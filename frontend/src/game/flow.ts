import type { ApiErrorCode } from '@/api/http';
import type { components } from '@/api/schema';
import { kstParts } from '@/app/clock';
import { pickQuestion, type Rng } from './pick';
import type { Progress } from './storage';

// 문제 풀이 상태 기계의 순수 부분(SPEC §6.4 · LLD §1.2). 호출 · 저장 · 화면은 useQuizFlow 가 한다.

type Schemas = components['schemas'];
export type Question = Schemas['Question'];
export type QuestionNo = Schemas['QuestionNo'];
export type MemberEntry = Schemas['MemberEntry'];

const HOUR_MS = 60 * 60 * 1000;

export interface GameWindow {
  /** 게임 날짜 = 서버 시각의 KST 날짜(game-session GS1 — `games/today` 의 `date` 는 08:00 뒤 다음 날) */
  date: string;
  startMs: number;
  deadlineMs: number;
}

/** 서버 기준 지금(ms)이 속한 KST 날짜의 07:00 ~ 08:00. */
export function gameWindow(nowMs: number): GameWindow {
  const { date } = kstParts(new Date(nowMs));
  const startMs = Date.parse(`${date}T07:00:00+09:00`);
  return { date, startMs, deadlineMs: startMs + HOUR_MS };
}

export const inWindow = (w: GameWindow, nowMs: number) =>
  nowMs >= w.startMs && nowMs < w.deadlineMs;

/** 처음 들어온 날의 진행 — 1번 · 지금 문제 없음. */
export function freshProgress(date: string): Progress {
  return { date, questionNo: 1, questionId: null, servedIds: [], passUsed: false, done: false };
}

/**
 * ⑦ 맞추기 — 서버 진행도(현황판 내 줄)를 믿는다. 번호가 다르면 `questionNo = progress + 1` · 지금 문제를 비운다.
 * `passedAtQuestionNo` 가 있으면 패스를 쓴 것이다. 서버가 3문제를 다 풀었다고 하면 `done`.
 * `forceClear` = 번호가 같아도 지금 문제를 비운다(신호가 `QUESTION_MISMATCH` 로 거부된 뒤).
 */
export function reconcile(
  stored: Progress | null,
  mine: Pick<MemberEntry, 'progress' | 'passedAtQuestionNo'> | undefined,
  date: string,
  forceClear = false,
): Progress {
  const base = stored ?? freshProgress(date);
  const serverProgress = Math.max(0, Math.min(3, Math.trunc(mine?.progress ?? 0)));
  const passUsed = base.passUsed || (mine?.passedAtQuestionNo ?? null) !== null;
  if (serverProgress >= 3) {
    return { ...base, questionNo: 3, questionId: null, passUsed, done: true };
  }
  const questionNo = (serverProgress + 1) as QuestionNo;
  const moved = questionNo !== base.questionNo;
  return {
    ...base,
    questionNo,
    questionId: moved || forceClear ? null : base.questionId,
    passUsed,
    done: false,
  };
}

/**
 * ③ 지금 문제 — 저장된 지금 문제가 은행에 그 번호 난이도로 있으면 그것(이어 풀기 · W23),
 * 아니면 `servedIds` 를 뺀 무작위 하나를 골라 `servedIds` 에 더한다. 고를 것이 없으면 `null`(QUESTIONS_EXHAUSTED).
 */
export function chooseQuestion(
  progress: Progress,
  bank: readonly Question[],
  rng?: Rng,
): { progress: Progress; question: Question } | null {
  if (progress.questionId !== null) {
    const kept = bank.find(
      (q) => q.questionId === progress.questionId && q.difficulty === progress.questionNo,
    );
    if (kept) return { progress, question: kept };
  }
  const picked = pickQuestion(bank, progress.questionNo, progress.servedIds, rng);
  if (!picked) return null;
  const servedIds = progress.servedIds.includes(picked.questionId)
    ? progress.servedIds
    : [...progress.servedIds, picked.questionId];
  return { progress: { ...progress, questionId: picked.questionId, servedIds }, question: picked };
}

/** ⑤ 풀이 응답 뒤 — 다음 번호(지금 문제 없음), 3번이면 `done`. */
export function afterSolve(progress: Progress): Progress {
  if (progress.questionNo >= 3) return { ...progress, questionId: null, done: true };
  return { ...progress, questionNo: (progress.questionNo + 1) as QuestionNo, questionId: null };
}

/** ⑥ 패스 응답 뒤 — 같은 번호 · 지금 문제 없음 · 패스 잠금. 넘긴 문제는 `servedIds` 에 남는다. */
export function afterPass(progress: Progress): Progress {
  return { ...progress, questionId: null, passUsed: true };
}

/** 부정 실패 신호 뒤 — 더 풀지 않는다. */
export function afterFailure(progress: Progress): Progress {
  return { ...progress, questionId: null, done: true };
}

export type Notice =
  'notParticipant' | 'exhausted' | 'serverError' | 'cannotJudge' | 'storageFailed' | 'passed';

export const NOTICE_TEXT: Record<Notice, string> = {
  notParticipant: '내일부터 참가해요. 오늘 07:00 이후에 가입해 오늘 게임에는 들어갈 수 없어요.',
  exhausted: '오늘은 문제가 준비되지 않았어요.',
  serverError: '잠시 뒤 다시 열어 주세요.',
  cannotJudge: '이 주소에서는 정답을 확인할 수 없어요(https 또는 localhost 로 여세요).',
  storageFailed:
    '이 브라우저에 풀이 상태를 저장하지 못했어요. 새로고침하면 실패 처리될 수 있으니 이 화면을 닫지 마세요.',
  passed: '패스했어요 — 같은 난이도의 다른 문제로 바뀌었어요.',
};

/** 신호 · 은행 · 현황판 오류에 할 일(SPEC §6.3). 분기는 `code` 로만(I8). */
export type ErrorAction =
  | { kind: 'navigate'; to: '/waiting' | '/result' | '/done' | '/solo' }
  | { kind: 'notice'; notice: Notice; then?: '/result' }
  | { kind: 'restart' }
  | { kind: 'resync' }
  | { kind: 'passLocked' }
  | { kind: 'ignore' };

export function errorAction(code: ApiErrorCode | 'NETWORK', afterDeadline: boolean): ErrorAction {
  switch (code) {
    case 'GAME_NOT_OPEN':
      return { kind: 'navigate', to: '/waiting' };
    case 'SUBMISSION_CLOSED':
    case 'MISSION_ALREADY_FINISHED':
      return { kind: 'navigate', to: afterDeadline ? '/result' : '/done' };
    case 'NOT_PARTICIPANT':
      return { kind: 'notice', notice: 'notParticipant', then: '/result' };
    case 'SOLO_GROUP':
      return { kind: 'navigate', to: '/solo' };
    case 'QUESTIONS_EXHAUSTED':
      return { kind: 'notice', notice: 'exhausted' };
    case 'MISSION_NOT_STARTED':
      return { kind: 'restart' };
    case 'QUESTION_OUT_OF_ORDER':
    case 'QUESTION_MISMATCH':
      return { kind: 'resync' };
    case 'PASS_ALREADY_USED':
      return { kind: 'passLocked' };
    case 'UNAUTHORIZED':
      // refresh 마저 실패했다 — 세션이 이미 /login 으로 보냈다(W18)
      return { kind: 'ignore' };
    default:
      return { kind: 'notice', notice: 'serverError' };
  }
}

/** 맞추기 · 은행부터 다시를 한 흐름에서 몇 번까지(LLD §1.2 · SPEC §6.4 ⑦). */
export const RECOVERY_LIMIT = 2;

/** 정답을 판정할 수 있는 주소인가 — Web Crypto 는 https · localhost 에서만 있다(W19). */
export function canJudge(): boolean {
  return typeof crypto !== 'undefined' && typeof crypto.subtle?.digest === 'function';
}

/** 현황판 요약 — 「n/m명 완료」. */
export function boardSummary(entries: readonly Pick<MemberEntry, 'status'>[]): string {
  const ok = entries.filter((e) => e.status === 'SUCCEEDED').length;
  return `${ok}/${entries.length}명 완료`;
}
