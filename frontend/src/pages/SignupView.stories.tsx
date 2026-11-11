import type { Meta, StoryObj } from '@storybook/react';
import { signup } from '@/mocks';
import { MSG } from './authForm';
import { SignupView } from './SignupView';

// 시안 signup 1~4 + 공통 Loading · Empty(목록 없는 지면이라 기본과 같다) — SPEC §1 · ORACLE O2 · O3.
const meta: Meta<typeof SignupView> = {
  title: 'Pages/Signup',
  component: SignupView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof SignupView>;

const base = { loginId: signup.id, password: signup.password };

export const New: Story = {
  args: { values: { ...base, mode: 'new', groupName: signup.newGroup } },
};

export const Full: Story = {
  args: {
    values: { ...base, mode: 'join', groupName: signup.fullGroup },
    errors: { groupName: MSG.full(signup.fullGroup), groupFull: true },
  },
};

export const Dup: Story = {
  args: {
    values: { ...base, mode: 'new', groupName: signup.newGroup },
    errors: { groupName: MSG.dup },
  },
};

export const Ok: Story = {
  args: {
    values: { ...base, mode: 'new', groupName: signup.newGroup },
    done: {
      userId: signup.done.id,
      groupName: signup.done.group,
      members: signup.done.members,
      joinedAt: signup.done.joinedAt,
      firstGame: '11월 12일',
      joinsToday: true,
    },
  },
};

/** 06:50 이후 가입 — 오늘 미션에는 참여할 수 없고 firstGameDate(다음 날)부터 */
export const OkLate: Story = {
  args: {
    values: Ok.args!.values!,
    done: {
      ...Ok.args!.done!,
      members: undefined,
      joinedAt: '21:15',
      firstGame: '11월 12일',
      joinsToday: false,
    },
  },
};

export const Loading: Story = { args: { ...New.args, loading: true } };

export const Empty: Story = { args: New.args };
