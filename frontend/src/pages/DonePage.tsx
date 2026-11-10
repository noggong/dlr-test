import { AppShell } from '@/components/AppShell';
import { Card, Hero, KeyValue } from '@/components/Blocks';
import { Board } from '@/components/Board';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Modal } from '@/components/Overlay';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import { useScreenState } from '@/hooks/useScreenState';
import { done, groups } from '@/mocks';

const states = ['ok', 'selffail', 'groupok', 'groupfail'] as const;

/**
 * 5 · 완료 — 본인이 끝났거나(3문제 · 부정 실패) 그룹 결과가 확정된 순간.
 * 08:00 전 현황판의 부정 실패자는 「진행 중 · 그 시점 진행도」로 보이고 순위는 맨 아래다(목 데이터 hana).
 */
export default function DonePage() {
  const state = useScreenState(states, 'ok');
  const key = state === 'loading' || state === 'empty' ? 'ok' : state;
  const d = done[key];
  const selfFail = key === 'selffail';

  return (
    <AppShell
      layout="split"
      group={groups.main}
      clock={{ deadlineSeconds: d.deadlineSeconds, tone: 'hot' }}
      overlay={
        d.result && (
          <Modal
            title={d.result.tone === 'ok' ? '그룹 미션 성공!' : '그룹 미션 실패'}
            icon={
              d.result.tone === 'ok'
                ? { name: 'trophy', tone: 'ok' }
                : { name: 'octagon', tone: 'bad' }
            }
          >
            <p className="num text-ink-2">{d.result.sub}</p>
            <Board inset compact title="그룹 내 랭킹" rows={d.result.ranking} />
            <Button to={d.result.tone === 'ok' ? '/result?state=ok' : '/result?state=fail'}>
              결과 보기
            </Button>
          </Modal>
        )
      }
    >
      {selfFail ? (
        <Hero
          icon={{ name: 'octagon', tone: 'bad' }}
          title="오늘 미션은 실패 처리됐어요"
          sub="저장된 풀이 상태가 지워져 이어서 풀 수 없어요. 그룹의 진행은 계속 볼 수 있어요."
        />
      ) : (
        <Hero
          icon={{ name: 'check', tone: 'ok' }}
          title="3문제를 다 풀었어요"
          sub="그룹원 모두가 끝내면 그룹 미션 성공이에요. 남은 그룹원을 응원해 주세요."
        />
      )}
      <Board
        aside
        compact
        title="그룹 현황"
        summary={d.board.summary}
        groupStatus={d.board.group}
        rows={state === 'empty' ? [] : d.board.rows}
        empty="아직 문제를 푼 그룹원이 없어요"
        loading={state === 'loading'}
      />
      {state === 'loading' ? (
        <LoadingRegion>
          <SkeletonCard lines={1} />
        </LoadingRegion>
      ) : selfFail ? (
        <Card>
          <KeyValue
            items={[
              ['내 상태', <StatusChip key="me" tone="bad" />],
              [
                '그룹 상태',
                <StatusChip key="group" tone={d.board.group.tone}>
                  {d.board.group.label}
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
