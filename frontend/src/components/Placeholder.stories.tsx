import type { Meta, StoryObj } from '@storybook/react';

const meta: Meta = { title: '설정/토큰 확인' };
export default meta;

export const Swatches: StoryObj = {
  render: () => (
    <div className="flex gap-2">
      {['bg-accent', 'bg-ok', 'bg-ing', 'bg-bad', 'bg-warn'].map((c) => (
        <span key={c} className={`h-8 w-8 rounded-r1 ${c}`} />
      ))}
    </div>
  ),
};
