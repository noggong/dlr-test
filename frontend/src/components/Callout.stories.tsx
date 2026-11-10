import type { Meta, StoryObj } from '@storybook/react';
import { Callout } from './Callout';

const meta: Meta<typeof Callout> = { title: '공용/Callout', component: Callout };
export default meta;

export const Tones: StoryObj = {
  render: () => (
    <div className="grid max-w-[390px] gap-3">
      <Callout>07:00이 되면 1번 문제가 자동으로 열려요.</Callout>
      <Callout tone="warn">부정행위로 실패 처리될 수 있어요.</Callout>
      <Callout tone="bad" icon="lock" role="status">
        <b>08:00 마감</b> — 더는 제출할 수 없어요.
      </Callout>
    </div>
  ),
};
