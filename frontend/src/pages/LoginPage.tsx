import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { login } from '@/api/endpoints';
import { useSession } from '@/app/session';
import { loginErrorView, type LoginErrors } from './authForm';
import { LoginView, type LoginValues } from './LoginView';

/** 로그인 컨테이너 — `POST /auth/login` 200 이면 「나」(gm:me)를 두고 `/` 가 첫 화면을 고른다. */
export default function LoginPage() {
  const { me, signIn } = useSession();
  const navigate = useNavigate();
  const [values, setValues] = useState<LoginValues>({ loginId: '', password: '' });
  const [errors, setErrors] = useState<LoginErrors>({});
  const [submitting, setSubmitting] = useState(false);

  const onChange = (patch: Partial<LoginValues>) => {
    setValues((v) => ({ ...v, ...patch }));
    setErrors({});
  };

  const onSubmit = async () => {
    setSubmitting(true);
    setErrors({});
    try {
      const res = await login({ loginId: values.loginId, password: values.password });
      signIn(res.userId);
      navigate('/', { replace: true });
    } catch (e) {
      setErrors(loginErrorView(e));
      setSubmitting(false);
    }
  };

  // 이미 로그인돼 있으면 첫 화면으로 — 같은 계정으로 다시 로그인하면 ALREADY_LOGGED_IN 에 막힌다
  if (me) return <Navigate to="/" replace />;

  return (
    <LoginView
      values={values}
      errors={errors}
      submitting={submitting}
      onChange={onChange}
      onSubmit={() => void onSubmit()}
    />
  );
}
