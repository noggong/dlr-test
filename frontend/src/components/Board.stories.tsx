import type { Meta, StoryObj } from '@storybook/react';
import { liveRows } from '@/fixtures/board';
import { Board } from './Board';

const meta: Meta<typeof Board> = {
  title: '공용/Board',
  component: Board,
  args: {
    title: '그룹 현황',
    summary: '0/5명 완료',
    groupStatus: { tone: 'ing', label: '그룹 진행 중' },
    rows: liveRows,
    compact: true,
  },
  decorators: [(S) => <div className="max-w-[390px]">{S()}</div>],
};
export default meta;
type Story = StoryObj<typeof Board>;

export const Live: Story = {};
export const Ranking: Story = {
  name: '그룹 내 랭킹(결과 · 마감 실패)',
  args: {
    title: '그룹 내 랭킹',
    summary: undefined,
    groupStatus: undefined,
    compact: false,
    rows: [
      { rank: 1, id: 'minji', progress: 3, status: 'ok', note: '07:39:12' },
      {
        rank: 2,
        id: 'junho',
        me: true,
        progress: 2,
        status: 'bad',
        note: '07:24:47 · 1번에서 패스',
      },
      { rank: 3, id: 'seoyeon', progress: 1, status: 'bad', note: '07:12:03' },
      { rank: 4, id: 'dohyun', progress: -1, status: 'bad', note: '접속하지 않음' },
      { rank: 5, id: 'hana', progress: 1, status: 'bad', note: '실패 처리' },
    ],
  },
};
export const Loading: Story = { args: { loading: true } };
export const Empty: Story = { args: { rows: [], empty: '아직 문제를 푼 그룹원이 없어요' } };
export const NotStarted: Story = {
  name: '미접속 포함(게임 중)',
  args: {
    summary: '0/4명 완료',
    rows: [
      { rank: 1, id: 'junho', progress: 2, status: 'ing', note: '07:03:41' },
      {
        rank: 2,
        id: 'minji',
        me: true,
        progress: 2,
        status: 'ing',
        note: '07:04:02 · 1번에서 패스',
      },
      { rank: 3, id: 'dohyun', progress: -1, status: 'none', note: '접속하지 않음' },
      { rank: 4, id: 'hana', progress: 1, status: 'ing', note: '07:02:10' },
    ],
  },
};
