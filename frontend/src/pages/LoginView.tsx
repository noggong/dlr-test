import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { Hero } from '@/components/Blocks';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Field } from '@/components/Field';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { MSG, type LoginErrors } from './authForm';

export interface LoginValues {
  loginId: string;
  password: string;
}

export interface LoginViewProps {
  values: LoginValues;
  errors?: LoginErrors;
  submitting?: boolean;
  loading?: boolean;
  onChange?: (patch: Partial<LoginValues>) => void;
  onSubmit?: () => void;
}

/** 2 · 로그인. 실패 문구는 아이디 · 비밀번호 중 무엇이 틀렸는지 말하지 않는다(W13). props 만 그린다. */
export function LoginView({
  values,
  errors = {},
  submitting,
  loading,
  onChange,
  onSubmit,
}: LoginViewProps) {
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!submitting) onSubmit?.();
  };

  return (
    <AppShell
      layout="narrow"
      footer={
        <>
          <Button type="submit" form="login-form" disabled={submitting || loading}>
            로그인
          </Button>
          <p className="text-center text-small text-ink-3">
            처음인가요?{' '}
            <Link to="/signup" className="font-bold text-accent-ink">
              가입하기
            </Link>
          </p>
        </>
      }
    >
      <Hero
        eyebrow="매일 07:00 · 그룹 퀴즈 미션"
        title="로그인"
        large
        sub="가입한 아이디와 비밀번호로 들어와요."
        className="pt-8"
      />
      {loading ? (
        <LoadingRegion>
          <SkeletonCard lines={3} />
        </LoadingRegion>
      ) : (
        <form id="login-form" className="grid gap-4" onSubmit={submit} noValidate>
          <Field
            label="아이디"
            value={values.loginId}
            onChange={(e) => onChange?.({ loginId: e.target.value })}
            error={errors.fail}
            autoComplete="username"
          />
          <Field
            label="비밀번호"
            type="password"
            value={values.password}
            onChange={(e) => onChange?.({ password: e.target.value })}
            error={errors.fail}
            autoComplete="current-password"
          />
        </form>
      )}
      {errors.fail && (
        <Callout tone="bad" role="alert">
          {MSG.loginFail}
        </Callout>
      )}
      {errors.alert && (
        <Callout tone="bad" role="alert">
          {errors.alert}
        </Callout>
      )}
    </AppShell>
  );
}
