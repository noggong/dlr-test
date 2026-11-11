import { useState } from 'react';
import { signup } from '@/api/endpoints';
import { kstParts } from '@/app/clock';
import {
  hasErrors,
  signupErrorView,
  validateSignup,
  type SignupErrors,
  type SignupValues,
} from './authForm';
import { SignupView, type SignupDoneData } from './SignupView';

const empty: SignupValues = { loginId: '', password: '', mode: 'new', groupName: '' };

/** 가입 컨테이너 — `POST /auth/signup` 을 불러 SignupView 에 넘긴다. 가입은 로그인하지 않는다(AS7). */
export default function SignupPage() {
  const [values, setValues] = useState<SignupValues>(empty);
  const [errors, setErrors] = useState<SignupErrors>({});
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState<SignupDoneData>();

  const onChange = (patch: Partial<SignupValues>) => {
    setValues((v) => ({ ...v, ...patch }));
    // 고친 칸의 오류만 걷는다. 그룹 방식을 바꾸면 그룹 칸 오류(꽉 참 · 중복 · 없음)도 걷는다.
    setErrors((e) => {
      const next = { ...e, alert: undefined };
      if ('loginId' in patch) next.loginId = undefined;
      if ('password' in patch) next.password = undefined;
      if ('groupName' in patch || 'mode' in patch) {
        next.groupName = undefined;
        next.groupFull = undefined;
      }
      return next;
    });
  };

  const onSubmit = async () => {
    const invalid = validateSignup(values);
    if (hasErrors(invalid)) {
      setErrors(invalid);
      return;
    }
    setSubmitting(true);
    setErrors({});
    try {
      const res = await signup({
        loginId: values.loginId,
        password: values.password,
        group: { mode: values.mode === 'new' ? 'NEW' : 'EXISTING', name: values.groupName },
      });
      setDone(toDone(res));
    } catch (e) {
      setErrors(signupErrorView(e, values));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <SignupView
      values={values}
      errors={errors}
      submitting={submitting}
      done={done}
      onChange={onChange}
      onSubmit={() => void onSubmit()}
    />
  );
}

/**
 * 가입 응답 → 완료 요약(SPEC W31). 가입 시각은 KST 「HH:mm」. 오늘부터인지는 서버가 정한 첫 참가 날짜
 * `firstGameDate` 와 가입 시각의 KST 날짜를 견줘 정한다 — 06:50 경계(prd D24)를 시 · 분으로 다시 재지 않는다.
 */
function toDone(res: Awaited<ReturnType<typeof signup>>): SignupDoneData {
  const base = {
    userId: res.userId,
    groupName: res.groupName,
    firstGame: monthDay(res.firstGameDate),
  };
  const ms = Date.parse(res.joinedAt);
  // 가입은 이미 됐다 — 시각을 못 읽어도 오류 화면으로 보내지 않고 받은 글자를 그대로 둔다(날짜만 안내)
  if (Number.isNaN(ms)) return { ...base, joinedAt: res.joinedAt };
  const at = kstParts(new Date(ms));
  const two = (n: number) => String(n).padStart(2, '0');
  return {
    ...base,
    joinedAt: `${two(at.hour)}:${two(at.minute)}`,
    joinsToday: res.firstGameDate <= at.date,
  };
}

/** `YYYY-MM-DD` → 「M월 D일」. 모양이 다르면 받은 글자 그대로. */
function monthDay(date: string): string {
  const m = /^\d{4}-(\d{2})-(\d{2})$/.exec(date);
  return m ? `${Number(m[1])}월 ${Number(m[2])}일` : date;
}
