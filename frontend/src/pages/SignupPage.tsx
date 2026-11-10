import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { Card, Hero, KeyValue } from '@/components/Blocks';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Field } from '@/components/Field';
import { Icon } from '@/components/Icon';
import { SegmentedControl } from '@/components/SegmentedControl';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import { useScreenState } from '@/hooks/useScreenState';
import { signup } from '@/mocks';

const states = ['new', 'full', 'dup', 'ok'] as const;
type Mode = 'new' | 'join';

/** 1 · 가입 — 새 그룹(그룹명 지정) 또는 기존 그룹(그룹명 입력). */
export default function SignupPage() {
  const state = useScreenState(states, 'new');
  if (state === 'ok') return <SignupDone />;
  if (state === 'loading') {
    return (
      <AppShell layout="narrow">
        <LoadingRegion>
          <SkeletonCard lines={6} />
        </LoadingRegion>
      </AppShell>
    );
  }
  return <SignupForm key={state} state={state === 'empty' ? 'new' : state} />;
}

function SignupForm({ state }: { state: 'new' | 'full' | 'dup' }) {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>(state === 'full' ? 'join' : 'new');
  const showError = (state === 'full' && mode === 'join') || (state === 'dup' && mode === 'new');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    navigate('/signup?state=ok');
  };

  return (
    <AppShell
      layout="narrow"
      footer={
        <>
          <Button type="submit" form="signup-form">
            가입하기
          </Button>
          <p className="text-center text-small text-ink-3">
            이미 계정이 있나요?{' '}
            <Link to="/login" className="font-bold text-accent-ink">
              로그인
            </Link>
          </p>
        </>
      }
    >
      <Hero
        eyebrow="매일 07:00 · 그룹 퀴즈 미션"
        title="가입하기"
        large
        sub="아이디를 만들고 함께할 그룹을 고르세요."
      />
      <form id="signup-form" className="grid gap-4" onSubmit={submit} noValidate>
        <Field label="아이디" defaultValue={signup.id} autoComplete="username" />
        <Field
          label="비밀번호"
          type="password"
          defaultValue={signup.password}
          hint="8자 이상"
          autoComplete="new-password"
        />
        <SegmentedControl
          label="그룹 고르기"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'new', label: '새 그룹 만들기' },
            { value: 'join', label: '기존 그룹 들어가기' },
          ]}
        />
        {mode === 'new' ? (
          <Field
            key="new"
            label="새 그룹명"
            defaultValue={signup.newGroup}
            hint="다른 그룹과 겹치지 않는 이름 · 2~20자"
            error={
              showError &&
              '이미 있는 그룹명이에요. 그 그룹에 들어가려면 「기존 그룹 들어가기」를 고르세요.'
            }
          />
        ) : (
          <Field
            key="join"
            label="들어갈 그룹명"
            defaultValue={state === 'full' ? signup.fullGroup : ''}
            placeholder="그룹명을 입력하세요"
            trailing={showError && <StatusChip tone="bad">10/10</StatusChip>}
            error={
              showError &&
              `「${signup.fullGroup}」 그룹은 10명이 모두 찼어요. 다른 그룹명을 입력하거나 새 그룹을 만드세요.`
            }
          />
        )}
      </form>
      <Callout>
        07:00 이후에 가입하면 오늘 게임은 쉬고 <b>내일 07:00부터</b> 참가해요.
      </Callout>
    </AppShell>
  );
}

function SignupDone() {
  const d = signup.done;
  return (
    <AppShell layout="narrow" footer={<Button to="/login">로그인하러 가기</Button>}>
      <Hero
        icon={{ name: 'check', tone: 'ok' }}
        title="가입했어요"
        large
        sub="이제 로그인하고 그룹원과 함께 미션을 기다려요."
        className="pt-10"
      />
      <Card>
        <KeyValue
          items={[
            ['아이디', d.id],
            ['그룹', d.group],
            ['그룹 인원', d.members],
            ['가입 시각', d.joinedAt],
          ]}
        />
        <hr className="border-line" />
        <p className="flex items-start gap-1.5 text-small text-ok">
          <Icon name="check" className="mt-0.5 flex-none" />
          <span>
            07:00 전에 가입해 <b>오늘 게임부터</b> 참가해요.
          </span>
        </p>
      </Card>
      <Callout>
        07:00 이후 가입이면 이 자리에 「오늘 게임은 쉬고 내일 07:00부터 참가해요」가 보여요.
      </Callout>
    </AppShell>
  );
}
