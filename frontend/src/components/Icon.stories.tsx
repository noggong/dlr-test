import type { Meta, StoryObj } from '@storybook/react';
import { Icon } from './Icon';
import { iconNames } from './iconPaths';
import { IconBadge } from './IconBadge';

const meta: Meta = { title: '바탕/Icon · IconBadge' };
export default meta;

export const Icons: StoryObj = {
  render: () => (
    <ul className="grid grid-cols-6 gap-4 text-small text-ink-2">
      {iconNames.map((n) => (
        <li key={n} className="grid justify-items-center gap-1">
          <Icon name={n} size={24} />
          {n}
        </li>
      ))}
    </ul>
  ),
};

export const Badges: StoryObj = {
  name: '상태 아이콘(장식 · 누를 수 없음)',
  render: () => (
    <div className="flex gap-3">
      <IconBadge name="check" tone="ok" />
      <IconBadge name="trophy" tone="ok" />
      <IconBadge name="octagon" tone="bad" />
      <IconBadge name="hourglass" tone="bad" />
      <IconBadge name="people" tone="ing" />
      <IconBadge name="pass" tone="acc" />
      <IconBadge name="alert" tone="warn" />
    </div>
  ),
};
