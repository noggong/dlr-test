import type { Meta, StoryObj } from '@storybook/react';
import { groups, quiz } from '@/mocks';
import { QuizView, type QuizViewProps } from './QuizView';

// 시안 play 1 ~ 8 + 공통 Loading · Empty — SPEC §1 · ORACLE O2 · O3 · O5 · O7 ~ O9.
const meta: Meta<typeof QuizView> = {
  title: 'Pages/Quiz',
  component: QuizView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof QuizView>;

type Key = 'start' | 'wrong' | 'pass' | 'passed' | 'live' | 'last' | 'warn' | 'closed';

const base = (key: Key): QuizViewProps => {
  const d = quiz[key];
  return {
    group: groups.main,
    clock:
      d.deadlineSeconds === undefined
        ? undefined
        : { deadlineSeconds: d.deadlineSeconds, tone: 'hot' },
    board: { summary: d.board.summary, groupStatus: d.board.group, rows: d.board.rows },
    question: d.question,
    answer: d.answer,
    passUsed: d.passUsed,
    toast: d.toast,
  };
};

export const Start: Story = { args: base('start') };

export const Wrong: Story = { args: { ...base('wrong'), wrong: true } };

export const Pass: Story = { args: { ...base('pass'), passAsk: true } };

export const Passed: Story = { args: { ...base('passed'), passed: true } };

export const Live: Story = { args: base('live') };

export const Last: Story = { args: base('last') };

export const Warn: Story = { args: { ...base('warn'), warn: true } };

export const Closed: Story = { args: { ...base('closed'), closed: true } };

export const Loading: Story = {
  args: { ...base('start'), board: { ...base('start').board, loading: true }, loading: true },
};

export const Empty: Story = {
  args: { ...base('start'), board: { ...base('start').board, rows: [] } },
};
