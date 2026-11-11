import type { Meta, StoryObj } from '@storybook/react';
import { groups, solo } from '@/mocks';
import { SoloView } from './SoloView';

// 시안 solo 1 + 공통 Loading · Empty — SPEC §1 · ORACLE O2 · O3.
const meta: Meta<typeof SoloView> = {
  title: 'Pages/Solo',
  component: SoloView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof SoloView>;

const base = {
  group: groups.solo,
  clockText: solo.now,
  members: solo.members,
  next: { seconds: solo.nextSeconds },
};

export const Default: Story = { args: base };

/** 07:00 전 — 아직 그룹원이 더 들어올 수 있다 */
export const BeforeStart: Story = {
  args: {
    ...base,
    clockText: '05:30',
    members: [{ id: 'junho', me: true }],
    next: { seconds: 5400 },
    nextWhen: '오늘 07:00 시작',
    beforeStart: true,
  },
};

export const Loading: Story = { args: { ...base, loading: true } };

export const Empty: Story = { args: { ...base, members: [] } };
