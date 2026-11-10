import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { statusLabel, type Tone } from '@/lib/types';

/** 상태 3색(ing · ok · bad) + 장식용 mute(중립) · acc(강조). 다른 값은 타입 오류다. */
export type ChipTone = Tone | 'mute' | 'acc';

const tones: Record<ChipTone, string> = {
  ing: 'bg-ing-soft text-ing',
  ok: 'bg-ok-soft text-ok',
  bad: 'bg-bad-soft text-bad',
  mute: 'bg-surface-2 text-ink-2',
  acc: 'bg-accent-soft text-accent-ink',
};

interface StatusChipProps {
  tone: ChipTone;
  children?: ReactNode;
  className?: string;
}

export function StatusChip({ tone, children, className }: StatusChipProps) {
  return (
    <span
      className={cx(
        'inline-flex h-6 items-center gap-1 whitespace-nowrap rounded-pill px-2.5 text-chip font-bold',
        tones[tone],
        className,
      )}
    >
      {children ?? (tone in statusLabel ? statusLabel[tone as Tone] : null)}
    </span>
  );
}
