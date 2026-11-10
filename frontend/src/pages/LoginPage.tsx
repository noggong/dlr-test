import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { Hero } from '@/components/Blocks';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Field } from '@/components/Field';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { useScreenState } from '@/hooks/useScreenState';
import { signup } from '@/mocks';

const states = ['default', 'fail'] as const;

/** 2 · 로그인. 실패 문구는 아이디 · 비밀번호 중 무엇이 틀렸는지 말하지 않는다. */
export default function LoginPage() {
  const state = useScreenState(states, 'default');
  const navigate = useNavigate();
  const fail = state === 'fail';

  const submit = (e: FormEvent) => {
    e.preventDefault();
    navigate('/waiting');
  };

  return (
    <AppShell
      layout="narrow"
      footer={
        <>
          <Button type="submit" form="login-form">
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
      {state === 'loading' ? (
        <LoadingRegion>
          <SkeletonCard lines={3} />
        </LoadingRegion>
      ) : (
        <form id="login-form" className="grid gap-4" onSubmit={submit} noValidate>
          <Field label="아이디" defaultValue={signup.id} error={fail} autoComplete="username" />
          <Field
            label="비밀번호"
            type="password"
            defaultValue={signup.password}
            error={fail}
            autoComplete="current-password"
          />
        </form>
      )}
      {fail && (
        <Callout tone="bad" role="alert">
          아이디 또는 비밀번호가 맞지 않아요. 다시 확인해 주세요.
        </Callout>
      )}
    </AppShell>
  );
}
