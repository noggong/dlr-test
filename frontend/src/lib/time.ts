import { useEffect, useState } from 'react';

/** 초 → 「mm:ss」(1시간 미만) 또는 「h:mm:ss」 */
export function formatRemain(total: number, pad = false): string {
  const s = Math.max(0, Math.floor(total));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const two = (n: number) => String(n).padStart(2, '0');
  if (h > 0 || pad) return `${pad ? two(h) : h}:${two(m)}:${two(sec)}`;
  return `${two(m)}:${two(sec)}`;
}

/** 초 → 「4시간 50분」 · 「5분」(분은 올림 — 남은 299초는 5분). */
export function remainWords(total: number): string {
  const minutes = Math.ceil(Math.max(0, total) / 60);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m}분`;
  return m === 0 ? `${h}시간` : `${h}시간 ${m}분`;
}

/**
 * 남은 시간을 주는 두 방법. `seconds` = 고정 초(스토리 · 목 데이터) — 1초씩 줄인다.
 * `until` + `now` = 서버 기준 시각(ms)과 서버 시계(`clock.nowMs`) — 틱마다 다시 잰다(SPEC W30).
 */
export type Remain =
  | { seconds: number; until?: undefined; now?: undefined }
  | { until: number; now: () => number; seconds?: undefined };

/** `until` 까지 남은 초(올림 — 0 에 닿는 순간이 `until`). */
export function remainSeconds(until: number, nowMs: number): number {
  return Math.max(0, Math.ceil((until - nowMs) / 1000));
}

/** 지금 남은 초. */
export function remainOf(r: Remain): number {
  return r.until === undefined ? r.seconds : remainSeconds(r.until, r.now());
}

/** 서버 시계로 잴 때의 틱 — 1초보다 촘촘해야 초가 건너뛰지 않는다. */
const UNTIL_TICK_MS = 250;

/** 남은 초를 1초마다 다시 그린다. 0 에서 멈춘다. */
export function useRemain(r: Remain): number {
  const { seconds, until, now } = r;
  const [left, setLeft] = useState(() => remainOf(r));
  useEffect(() => {
    if (until === undefined || now === undefined) {
      setLeft(seconds ?? 0);
      const t = window.setInterval(() => setLeft((v) => (v > 0 ? v - 1 : 0)), 1000);
      return () => window.clearInterval(t);
    }
    const tick = () => setLeft(remainSeconds(until, now()));
    tick();
    const t = window.setInterval(tick, UNTIL_TICK_MS);
    return () => window.clearInterval(t);
  }, [seconds, until, now]);
  return left;
}

/** 남은 초를 받아 1초씩 줄인다. 0 에서 멈춘다. */
export function useCountdown(seconds: number): number {
  return useRemain({ seconds });
}
