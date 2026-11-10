import { AppShell } from '@/components/AppShell';
import { Card, Hero } from '@/components/Blocks';
import { Board } from '@/components/Board';
import { NextGameCard } from '@/components/NextGameCard';
import { LoadingRegion, Skeleton } from '@/components/Skeleton';
import { useScreenState } from '@/hooks/useScreenState';
import { groups, result } from '@/mocks';

const states = ['ok', 'fail'] as const;

/** 6 · 결과(08:00 이후 ~ 다음 게임 전). 마감 실패에서는 3문제를 못 푼 그룹원도 「실패」. */
export default function ResultPage() {
  const state = useScreenState(states, 'ok');
  const key = state === 'loading' || state === 'empty' ? 'ok' : state;
  const d = result[key];
  const ok = key === 'ok';
  const loading = state === 'loading';

  return (
    <AppShell layout="split" group={groups.main} clock={{ text: d.now }}>
      <Hero
        icon={ok ? { name: 'trophy', tone: 'ok' } : { name: 'hourglass', tone: 'bad' }}
        title={ok ? '그룹 미션 성공' : '그룹 미션 실패'}
        large
        sub={d.sub}
      />
      <Card
        title="내 순위"
        aside={
          loading ? (
            <LoadingRegion>
              <Skeleton className="h-7 w-24" />
            </LoadingRegion>
          ) : (
            <span className="num text-title font-extrabold">
              {d.myRank}위 <span className="text-small font-normal text-ink-3">/ {d.total}명</span>
            </span>
          )
        }
      />
      <Board
        aside
        title="그룹 내 랭킹"
        rows={state === 'empty' ? [] : d.ranking}
        empty="아직 문제를 푼 그룹원이 없어요"
        loading={loading}
      />
      <NextGameCard seconds={d.nextSeconds} />
    </AppShell>
  );
}
