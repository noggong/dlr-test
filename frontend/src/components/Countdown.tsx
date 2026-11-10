import { cx } from '@/lib/cx';
import { formatRemain, useCountdown } from '@/lib/time';

type Size = 'hero' | 'inline' | 'bare';

interface CountdownProps {
  seconds: number;
  /** hero = 대기 지면 큰 숫자 · inline = 카드 안 24 · bare = 글자 크기를 물려받음(상단 바) */
  size?: Size;
  /** 시:분:초를 늘 두 자리로(다음 게임까지 22:47:30) */
  padHours?: boolean;
  label?: string;
  className?: string;
}

/** 시계가 주인공 — 고정폭 숫자(tabular-nums). */
export function Countdown({
  seconds,
  size = 'inline',
  padHours,
  label,
  className,
}: CountdownProps) {
  const left = useCountdown(seconds);
  const text = formatRemain(left, padHours);
  const long = left >= 3600;
  return (
    <span
      role="timer"
      aria-label={label ? `${label} ${text}` : undefined}
      className={cx(
        'num',
        size === 'hero' &&
          (long
            ? 'block text-count-m font-extrabold tracking-[-0.02em] @pc:text-count'
            : 'block text-count font-extrabold tracking-[-0.02em] @pc:text-count-pc'),
        size === 'inline' && 'text-title font-extrabold tracking-[-0.01em]',
        className,
      )}
    >
      {text}
    </span>
  );
}
