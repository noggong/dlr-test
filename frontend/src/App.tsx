import { Navigate, Route, Routes } from 'react-router-dom';
import type { ServerClock } from '@/app/clock';
import { Landing, RequireAuth, SessionProvider } from '@/app/session';
import DonePage from '@/pages/DonePage';
import LoginPage from '@/pages/LoginPage';
import QuizPage from '@/pages/QuizPage';
import ResultPage from '@/pages/ResultPage';
import SignupPage from '@/pages/SignupPage';
import SoloPage from '@/pages/SoloPage';
import WaitingPage from '@/pages/WaitingPage';

/**
 * 지면마다 경로 1개 — docs/spec/web-client.md §1. `/` 는 로그인돼 있으면 첫 화면(서버 시각 기준),
 * 아니면 /login. 대기 · 문제 풀이 · 완료 · 결과 · 1명 안내는 로그인이 있어야 연다.
 */
export default function App({ clock }: { clock?: ServerClock }) {
  return (
    <SessionProvider clock={clock}>
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/waiting" element={guard(<WaitingPage />)} />
        <Route path="/quiz" element={guard(<QuizPage />)} />
        <Route path="/done" element={guard(<DonePage />)} />
        <Route path="/result" element={guard(<ResultPage />)} />
        <Route path="/solo" element={guard(<SoloPage />)} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </SessionProvider>
  );
}

const guard = (page: JSX.Element) => <RequireAuth>{page}</RequireAuth>;
