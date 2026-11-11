import { AppShell, type ClockProps } from '@/components/AppShell';
import { Card, Hero, KeyValue } from '@/components/Blocks';
import { Board, type BoardRowData } from '@/components/Board';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Modal } from '@/components/Overlay';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import { Toast } from '@/components/Toast';
import type { Tone } from '@/lib/types';

/** `ok` 3문제 완료 · `selffail` 본인 부정 실패 · `ended` 끝나지 않았는데 그룹 결과가 먼저 확정됨 */
export type DoneSelf = 'ok' | 'selffail' | 'ended';

export interface DoneViewProps {
  group?: { name: string; size: string };
  clock?: ClockProps;
  self: DoneSelf;
  board: {
    summary?: string;
    groupStatus: { tone: Tone; label: string };
    rows: BoardRowData[];
    loading?: boolean;
  };
  /** 그룹 결과 확정 대화상자(W29) */
  result?: { tone: 'ok' | 'bad'; sub: string; ranking: BoardRowData[] };
  /** 결과 보기 — 기본은 `/result` 링크 */
  onViewResult?: () => void;
  toast?: { id: string; progress: number };
  /** 본문 뼈대 */
  loading?: boolean;
}

const heroes = {
  ok: {
    icon: { name: 'check', tone: 'ok' },
    title: '3문제를 다 풀었어요',
    sub: '그룹원 모두가 끝내면 그룹 미션 성공이에요. 남은 그룹원을 응원해 주세요.',
  },
  selffail: {
    icon: { name: 'octagon', tone: 'bad' },
    title: '오늘 미션은 실패 처리됐어요',
    sub: '저장된 풀이 상태가 지워져 이어서 풀 수 없어요. 그룹의 진행은 계속 볼 수 있어요.',
  },
  ended: {
    icon: { name: 'hourglass', tone: 'ing' },
    title: '오늘 그룹 미션이 끝났어요',
    sub: '그룹 결과가 확정돼 더 풀 수 없어요.',
  },
} as const;

/**
 * 5 · 완료 — 본인이 끝났거나(3문제 · 부정 실패) 그룹 결과가 확정된 순간. props 만 그린다.
 * 08:00 전 현황판의 부정 실패자는 「진행 중 · 그 시점 진행도」로 보이고 순위는 맨 아래다(W9 — 서버가 정한다).
 */
export function DoneView({
  group,
  clock,
  self,
  board,
  result,
  onViewResult,
  toast,
  loading,
}: DoneViewProps) {
  const hero = heroes[self];
  return (
    <AppShell
      layout="split"
      group={group}
      clock={clock}
      overlay={
        <>
          {result && (
            <Modal
              title={result.tone === 'ok' ? '그룹 미션 성공!' : '그룹 미션 실패'}
              icon={
                result.tone === 'ok'
                  ? { name: 'trophy', tone: 'ok' }
                  : { name: 'octagon', tone: 'bad' }
              }
            >
              <p className="num text-ink-2">{result.sub}</p>
              <Board inset compact title="그룹 내 랭킹" rows={result.ranking} />
              {onViewResult ? (
                <Button onClick={onViewResult}>결과 보기</Button>
              ) : (
                <Button to="/result">결과 보기</Button>
              )}
            </Modal>
          )}
          {toast && !result && (
            <Toast>
              <b>{toast.id}</b> 님이 {toast.progress}번까지 풀었어요
            </Toast>
          )}
        </>
      }
    >
      {loading ? (
        <LoadingRegion>
          <SkeletonCard lines={2} />
        </LoadingRegion>
      ) : (
        <Hero icon={hero.icon} title={hero.title} sub={hero.sub} />
      )}
      <Board
        aside
        compact
        title="그룹 현황"
        summary={board.summary}
        groupStatus={board.groupStatus}
        rows={board.rows}
        empty="아직 문제를 푼 그룹원이 없어요"
        loading={board.loading || loading}
      />
      {loading ? (
        <LoadingRegion>
          <SkeletonCard lines={1} />
        </LoadingRegion>
      ) : self === 'selffail' ? (
        <Card>
          <KeyValue
            items={[
              ['내 상태', <StatusChip key="me" tone="bad" />],
              [
                '그룹 상태',
                <StatusChip key="group" tone={board.groupStatus.tone}>
                  {board.groupStatus.label}
                </StatusChip>,
              ],
            ]}
          />
        </Card>
      ) : (
        <Callout>
          그룹원 진행도는 실시간으로 바뀌어요. 그룹 결과가 확정되면 바로 결과 화면으로 넘어가요.
        </Callout>
      )}
    </AppShell>
  );
}
