import { AppShell } from '@/components/AppShell';
import { Card, Hero } from '@/components/Blocks';
import { Board, type BoardRowData } from '@/components/Board';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { NextGameCard } from '@/components/NextGameCard';
import { LoadingRegion, Skeleton } from '@/components/Skeleton';
import type { Remain } from '@/lib/time';

/** `ok` · `fail` 확정 결과 · `pending` 확정 대기(로딩) · `none` 결과 없음(NO_RESULT_YET) · `error` 다시 열기 */
export type ResultKind = 'ok' | 'fail' | 'pending' | 'none' | 'error';

export interface ResultViewProps {
  kind: ResultKind;
  group?: { name: string; size: string };
  clockText?: string;
  /** 머리 아래 줄 */
  sub?: string;
  /** 내 순위 — null 이면 그날 그룹원이 아니었다(07:00 뒤 가입) */
  myRank?: number | null;
  total?: number;
  ranking?: BoardRowData[];
  /** false 면 「랭킹 확정 중」(08:00 ~ 08:00:03 잠정 랭킹) */
  rankingFinal?: boolean;
  /** 다음 게임까지 */
  next?: Remain;
  nextWhen?: string;
  /** 본문 뼈대(스토리 Loading) */
  loading?: boolean;
  onRetry?: () => void;
}

const heads = {
  ok: { icon: { name: 'trophy', tone: 'ok' }, title: '그룹 미션 성공' },
  fail: { icon: { name: 'hourglass', tone: 'bad' }, title: '그룹 미션 실패' },
  pending: { icon: { name: 'hourglass', tone: 'ing' }, title: '결과 확정 중' },
  none: { icon: { name: 'info', tone: 'ing' }, title: '아직 결과가 없어요' },
  error: { icon: { name: 'alert', tone: 'bad' }, title: '결과를 불러오지 못했어요' },
} as const;

/** 6 · 결과(08:00 이후 ~ 다음 게임 전). 마감 실패에서는 3문제를 못 푼 그룹원도 「실패」(W10). */
export function ResultView({
  kind,
  group,
  clockText,
  sub,
  myRank,
  total,
  ranking = [],
  rankingFinal = true,
  next,
  nextWhen,
  loading,
  onRetry,
}: ResultViewProps) {
  // 처음 읽는 중(무슨 결과인지 아직 모름)은 「확정 중」이라 말하지 않는다
  const head =
    loading && kind === 'pending'
      ? ({ icon: { name: 'hourglass', tone: 'ing' }, title: '결과를 불러오는 중' } as const)
      : heads[kind];
  const decided = kind === 'ok' || kind === 'fail';
  const busy = loading || kind === 'pending';
  return (
    <AppShell
      layout={decided || kind === 'pending' ? 'split' : 'center'}
      group={group}
      clock={clockText ? { text: clockText } : undefined}
    >
      <Hero
        icon={head.icon}
        title={head.title}
        large
        sub={
          loading && kind === 'pending'
            ? undefined
            : kind === 'pending'
              ? '08:00에 문제를 풀던 그룹원이 있어 잠시 뒤 확정돼요.'
              : kind === 'none'
                ? '함께한 게임이 끝나면 여기에 결과가 나와요.'
                : sub
        }
      />
      {kind === 'error' && (
        <>
          <Callout tone="bad" role="alert">
            잠시 뒤 다시 열어 주세요.
          </Callout>
          {onRetry && (
            <Button variant="secondary" onClick={onRetry}>
              다시 시도
            </Button>
          )}
        </>
      )}
      {(decided || kind === 'pending') && (
        <>
          <Card
            title="내 순위"
            aside={
              busy ? (
                <LoadingRegion>
                  <Skeleton className="h-7 w-24" />
                </LoadingRegion>
              ) : myRank === null || myRank === undefined ? (
                <span className="text-small font-semibold text-ink-3">
                  그날은 참가하지 않았어요
                </span>
              ) : (
                <span className="num text-title font-extrabold">
                  {myRank}위 <span className="text-small font-normal text-ink-3">/ {total}명</span>
                </span>
              )
            }
          >
            {!busy && myRank === null && (
              <p className="text-small text-ink-3">06:50 이후에 가입해 다음 게임부터 함께해요.</p>
            )}
          </Card>
          <Board
            aside
            title="그룹 내 랭킹"
            groupStatus={
              decided && !rankingFinal ? { tone: 'ing', label: '랭킹 확정 중' } : undefined
            }
            rows={ranking}
            empty="아직 문제를 푼 그룹원이 없어요"
            loading={busy}
          />
        </>
      )}
      {next && <NextGameCard {...next} when={nextWhen} />}
    </AppShell>
  );
}
