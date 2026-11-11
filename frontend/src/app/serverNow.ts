import { useEffect, useState } from 'react';
import { kstParts, type ServerClock } from './clock';
import type { Today } from './session';

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
