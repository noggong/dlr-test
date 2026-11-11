import type { Meta, StoryObj } from '@storybook/react';
import { done, groups } from '@/mocks';
import { DoneView, type DoneSelf, type DoneViewProps } from './DoneView';

// 시안 done 1 ~ 4 + 공통 Loading · Empty — SPEC §1 · ORACLE O2 · O3 · O10.
const meta: Meta<typeof DoneView> = {
  title: 'Pages/Done',
  component: DoneView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof DoneView>;

const base = (key: 'ok' | 'selffail' | 'groupok' | 'groupfail', self: DoneSelf): DoneViewProps => {
  const d = done[key];
  return {
    group: groups.main,
    clock: { deadlineSeconds: d.deadlineSeconds, tone: 'hot' },
    self,
    board: { summary: d.board.summary, groupStatus: d.board.group, rows: d.board.rows },
    result: d.result,
  };
};

export const Ok: Story = { args: base('ok', 'ok') };

export const Selffail: Story = { args: base('selffail', 'selffail') };

export const GroupOk: Story = { args: base('groupok', 'ok') };

export const GroupFail: Story = { args: base('groupfail', 'ok') };

export const Loading: Story = {
  args: { ...base('ok', 'ok'), loading: true },
};

export const Empty: Story = {
  args: { ...base('ok', 'ok'), board: { ...base('ok', 'ok').board, rows: [] } },
};
