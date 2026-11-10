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

/** 남은 초를 받아 1초씩 줄인다. 0 에서 멈춘다. */
export function useCountdown(seconds: number): number {
  const [left, setLeft] = useState(seconds);
  useEffect(() => {
    setLeft(seconds);
    const t = window.setInterval(() => setLeft((v) => (v > 0 ? v - 1 : 0)), 1000);
    return () => window.clearInterval(t);
  }, [seconds]);
  return left;
}
