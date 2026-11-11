import { AppShell } from '@/components/AppShell';
import { Card, Hero, PeopleList, type Person } from '@/components/Blocks';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Countdown } from '@/components/Countdown';
import { LoadingRegion, Skeleton, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import { remainOf, remainWords, type Remain } from '@/lib/time';

export interface YesterdayCard {
  /** 「어제 결과」 · 「11월 10일 결과」 */
  title: string;
  tone: 'ok' | 'bad';
  label: string;
  detail: string;
}

export interface WaitingViewProps {
  group?: { name: string; size: string };
  /** 상단 바 시각(KST HH:MM) */
  clockText?: string;
  /** 게임 시작(07:00)까지 */
  startIn: Remain;
  members: Person[];
  /** 직전 게임 결과(W11) — 없으면 카드 없음(`noyday`) */
  yesterday?: YesterdayCard;
  loading?: boolean;
}

/** 3 · 대기(00:00~07:00). 어제 미션을 했으면 어제 결과 카드, 안 했으면 대기만(SPEC W11). */
export function WaitingView({
  group,
  clockText,
  startIn,
  members,
  yesterday,
  loading,
}: WaitingViewProps) {
  return (
    <AppShell
      layout="center"
      group={group}
      clock={clockText ? { text: clockText } : undefined}
      footer={<Button disabled>07:00에 열려요</Button>}
    >
      <Hero
        eyebrow="오늘의 그룹 미션"
        title={`${remainWords(remainOf(startIn))} 후 게임 시작`}
        large
        className="pt-6"
      >
        {loading ? (
          <Skeleton className="h-14 w-40" />
        ) : (
          <Countdown {...startIn} size="hero" label="게임 시작까지" />
        )}
        <p className="num text-ink-2">07:00 시작 · 08:00 마감 · 한 사람당 3문제</p>
      </Hero>
      {loading ? (
        <LoadingRegion>
          <SkeletonCard lines={2} />
        </LoadingRegion>
      ) : (
        <Card title={`지금 그룹원 ${members.filter((m) => !m.later).length}명`}>
          {members.length > 0 ? (
            <PeopleList people={members} />
          ) : (
            <Callout>아직 함께하는 그룹원이 없어요</Callout>
          )}
          <p className="text-small text-ink-3">06:50 전에 가입한 사람까지 오늘 함께해요.</p>
        </Card>
      )}
      {!loading && yesterday && (
        <Card
          title={yesterday.title}
          aside={<StatusChip tone={yesterday.tone}>{yesterday.label}</StatusChip>}
        >
          <p className="num text-small text-ink-3">{yesterday.detail}</p>
        </Card>
      )}
      <Callout>
        07:00이 되면 1번 문제가 자동으로 열려요. 켜 두고 기다려도, 그때 다시 들어와도 같아요.
      </Callout>
    </AppShell>
  );
}
