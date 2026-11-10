import { AppShell } from '@/components/AppShell';
import { Card, Hero, PeopleList } from '@/components/Blocks';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Countdown } from '@/components/Countdown';
import { LoadingRegion, Skeleton, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import { useScreenState } from '@/hooks/useScreenState';
import { groups, waiting } from '@/mocks';

const states = ['yday', 'noyday'] as const;

/** 3 · 대기(00:00~07:00). 어제 미션을 했으면 어제 결과 카드, 안 했으면 대기만. */
export default function WaitingPage() {
  const state = useScreenState(states, 'yday');
  const d = waiting[state === 'loading' || state === 'empty' ? 'yday' : state];
  const loading = state === 'loading';
  const members = state === 'empty' ? [] : d.members;

  return (
    <AppShell
      layout="center"
      group={groups.main}
      clock={{ text: d.now }}
      footer={<Button disabled>07:00에 열려요</Button>}
    >
      <Hero eyebrow="오늘의 그룹 미션" title={d.headline} large className="pt-6">
        {loading ? (
          <Skeleton className="h-14 w-40" />
        ) : (
          <Countdown seconds={d.seconds} size="hero" label="게임 시작까지" />
        )}
        <p className="num text-ink-2">07:00 시작 · 08:00 마감 · 한 사람당 3문제</p>
      </Hero>
      {loading ? (
        <LoadingRegion>
          <SkeletonCard lines={2} />
        </LoadingRegion>
      ) : (
        <Card title={`지금 그룹원 ${members.length}명`}>
          {members.length > 0 ? (
            <PeopleList people={members} />
          ) : (
            <Callout>아직 함께하는 그룹원이 없어요</Callout>
          )}
          <p className="text-small text-ink-3">07:00 전에 가입한 사람까지 오늘 함께해요.</p>
        </Card>
      )}
      {!loading && d.yesterday && (
        <Card
          title="어제 결과"
          aside={<StatusChip tone={d.yesterday.tone}>{d.yesterday.label}</StatusChip>}
        >
          <p className="num text-small text-ink-3">{d.yesterday.detail}</p>
        </Card>
      )}
      <Callout>
        07:00이 되면 1번 문제가 자동으로 열려요. 켜 두고 기다려도, 그때 다시 들어와도 같아요.
      </Callout>
    </AppShell>
  );
}
