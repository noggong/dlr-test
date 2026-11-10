import { AppShell } from '@/components/AppShell';
import { Card, Hero, PeopleList } from '@/components/Blocks';
import { Callout } from '@/components/Callout';
import { Field } from '@/components/Field';
import { Icon } from '@/components/Icon';
import { NextGameCard } from '@/components/NextGameCard';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import { useScreenState } from '@/hooks/useScreenState';
import { groups, solo } from '@/mocks';

const states = ['default'] as const;

/** 7 · 1명 그룹 안내 — 그날 그룹원이 1명이면 미션도 결과도 없다. */
export default function SoloPage() {
  const state = useScreenState(states, 'default');
  const group = groups.solo;
  const members = state === 'empty' ? [] : solo.members;

  return (
    <AppShell layout="center" group={group} clock={{ text: solo.now }}>
      <Hero
        icon={{ name: 'people', tone: 'ing' }}
        title="그룹원이 2명 이상이어야 미션을 시작할 수 있어요"
        sub={`오늘 07:00 기준으로 「${group.name}」 그룹원은 나 혼자예요. 그래서 오늘은 미션도, 결과도 없어요.`}
        className="pt-6"
      />
      {state === 'loading' ? (
        <LoadingRegion>
          <SkeletonCard lines={2} />
          <SkeletonCard lines={2} />
        </LoadingRegion>
      ) : (
        <>
          <Card title="그룹원">
            {members.length > 0 ? (
              <PeopleList people={members} />
            ) : (
              <Callout>아직 함께하는 그룹원이 없어요</Callout>
            )}
            <p className="text-small text-ink-3">
              07:00 이후에 가입한 그룹원은 다음 날부터 함께해요. 내일은 2명이 함께 시작해요.
            </p>
          </Card>
          <Card title="친구를 그룹에 초대하세요">
            <p className="text-small text-ink-3">
              가입할 때 「기존 그룹 들어가기」에서 이 그룹명을 입력하면 돼요.
            </p>
            <Field
              label="초대할 그룹명"
              hideLabel
              readOnly
              value={group.name}
              trailing={
                <button
                  type="button"
                  aria-label="그룹명 복사"
                  onClick={() => void navigator.clipboard?.writeText(group.name)}
                  className="grid h-11 place-items-center rounded-r1 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
                >
                  <StatusChip tone="mute">
                    <Icon name="copy" size={14} />
                    복사
                  </StatusChip>
                </button>
              }
            />
          </Card>
        </>
      )}
      <NextGameCard seconds={solo.nextSeconds} />
    </AppShell>
  );
}
