import type { Meta, StoryObj } from '@storybook/react';
import { Field } from './Field';
import { StatusChip } from './StatusChip';

const meta: Meta<typeof Field> = {
  title: '공용/Field',
  component: Field,
  args: { label: '아이디', placeholder: '아이디' },
  decorators: [(S) => <div className="max-w-[390px]">{S()}</div>],
};
export default meta;
type Story = StoryObj<typeof Field>;

export const Default: Story = {};
export const WithHint: Story = {
  args: { label: '비밀번호', type: 'password', hint: '8자 이상', defaultValue: 'password' },
};
export const Error: Story = {
  args: {
    label: '새 그룹명',
    defaultValue: '아침7시클럽',
    error: '이미 있는 그룹명이에요. 그 그룹에 들어가려면 「기존 그룹 들어가기」를 고르세요.',
  },
};
export const ErrorWithTrailing: Story = {
  args: {
    label: '들어갈 그룹명',
    defaultValue: '새벽러닝',
    trailing: <StatusChip tone="bad">10/10</StatusChip>,
    error: '「새벽러닝」 그룹은 10명이 모두 찼어요. 다른 그룹명을 입력하거나 새 그룹을 만드세요.',
  },
};
export const Disabled: Story = {
  args: { label: '답', placeholder: '마감됐어요', disabled: true },
};
