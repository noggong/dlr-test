import type { Meta, StoryObj } from '@storybook/react';
import { groups, result } from '@/mocks';
import { ResultView } from './ResultView';

// 시안 result 1 · 2 + 공통 Loading · Empty + 연동 상태(Pending · 랭킹 확정 중 · 미참가 · 결과 없음 · 오류)
// — SPEC §1 · W29 · §6.3 · ORACLE O2 · O3.
const meta: Meta<typeof ResultView> = {
  title: 'Pages/Result',
  component: ResultView,
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj<typeof ResultView>;

const base = (key: 'ok' | 'fail') => {
  const d = result[key];
  return {
    kind: key,
    group: groups.main,
    clockText: d.now,
    sub: d.sub,
    myRank: d.myRank,
    total: d.total,
    ranking: d.ranking,
    next: { seconds: d.nextSeconds },
  };
};

export const Ok: Story = { args: base('ok') };

export const Fail: Story = { args: base('fail') };

export const Loading: Story = { args: { ...base('ok'), loading: true } };

export const Empty: Story = { args: { ...base('ok'), ranking: [] } };

/** `PENDING` — 08:00 뒤 확정 대기(api-guide §2) */
export const Pending: Story = {
  args: { ...base('ok'), kind: 'pending', clockText: '08:00', ranking: [], myRank: null },
};

/** 결과는 왔지만 `rankingFinal == false`(08:00 ~ 08:00:03) */
export const RankingPending: Story = { args: { ...base('fail'), rankingFinal: false } };

/** `myRank == null` — 그날 07:00 뒤 가입 */
export const NotParticipant: Story = {
  args: {
    ...base('ok'),
    myRank: null,
    ranking: result.ok.ranking.map((r) => ({ ...r, me: undefined })),
  },
};

/** `NO_RESULT_YET` — 결과 없이 다음 게임까지 */
export const NoResult: Story = {
  args: { kind: 'none', group: groups.main, clockText: '09:30', next: { seconds: 77400 } },
};

export const LoadError: Story = {
  args: { kind: 'error', group: groups.main, clockText: '08:12', onRetry: () => undefined },
};
