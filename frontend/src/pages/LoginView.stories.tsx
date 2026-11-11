import type { Meta, StoryObj } from '@storybook/react';
import { signup } from '@/mocks';
import { MSG } from './authForm';
import { LoginView } from './LoginView';

// 시안 login 1 · 2 + 공통 Loading · Empty(목록 없는 지면이라 기본과 같다) — SPEC §1 · ORACLE O2 · O3.
const meta: Meta<typeof LoginView> = {
  title: 'Pages/Login',
  component: LoginView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof LoginView>;

const values = { loginId: signup.id, password: signup.password };

export const Default: Story = { args: { values } };

export const Fail: Story = { args: { values, errors: { fail: true } } };

/** SPEC §6.3 ALREADY_LOGGED_IN */
export const AlreadyLoggedIn: Story = {
  args: { values, errors: { alert: MSG.alreadyLoggedIn } },
};

export const Loading: Story = { args: { values, loading: true } };

export const Empty: Story = { args: { values } };
