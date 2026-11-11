import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  getBoard,
  postFailure,
  postPass,
  postQuestionBank,
  postShown,
  postSolve,
} from '@/api/endpoints';
import { ApiError, NetworkError } from '@/api/http';
import { isoKst, type ServerClock } from '@/app/clock';
import { isCorrect } from './answer';
import { shouldReportCheat } from './cheat';
import {
  afterFailure,
  afterPass,
  afterSolve,
  canJudge,
  chooseQuestion,
  errorAction,
  gameWindow,
  inWindow,
  reconcile,
  RECOVERY_LIMIT,
  type GameWindow,
  type Notice,
  type Question,
  type QuestionNo,
} from './flow';
import type { Rng } from './pick';
import { loadProgress, saveProgress, type Progress, type StorageSource } from './storage';
import { useMissionWarning } from './warn';

// 문제 풀이 흐름(SPEC §6.4 ①~⑦ · W22 ~ W25 · LLD §1.2). 순수 판단은 flow.ts, 여기는 호출 · 저장 · 화면 상태.
// 저장 순서: ⑦ 맞춘 진행 → (은행) → ③ 고른 문제 → (진입 신호). 풀이 · 패스는 응답 뒤에 저장한다(§6.5).

export interface QuizFlowView {
  status: 'loading' | 'question' | 'blocked';
  question: Question | null;
  questionNo: QuestionNo;
  passUsed: boolean;
  /** 「틀렸어요」 */
  wrong: boolean;
  /** 신호를 보내는 중 — 패스 · 제출 잠금 */
  busy: boolean;
  /** 패스 확인 시트 */
  passAsk: boolean;
  /** 방금 패스했다(안내) */
  passed: boolean;
  /** 서버 기준 08:00 — 입력 · 패스 · 제출 잠금(W25) */
  closed: boolean;
  /** 부정행위 경고 대화상자(W24) */
  warn: boolean;
  notice: Notice | null;
  /** 안내 뒤 갈 곳(`NOT_PARTICIPANT` → 결과) */
  noticeThen?: '/result';
  storageFailed: boolean;
  /** Web Crypto 가 없어 판정할 수 없다(W19) */
  cannotJudge: boolean;
}

export interface QuizFlow extends QuizFlowView {
  window: GameWindow;
  submit: (answer: string) => void;
  askPass: () => void;
  cancelPass: () => void;
  confirmPass: () => void;
  closeWarn: () => void;
  retry: () => void;
  /** 밖에서 지면을 떠나게 할 때(그룹 결과 확정) — 제출 중이면 응답 뒤에 간다 */
  leave: (to: '/done' | '/result') => void;
}

export interface QuizFlowOptions {
  me: string;
  clock: ServerClock;
  /** 테스트용 — 난수 · 저장소 · 판정 가능 여부 */
  rng?: Rng;
  storage?: StorageSource;
  judgeable?: () => boolean;
}

/** 08:00 을 보는 틱 — 1초보다 촘촘하게(W25). */
export const CLOSE_TICK_MS = 250;

const INITIAL: QuizFlowView = {
  status: 'loading',
  question: null,
  questionNo: 1,
  passUsed: false,
  wrong: false,
  busy: false,
  passAsk: false,
  passed: false,
  closed: false,
  warn: false,
  notice: null,
  storageFailed: false,
  cannotJudge: false,
};

type Busy = null | 'submit' | 'other';
type Step = (signal: AbortSignal) => Promise<void>;

const isAbort = (e: unknown, signal: AbortSignal) =>
  signal.aborted || (e instanceof DOMException && e.name === 'AbortError');

export function useQuizFlow({
  me,
  clock,
  rng,
  storage,
  judgeable = canJudge,
}: QuizFlowOptions): QuizFlow {
  const navigate = useNavigate();
  // 게임 창은 들어온 순간의 서버 시각으로 정한다(GS1 — 세션 today 는 08:00 뒤 다음 날로 바뀐다)
  const [win] = useState(() => gameWindow(clock.nowMs()));
  const [view, setView] = useState<QuizFlowView>(INITIAL);
  const patch = useCallback((p: Partial<QuizFlowView>) => setView((v) => ({ ...v, ...p })), []);

  const progress = useRef<Progress | null>(null);
  const bank = useRef<Question[] | null>(null);
  const current = useRef<Question | null>(null);
  const busy = useRef<Busy>(null);
  const ctrl = useRef<AbortController | null>(null);
  const recoveries = useRef(0);
  const closed = useRef(false);
  const left = useRef(false);
  const pendingLeave = useRef<string | null>(null);
  const [active, setActive] = useState(false);

  const go = useCallback(
    (to: string) => {
      if (left.current) return;
      left.current = true;
      setActive(false);
      ctrl.current?.abort();
      navigate(to, { replace: true });
    },
    [navigate],
  );

  /** 저장한다. false = 저장하지 못함(안내를 띄운다). */
  const save = useCallback(
    (p: Progress): boolean => {
      progress.current = p;
      const ok = saveProgress(me, p, storage);
      patch(
        ok
          ? { questionNo: p.questionNo, passUsed: p.passUsed }
          : { questionNo: p.questionNo, passUsed: p.passUsed, storageFailed: true },
      );
      return ok;
    },
    [me, storage, patch],
  );

  // 제출 중이면(08:00 전에 누른 것 — D1) 응답을 기다린 뒤 떠난다. 그 밖의 호출은 버리고 떠난다.
  const leave = useCallback(
    (to: string) => {
      // 08:00 뒤에는 어디로 가라 해도 결과다(W25)
      const dest = clock.nowMs() >= win.deadlineMs ? '/result' : to;
      if (busy.current === 'submit') pendingLeave.current = dest;
      else go(dest);
    },
    [go, clock, win],
  );

  // 진행 중 호출 하나만 — 새로 시작하면 앞의 것을 버린다. 실패는 handle 이 §6.3 대로.
  const run = useCallback(
    (step: Step, kind: Exclude<Busy, null> = 'other'): void => {
      ctrl.current?.abort();
      const c = new AbortController();
      ctrl.current = c;
      busy.current = kind;
      patch({ busy: true });
      void step(c.signal)
        .catch((e: unknown) => {
          if (!isAbort(e, c.signal)) handle(e);
        })
        .finally(() => {
          if (ctrl.current !== c) return; // 다른 호출이 이어받았다
          busy.current = null;
          patch({ busy: false });
          const to = pendingLeave.current ?? (closed.current ? '/result' : null);
          if (to) go(to);
        });
    },
    // handle 은 아래에서 run 을 다시 부른다 — 서로 참조하므로 handleRef 로 잇는다
    [patch, go],
  );

  // ③ ④ — 고르고 저장한 뒤 진입 신호
  const present = useCallback(
    async (p: Progress, signal: AbortSignal, opts: { passed?: boolean } = {}) => {
      const chosen = bank.current ? chooseQuestion(p, bank.current, rng) : null;
      if (!chosen) {
        save(p);
        setActive(false);
        patch({ status: 'blocked', notice: 'exhausted', question: null });
        return;
      }
      save(chosen.progress);
      await postShown(
        { questionNo: chosen.progress.questionNo, questionId: chosen.question.questionId },
        signal,
      );
      current.current = chosen.question;
      patch({
        status: 'question',
        question: chosen.question,
        wrong: false,
        passed: opts.passed ?? false,
        notice: null,
      });
    },
    [rng, save, patch],
  );

  const fromBank = useCallback(
    async (p: Progress, signal: AbortSignal) => {
      const res = await postQuestionBank(signal);
      bank.current = res.questions;
      await present(p, signal);
    },
    [present],
  );

  /** 서버 상태를 다시 읽어 맞춘다(① 의 현황판). 끝난 미션이면 /done. */
  const readBoard = useCallback(
    async (signal: AbortSignal) => {
      const board = await getBoard(signal);
      const mine = board.entries.find((e) => e.userId === me);
      const finished =
        mine?.status === 'SUCCEEDED' ||
        mine?.status === 'CHEAT_FAILED' ||
        board.groupStatus === 'SUCCEEDED' ||
        board.groupStatus === 'FAILED';
      return { mine, finished };
    },
    [me],
  );

  // ① ~ ④
  const enter: Step = useCallback(
    async (signal) => {
      patch({ status: 'loading', notice: null, question: null });
      const { mine, finished } = await readBoard(signal);
      const stored = loadProgress(me, win.date, storage);
      if (finished) {
        if (stored && !stored.done) save({ ...stored, questionId: null, done: true });
        go('/done');
        return;
      }
      if (
        mine &&
        shouldReportCheat({
          inWindow: inWindow(win, clock.nowMs()),
          started: mine.started,
          status: mine.status,
          stored,
        })
      ) {
        await postFailure(signal);
        save(afterFailure(reconcile(null, mine, win.date)));
        go('/done');
        return;
      }
      const p = reconcile(stored, mine, win.date);
      const saved = save(p); // 은행(미션 시작)보다 먼저 — SPEC §6.4 ②
      if (p.done) {
        go('/done');
        return;
      }
      if (!saved && !mine?.started) {
        // 저장할 수 없는 채로 시작하면 새로고침 한 번이 되돌릴 수 없는 부정 실패가 된다 — 시작하지 않는다
        patch({ status: 'blocked', notice: 'storageBlocked', question: null });
        return;
      }
      setActive(true);
      await fromBank(p, signal);
    },
    [readBoard, me, win, storage, clock, save, go, patch, fromBank],
  );

  // ⑦ — 신호가 순서 · 문제 불일치로 거부됐다
  const resync: Step = useCallback(
    async (signal) => {
      const { mine, finished } = await readBoard(signal);
      if (finished) {
        go('/done');
        return;
      }
      // 다른 탭이 이 번호의 지금 문제를 새로 골랐으면(패스 등) 그것부터 — 새로 고르면 그 번호가 잠긴다
      const rejected = progress.current?.questionId ?? null;
      const disk = loadProgress(me, win.date, storage);
      const other = disk !== null && disk.questionId !== null && disk.questionId !== rejected;
      const p = other
        ? reconcile(disk, mine, win.date)
        : reconcile(progress.current ?? disk, mine, win.date, true);
      save(p);
      if (p.done) {
        go('/done');
        return;
      }
      if (bank.current) await present(p, signal);
      else await fromBank(p, signal);
    },
    [readBoard, me, win, storage, save, go, present, fromBank],
  );

  const handleRef = useRef<(e: unknown) => void>(() => {});
  const handle = (e: unknown) => handleRef.current(e);
  handleRef.current = (e: unknown) => {
    const code = e instanceof ApiError ? e.code : e instanceof NetworkError ? 'NETWORK' : undefined;
    const action = errorAction(code ?? 'NETWORK', win.deadlineMs - clock.nowMs());
    const recover = (step: Step) => {
      if (recoveries.current >= RECOVERY_LIMIT) {
        if (!current.current) setActive(false);
        patch({ status: current.current ? 'question' : 'blocked', notice: 'serverError' });
        return;
      }
      recoveries.current++;
      run(step);
    };
    switch (action.kind) {
      case 'navigate':
        go(action.to);
        return;
      case 'notice': {
        // 문제를 보던 중의 일시 오류면 문제를 둔다 — 다시 제출할 수 있다
        const keep = current.current !== null && action.notice === 'serverError';
        if (!keep) setActive(false);
        patch({
          status: keep ? 'question' : 'blocked',
          notice: action.notice,
          noticeThen: action.then,
        });
        return;
      }
      case 'restart':
        recover(async (signal) => {
          const p = progress.current ?? reconcile(null, undefined, win.date);
          await fromBank(p, signal);
        });
        return;
      case 'resync':
        recover(resync);
        return;
      case 'passLocked':
        if (progress.current) save({ ...progress.current, passUsed: true });
        patch({ passUsed: true, passAsk: false });
        return;
      case 'ignore':
        return;
    }
  };

  // 들어올 때 — 창 밖이면 그 지면으로, 아니면 ①부터(W23 — 새로고침도 ①부터)
  useEffect(() => {
    const now = clock.nowMs();
    if (now < win.startMs) go('/waiting');
    else if (now >= win.deadlineMs) go('/result');
    else {
      if (!judgeable()) patch({ cannotJudge: true });
      run(enter);
    }
    return () => {
      ctrl.current?.abort();
    };
    // 마운트 때 한 번
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 08:00 잠금(W25) — 서버 기준 시각으로 촘촘히 본다
  useEffect(() => {
    const check = () => {
      if (closed.current || clock.nowMs() < win.deadlineMs) return;
      closed.current = true;
      setActive(false);
      patch({ closed: true, passAsk: false, warn: false });
      leave('/result');
    };
    check();
    const t = window.setInterval(check, CLOSE_TICK_MS);
    return () => window.clearInterval(t);
  }, [clock, win, patch, leave]);

  // 경고(W24) — 미션 중에만
  useMissionWarning(active && !view.closed, () => patch({ warn: true }));

  const submit = useCallback(
    (answer: string) => {
      if (busy.current || closed.current || left.current) return;
      const q = current.current;
      const p = progress.current;
      if (!q || !p || p.questionId !== q.questionId) return;
      const clickedMs = clock.nowMs();
      if (clickedMs >= win.deadlineMs) return; // 잠금 틱보다 먼저 눌렸다 — 틱이 곧 닫는다
      if (!judgeable()) {
        patch({ cannotJudge: true });
        return;
      }
      patch({ wrong: false, passed: false });
      run(async (signal) => {
        const ok = await isCorrect(q, answer);
        const successMs = clock.nowMs();
        if (signal.aborted) return;
        if (!ok) {
          patch({ wrong: true });
          return;
        }
        await postSolve(
          {
            questionNo: p.questionNo,
            questionId: q.questionId,
            clickedAt: isoKst(new Date(clickedMs)),
            successTime: isoKst(new Date(successMs)),
          },
          signal,
        );
        recoveries.current = 0;
        const next = afterSolve(p);
        save(next);
        current.current = null;
        if (next.done || clock.nowMs() >= win.deadlineMs) {
          busy.current = null;
          go(next.done && clock.nowMs() < win.deadlineMs ? '/done' : '/result');
          return;
        }
        // 그사이 떠나라는 말(그룹 결과 확정 · 08:00)을 받았으면 다음 문제를 내지 않는다 — finally 가 떠난다
        if (pendingLeave.current || closed.current) return;
        busy.current = 'other'; // 다음 문제의 진입 신호는 08:00 에 기다리지 않는다
        await present(next, signal);
      }, 'submit');
    },
    [clock, win, judgeable, patch, run, save, go, present],
  );

  const askPass = useCallback(() => {
    if (busy.current || closed.current || progress.current?.passUsed) return;
    patch({ passAsk: true });
  }, [patch]);

  const cancelPass = useCallback(() => patch({ passAsk: false }), [patch]);

  const confirmPass = useCallback(() => {
    patch({ passAsk: false });
    if (busy.current || closed.current || left.current) return;
    const q = current.current;
    const p = progress.current;
    if (!q || !p || p.passUsed || clock.nowMs() >= win.deadlineMs) return;
    patch({ wrong: false });
    run(async (signal) => {
      await postPass({ questionNo: p.questionNo, questionId: q.questionId }, signal);
      recoveries.current = 0;
      const next = afterPass(p);
      save(next);
      current.current = null;
      await present(next, signal, { passed: true });
    });
  }, [clock, win, patch, run, save, present]);

  const closeWarn = useCallback(() => patch({ warn: false }), [patch]);

  const retry = useCallback(() => {
    recoveries.current = 0;
    run(enter);
  }, [run, enter]);

  return useMemo(
    () => ({
      ...view,
      window: win,
      submit,
      askPass,
      cancelPass,
      confirmPass,
      closeWarn,
      retry,
      leave,
    }),
    [view, win, submit, askPass, cancelPass, confirmPass, closeWarn, retry, leave],
  );
}
