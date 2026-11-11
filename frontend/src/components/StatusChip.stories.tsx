import type { Meta, StoryObj } from '@storybook/react';
import { StatusChip } from './StatusChip';

const meta: Meta<typeof StatusChip> = { title: '공용/StatusChip', component: StatusChip };
export default meta;

export const All: StoryObj = {
  render: () => (
    <div className="flex flex-wrap gap-2">
      <StatusChip tone="ing" />
      <StatusChip tone="ok" />
      <StatusChip tone="bad" />
      <StatusChip tone="none" />
      <StatusChip tone="ing">그룹 진행 중</StatusChip>
      <StatusChip tone="acc">강조</StatusChip>
      <StatusChip tone="mute">중립</StatusChip>
    </div>
  ),
};

/** 미접속(prd D13 · SPEC W5) — 회색 칩 「미접속」. 현황판에서는 진행도 0/3 · 아래 줄 「접속하지 않음」과 함께 나온다. */
export const NotStarted: StoryObj = {
  render: () => <StatusChip tone="none" />,
};
