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
      <StatusChip tone="ing">그룹 진행 중</StatusChip>
      <StatusChip tone="acc">강조</StatusChip>
      <StatusChip tone="mute">중립</StatusChip>
    </div>
  ),
};
