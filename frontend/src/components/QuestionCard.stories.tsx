import type { Meta, StoryObj } from '@storybook/react';
import { QuestionCard } from './QuestionCard';

const meta: Meta<typeof QuestionCard> = {
  title: '공용/QuestionCard',
  component: QuestionCard,
  args: { question: { no: 1, level: 1, text: '대한민국 국회의원의 임기는 몇 년일까요?' } },
  decorators: [(S) => <div className="max-w-[390px]">{S()}</div>],
};
export default meta;
type Story = StoryObj<typeof QuestionCard>;

export const Start: Story = {};
export const Wrong: Story = {
  args: { defaultAnswer: '5년', error: '틀렸어요. 같은 문제를 다시 풀어 보세요.' },
};
export const PassUsed: Story = {
  args: {
    question: {
      no: 2,
      level: 2,
      text: '1919년 3월 1일, 민족대표들이 독립선언서를 낭독한 서울 인사동의 음식점 이름은?',
    },
    passUsed: true,
  },
};
export const Closed: Story = {
  name: '08:00 마감(모두 잠김)',
  args: {
    question: { no: 3, level: 3, text: '헌법 개정안이 국민투표로 확정되려면 …' },
    passUsed: true,
    locked: true,
  },
};
