import type { Meta, StoryObj } from '@storybook/react';
import { Button } from './Button';

const meta: Meta<typeof Button> = {
  title: '공용/Button',
  component: Button,
  args: { children: '제출' },
};
export default meta;
type Story = StoryObj<typeof Button>;

export const Primary: Story = { args: { variant: 'primary', children: '가입하기' } };
export const Secondary: Story = { args: { variant: 'secondary', children: '그대로 풀기' } };
export const SmallWithIcon: Story = {
  args: { variant: 'secondary', size: 'sm', icon: 'pass', children: '패스 1회 남음' },
};
export const Locked: Story = {
  name: '잠김(패스 사용함 · 08:00 마감)',
  args: { variant: 'secondary', size: 'sm', icon: 'pass', disabled: true, children: '패스 사용함' },
};
export const AsLink: Story = { args: { to: '/login', children: '로그인하러 가기' } };
