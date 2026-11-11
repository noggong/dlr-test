import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getLatestResult } from '@/api/endpoints';
import { ApiError } from '@/api/http';
import { dayWord, hhmm, nextGameStartMs, useNextGame, useServerNow } from '@/app/serverNow';
import { useSession } from '@/app/session';
import { toBoardRows } from '@/realtime/mapEntry';
import { useLiveBoard } from '@/realtime/useLiveBoard';
import { isDecided, resultSub, type DecidedResult, type LatestResult } from './resultText';
import { ResultView } from './ResultView';

/** `PENDING` 이면 이 간격으로 다시 읽는다(RB13). */
export const PENDING_POLL_MS = 3000;
/** `PENDING` 다시 읽기 상한 — 넘으면 멈추고 다시 시도 버튼(LLD §4). */
export const PENDING_POLL_LIMIT = 40;
/** 08:00 ~ 08:00:03 — 랭킹 확정(`ranking_final`)을 기다리는 창(RB13 · W29). */
export const RANKING_GRACE_MS = 3000;
/** 08:00:03 「뒤」에 읽도록 조금 더 기다린다. */
const AFTER_GRACE_MS = 500;

type Phase =
  | { kind: 'loading' }
  | { kind: 'pending'; data: LatestResult }
  | { kind: 'ready'; data: DecidedResult }
  | { kind: 'none' }
  | { kind: 'error' };

// 08:00 뒤에는 현황판 조회를 쓰지 않는다(RB13) — 실시간 연결의 이벤트만 쓴다
const LIVE_DEPS = {
  getBoard: () => Promise.reject(new Error('RB13 — 08:00 뒤 현황판 조회 안 함')),
};

/**
 * 결과 지면 컨테이너 — `GET /games/results/latest`. `PENDING` 이면 로딩 + 실시간 `group.result` 또는
 * 3초마다 다시 읽기, `rankingFinal == false` 면 「랭킹 확정 중」 + `ranking_final` 을 기다린다(W29).
 * 다음 게임 시작(서버 시각)이 되면 첫 화면 규칙으로 간다.
 */
export default function ResultPage() {
  const { me, today, clock } = useSession();
  const navigate = useNavigate();
  const now = useServerNow(clock);
  const t = today.status === 'ready' ? today.today : null;
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });
  // 마감은 결과의 날짜로 잰다 — 세션 today 는 08:00 뒤 다음 날 게임이다(game-session GS1)
  const resultDate = phase.kind === 'ready' || phase.kind === 'pending' ? phase.data.date : null;
  const deadline = resultDate ? Date.parse(`${resultDate}T08:00:00+09:00`) : 0;
  const inflight = useRef<AbortController | null>(null);
  const polls = useRef(0);
  const rereads = useRef(0);

  const load = useCallback(() => {
    inflight.current?.abort();
    const c = new AbortController();
    inflight.current = c;
    getLatestResult(c.signal).then(
      (r) => {
        if (c.signal.aborted) return;
        setPhase(isDecided(r) ? { kind: 'ready', data: r } : { kind: 'pending', data: r });
      },
      (e: unknown) => {
        if (c.signal.aborted) return;
        const code = e instanceof ApiError ? e.code : undefined;
        if (code === 'SOLO_GROUP') navigate('/solo', { replace: true });
        else setPhase(code === 'NO_RESULT_YET' ? { kind: 'none' } : { kind: 'error' });
      },
    );
  }, [navigate]);

  useEffect(() => {
    load();
    return () => inflight.current?.abort();
  }, [load]);

  // PENDING — 3초마다 다시(RB13), 상한을 넘으면 멈춘다
  useEffect(() => {
    if (phase.kind !== 'pending') return;
    if (polls.current >= PENDING_POLL_LIMIT) {
      setPhase({ kind: 'error' });
      return;
    }
    const h = window.setTimeout(() => {
      polls.current++;
      load();
    }, PENDING_POLL_MS);
    return () => window.clearTimeout(h);
  }, [phase, load]);

  // 실시간은 확정 · 랭킹 확정을 기다리는 동안, 서버 시각 08:00:03 전에 연 지면에서만(W26 · RB13)
  const waiting = phase.kind === 'pending' || (phase.kind === 'ready' && !phase.data.rankingFinal);
  // 기다리기 시작할 때 한 번 정한다 — PENDING 재조회 · 잠정 결과로 넘어가도 연결을 그대로 둔다(끝은 서버가 낸다 — RB8)
  const liveOn = useMemo(
    () => waiting && clock.nowMs() < deadline + RANKING_GRACE_MS,
    [waiting, clock, deadline],
  );
  const live = useLiveBoard({ viewerId: me ?? undefined, enabled: liveOn, deps: LIVE_DEPS });

  // group.result · group.ranking_final → 결과 · 랭킹 · 내 순위를 다시 읽는다(W29)
  const lastEvent = live.lastEvent;
  useEffect(() => {
    if (lastEvent?.type === 'group.result' || lastEvent?.type === 'group.ranking_final') load();
  }, [lastEvent, load]);

  // 랭킹 미확정인데 실시간으로 받을 길이 없다(W29) — 연결이 끝났으면 바로 한 번,
  // 그 뒤(또는 처음부터 창 밖이었으면) 08:00:03 뒤 한 번. 합쳐 최대 2번.
  const connState = live.connection.state;
  const connDone = liveOn && (connState === 'ended' || connState === 'stopped');
  const notFinal = phase.kind === 'ready' && !phase.data.rankingFinal;
  useEffect(() => {
    if (!notFinal || (liveOn && !connDone)) return;
    if (!connDone && rereads.current === 0) rereads.current = 1; // 연결이 없었다 — 뒤의 한 번만
    if (rereads.current >= 2) return;
    const wait =
      rereads.current === 0
        ? 0
        : Math.max(deadline + RANKING_GRACE_MS + AFTER_GRACE_MS - clock.nowMs(), PENDING_POLL_MS);
    const h = window.setTimeout(() => {
      rereads.current++;
      load();
    }, wait);
    return () => window.clearTimeout(h);
  }, [phase, notFinal, liveOn, connDone, deadline, clock, load]);

  // 다음 게임 시작(서버 시각) — games/today 를 다시 받으면 첫 화면 규칙으로(LLD §4)
  const [fallbackNext] = useState(() => (t ? nextGameStartMs(t, clock.nowMs()) : 0));
  const nextMs = phase.kind === 'ready' ? Date.parse(phase.data.nextGameStartAt) : fallbackNext;
  useNextGame(nextMs, now, t !== null && phase.kind !== 'loading');

  const next = useMemo(() => ({ until: nextMs, now: () => clock.nowMs() }), [nextMs, clock]);
  const rows = useMemo(
    () => (phase.kind === 'ready' ? toBoardRows(phase.data.ranking, me ?? undefined) : []),
    [phase, me],
  );
  if (!t) return null;

  const retry = () => {
    polls.current = 0;
    rereads.current = 0;
    setPhase({ kind: 'loading' });
    load();
  };
  const common = {
    clockText: hhmm(now),
    next: phase.kind === 'error' ? undefined : next,
    nextWhen: `${dayWord(nextMs, now)} 07:00 시작`,
  };
  if (phase.kind !== 'ready') {
    const kind = phase.kind === 'loading' ? 'pending' : phase.kind;
    return (
      <ResultView
        kind={kind}
        group={{ name: t.group.name, size: `${t.group.members.length}명` }}
        loading={phase.kind === 'loading'}
        onRetry={retry}
        {...common}
      />
    );
  }
  const r = phase.data;
  return (
    <ResultView
      kind={r.groupStatus === 'SUCCEEDED' ? 'ok' : 'fail'}
      group={{ name: t.group.name, size: `${r.ranking.length}명` }}
      sub={resultSub(r)}
      myRank={r.myRank}
      total={r.ranking.length}
      ranking={rows}
      rankingFinal={r.rankingFinal}
      {...common}
    />
  );
}
