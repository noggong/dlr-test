import { cx } from '@/lib/cx';
import type { Progress, Tone } from '@/lib/types';

const fill: Record<Tone, string> = { ing: 'bg-ing', ok: 'bg-ok', bad: 'bg-bad' };

interface ProgressBarProps {
  /** 0~3. -1(미션 시작 전)은 0 으로 그린다. */
  value: Progress;
  tone?: Tone;
  className?: string;
}

/** 진행도 막대 3칸 + n/3. */
export function ProgressBar({ value, tone = 'ing', className }: ProgressBarProps) {
  const n = Math.max(0, Math.min(3, value));
  return (
    <span
      role="img"
      aria-label={`진행도 ${n}/3`}
      className={cx('flex items-center gap-[3px]', className)}
    >
      {[1, 2, 3].map((i) => (
        <i key={i} className={cx('h-1.5 w-3.5 rounded-[3px]', i <= n ? fill[tone] : 'bg-line')} />
      ))}
      <b className="num ml-1.5 text-small">{n}/3</b>
    </span>
  );
}
