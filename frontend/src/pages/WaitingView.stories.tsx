import type { Meta, StoryObj } from '@storybook/react';
import { groups, waiting } from '@/mocks';
import { WaitingView } from './WaitingView';

// 시안 waiting 1 · 2 + 공통 Loading · Empty — SPEC §1 · ORACLE O2 · O3.
const meta: Meta<typeof WaitingView> = {
  title: 'Pages/Waiting',
  component: WaitingView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof WaitingView>;

const base = (key: 'yday' | 'noyday') => {
  const d = waiting[key];
  return {
    group: groups.main,
    clockText: d.now,
    startIn: { seconds: d.seconds },
    members: d.members,
    yesterday: d.yesterday && { title: '어제 결과', ...d.yesterday },
  };
};

export const Yday: Story = { args: base('yday') };

export const Noyday: Story = { args: base('noyday') };

export const Loading: Story = { args: { ...base('yday'), loading: true } };

export const Empty: Story = { args: { ...base('yday'), members: [] } };
