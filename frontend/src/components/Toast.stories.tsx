import type { Meta, StoryObj } from '@storybook/react';
import { Toast } from './Toast';

const meta: Meta<typeof Toast> = {
  title: '공용/Toast',
  component: Toast,
  decorators: [(S) => <div className="relative h-[200px] border border-line">{S()}</div>],
};
export default meta;

export const Live: StoryObj<typeof Toast> = {
  args: {
    children: (
      <>
        <b>minji</b> 님이 2번까지 풀었어요
      </>
    ),
  },
};
