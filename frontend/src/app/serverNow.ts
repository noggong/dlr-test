import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { kstParts, type ServerClock } from './clock';
import { useSession, type Today } from './session';

const DAY_MS = 24 * 60 * 60 * 1000;
/** 경계(07:00 · 다음 게임)를 넘었는지 보는 틱. */
export const SERVER_TICK_MS = 1000;

/**
 * 서버 기준 지금(ms) — 1초마다 다시 그린다(LLD §3.7 · SPEC W30). 경계 전환과 상단 바 시각에 쓴다.
 * 기기 시계가 아니라 `clock`(서버 시각 + 단조 시계)을 읽는다.
 */
export function useServerNow(clock: ServerClock): number {
  const [now, setNow] = useState(() => clock.nowMs());
  useEffect(() => {
    setNow(clock.nowMs());
    const t = window.setInterval(() => setNow(clock.nowMs()), SERVER_TICK_MS);
    return () => window.clearInterval(t);
  }, [clock]);
  return now;
}

/** 다음 게임 시작(ms) — 지금이 오늘 `startAt` 전이면 그 시각, 아니면 하루 뒤(`NO_RESULT_YET` · 1명 안내). */
export function nextGameStartMs(today: Today, nowMs: number): number {
  const start = Date.parse(today.startAt);
  return nowMs < start ? start : start + (Math.floor((nowMs - start) / DAY_MS) + 1) * DAY_MS;
}

/** 서버 기준 ms → KST 「HH:MM」(상단 바 시각). */
export function hhmm(ms: number): string {
  const { hour, minute } = kstParts(new Date(ms));
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/** 다음 게임이 지금과 같은 KST 날짜면 「오늘」, 아니면 「내일」. */
export function dayWord(nextMs: number, nowMs: number): '오늘' | '내일' {
  return kstParts(new Date(nextMs)).date === kstParts(new Date(nowMs)).date ? '오늘' : '내일';
}

/** 다음 게임 전환에서 `games/today` 를 못 받았을 때 다시 부르기까지(ms) — 07:00 몰림에 쉬지 않고 부르지 않는다. */
export const NEXT_GAME_RETRY_MS = [5_000, 10_000, 20_000, 40_000, 60_000] as const;

/**
 * 서버 시각이 다음 게임 시작(`nextMs`)에 닿으면 `games/today` 를 다시 받고, 받았을 때만 `/`(첫 화면 규칙)로 간다.
 * 못 받으면 지면에 둔 채 늘어나는 대기 뒤 다시(LLD §4). `enabled` 가 거짓이면(아직 다음 게임을 모름) 보지 않는다.
 */
export function useNextGame(nextMs: number, nowMs: number, enabled = true): void {
  const { clock, loadSession } = useSession();
  const navigate = useNavigate();
  const busy = useRef(false);
  const failures = useRef(0);
  const retryAt = useRef(0);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    if (!enabled || busy.current || nowMs < nextMs || nowMs < retryAt.current) return;
    busy.current = true;
    void loadSession({ quiet: true }).then((ok) => {
      busy.current = false;
      if (!alive.current) return;
      if (ok) {
        navigate('/', { replace: true });
        return;
      }
      const wait = NEXT_GAME_RETRY_MS[Math.min(failures.current, NEXT_GAME_RETRY_MS.length - 1)];
      failures.current++;
      retryAt.current = clock.nowMs() + wait;
    });
  }, [enabled, nowMs, nextMs, clock, loadSession, navigate]);
}
