import type { Meta, StoryObj } from '@storybook/react';
import { ProgressBar } from './ProgressBar';

const meta: Meta<typeof ProgressBar> = { title: '공용/ProgressBar', component: ProgressBar };
export default meta;

export const Values: StoryObj = {
  render: () => (
    <div className="grid gap-3">
      <ProgressBar value={-1} />
      <ProgressBar value={0} />
      <ProgressBar value={1} />
      <ProgressBar value={2} />
      <ProgressBar value={3} tone="ok" />
      <ProgressBar value={2} tone="bad" />
    </div>
  ),
};
