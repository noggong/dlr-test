import type { Meta, StoryObj } from '@storybook/react';
import { liveRows } from '@/fixtures/board';
import { AppShell } from './AppShell';
import { Board } from './Board';
import { Button } from './Button';
import { Card } from './Blocks';
import { QuestionCard } from './QuestionCard';

const meta: Meta<typeof AppShell> = {
  title: '공용/AppShell · TopBar',
  component: AppShell,
  parameters: { layout: 'fullscreen' },
};
export default meta;

const group = { name: '아침7시클럽', size: '5명' };

export const Narrow: StoryObj = {
  render: () => (
    <AppShell layout="narrow" footer={<Button>가입하기</Button>}>
      <Card title="narrow — 가입 · 로그인 456" />
    </AppShell>
  ),
};

export const Center: StoryObj = {
  render: () => (
    <AppShell
      layout="center"
      group={group}
      clock={{ text: '06:55' }}
      footer={<Button disabled>07:00에 열려요</Button>}
    >
      <Card title="center — 대기 · 1명 안내 640" />
    </AppShell>
  ),
};

export const Split: StoryObj = {
  render: () => (
    <AppShell layout="split" group={group} clock={{ deadlineSeconds: 2480, tone: 'hot' }}>
      <Board
        aside
        compact
        title="그룹 현황"
        summary="0/5명 완료"
        groupStatus={{ tone: 'ing', label: '그룹 진행 중' }}
        rows={liveRows}
      />
      <QuestionCard question={{ no: 2, level: 2, text: '예시 문제 본문' }} passUsed />
    </AppShell>
  ),
};
