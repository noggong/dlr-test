import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { Icon, type IconName } from './Icon';

type CalloutTone = 'info' | 'warn' | 'bad';

const tones: Record<CalloutTone, string> = {
  info: 'bg-surface-2 text-ink-2',
  warn: 'bg-warn-soft text-warn',
  bad: 'bg-bad-soft text-bad',
};
const defaultIcon: Record<CalloutTone, IconName> = { info: 'info', warn: 'alert', bad: 'alert' };

interface CalloutProps {
  tone?: CalloutTone;
  icon?: IconName;
  role?: 'status' | 'alert';
  children: ReactNode;
  className?: string;
}

/** 안내 · 경고 · 오류 한 덩어리. 빈 상태도 이것 한 줄로 그린다. */
export function Callout({ tone = 'info', icon, role, children, className }: CalloutProps) {
  return (
    <div
      role={role}
      className={cx(
        'flex items-start gap-2 rounded-r2 px-3.5 py-3 text-small',
        tones[tone],
        className,
      )}
    >
      <Icon name={icon ?? defaultIcon[tone]} className="mt-px flex-none" />
      <span>{children}</span>
    </div>
  );
}
