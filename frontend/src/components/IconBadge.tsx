import { cx } from '@/lib/cx';
import { Icon, type IconName } from './Icon';

export type BadgeTone = 'ok' | 'bad' | 'ing' | 'acc' | 'warn';

const tones: Record<BadgeTone, string> = {
  ok: 'bg-ok-soft text-ok',
  bad: 'bg-bad-soft text-bad',
  ing: 'bg-ing-soft text-ing',
  acc: 'bg-accent-soft text-accent-ink',
  warn: 'bg-warn-soft text-warn',
};

/** 상태 아이콘(체크 · 팔각 느낌표 · 모래시계 …). 장식이라 누를 수 없고 버튼처럼 보이지 않는다. */
export function IconBadge({ name, tone }: { name: IconName; tone: BadgeTone }) {
  return (
    <span
      aria-hidden="true"
      className={cx('grid h-14 w-14 place-items-center rounded-full', tones[tone])}
    >
      <Icon
        name={name}
        size={tone === 'acc' || tone === 'warn' ? 26 : 28}
        strokeWidth={name === 'check' ? 2.4 : 2}
      />
    </span>
  );
}
