import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login } from '@/api/endpoints';
import { useSession } from '@/app/session';
import { loginErrorView, type LoginErrors } from './authForm';
import { LoginView, type LoginValues } from './LoginView';

/** 로그인 컨테이너 — `POST /auth/login` 200 이면 「나」(gm:me)를 두고 `/` 가 첫 화면을 고른다. */
export default function LoginPage() {
  const { signIn } = useSession();
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
