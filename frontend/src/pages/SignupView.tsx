import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { Card, Hero, KeyValue } from '@/components/Blocks';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Field } from '@/components/Field';
import { Icon } from '@/components/Icon';
import { SegmentedControl } from '@/components/SegmentedControl';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { StatusChip } from '@/components/StatusChip';
import type { SignupErrors, SignupMode, SignupValues } from './authForm';

/** 가입 완료 요약 — 가입 응답(`userId` · `groupName` · `joinedAt`)에서 만든다. */
export interface SignupDoneData {
  userId: string;
  groupName: string;
  /** 가입 시각 KST 「HH:mm」 */
  joinedAt: string;
  /** 가입 응답에는 인원이 없다 — 있을 때만(스토리) */
  members?: string;
  /** 07:00 전 가입 = 그날 게임부터 참가(R-G4) */
  joinsToday: boolean;
}

export interface SignupViewProps {
  values: SignupValues;
  errors?: SignupErrors;
  submitting?: boolean;
  loading?: boolean;
  done?: SignupDoneData;
  onChange?: (patch: Partial<SignupValues>) => void;
  onSubmit?: () => void;
}

/** 1 · 가입 — 새 그룹(그룹명 지정) 또는 기존 그룹(그룹명 입력). props 만 그린다. */
export function SignupView(props: SignupViewProps) {
  if (props.done) return <SignupDone done={props.done} />;
  if (props.loading) {
    return (
      <AppShell layout="narrow">
        <LoadingRegion>
          <SkeletonCard lines={6} />
        </LoadingRegion>
      </AppShell>
    );
  }
  return <SignupForm {...props} />;
}

function SignupForm({ values, errors = {}, submitting, onChange, onSubmit }: SignupViewProps) {
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!submitting) onSubmit?.();
  };

  return (
    <AppShell
      layout="narrow"
      footer={
        <>
          <Button type="submit" form="signup-form" disabled={submitting}>
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
        <Field
          label="아이디"
          value={values.loginId}
          onChange={(e) => onChange?.({ loginId: e.target.value })}
          error={errors.loginId}
          autoComplete="username"
        />
        <Field
          label="비밀번호"
          type="password"
          value={values.password}
          onChange={(e) => onChange?.({ password: e.target.value })}
          hint="8자 이상"
          error={errors.password}
          autoComplete="new-password"
        />
        <SegmentedControl<SignupMode>
          label="그룹 고르기"
          value={values.mode}
          onChange={(mode) => onChange?.({ mode })}
          options={[
            { value: 'new', label: '새 그룹 만들기' },
            { value: 'join', label: '기존 그룹 들어가기' },
          ]}
        />
        {values.mode === 'new' ? (
          <Field
            key="new"
            label="새 그룹명"
            value={values.groupName}
            onChange={(e) => onChange?.({ groupName: e.target.value })}
            hint="다른 그룹과 겹치지 않는 이름 · 2~20자"
            error={errors.groupName}
          />
        ) : (
          <Field
            key="join"
            label="들어갈 그룹명"
            value={values.groupName}
            onChange={(e) => onChange?.({ groupName: e.target.value })}
            placeholder="그룹명을 입력하세요"
            trailing={errors.groupFull && <StatusChip tone="bad">10/10</StatusChip>}
            error={errors.groupName}
          />
        )}
      </form>
      {errors.alert && (
        <Callout tone="bad" role="alert">
          {errors.alert}
        </Callout>
      )}
      <Callout>
        07:00 이후에 가입하면 오늘 게임은 쉬고 <b>내일 07:00부터</b> 참가해요.
      </Callout>
    </AppShell>
  );
}

function SignupDone({ done }: { done: SignupDoneData }) {
  const items: Array<[string, string]> = [
    ['아이디', done.userId],
    ['그룹', done.groupName],
  ];
  if (done.members) items.push(['그룹 인원', done.members]);
  items.push(['가입 시각', done.joinedAt]);
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
        <KeyValue items={items} />
        <hr className="border-line" />
        {done.joinsToday ? (
          <p className="flex items-start gap-1.5 text-small text-ok">
            <Icon name="check" className="mt-0.5 flex-none" />
            <span>
              07:00 전에 가입해 <b>오늘 게임부터</b> 참가해요.
            </span>
          </p>
        ) : (
          <p className="flex items-start gap-1.5 text-small text-ink-2">
            <Icon name="info" className="mt-0.5 flex-none" />
            <span>
              오늘 게임은 쉬고 <b>내일 07:00부터</b> 참가해요.
            </span>
          </p>
        )}
      </Card>
    </AppShell>
  );
}
