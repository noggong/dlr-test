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
      const at = kstParts(new Date(res.joinedAt));
      setDone({
        userId: res.userId,
        groupName: res.groupName,
        joinedAt: `${String(at.hour).padStart(2, '0')}:${String(at.minute).padStart(2, '0')}`,
        joinsToday: at.hour < 7,
      });
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
