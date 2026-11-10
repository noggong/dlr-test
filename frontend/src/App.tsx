import { Navigate, Route, Routes } from 'react-router-dom';
import DonePage from '@/pages/DonePage';
import LoginPage from '@/pages/LoginPage';
import QuizPage from '@/pages/QuizPage';
import ResultPage from '@/pages/ResultPage';
import SignupPage from '@/pages/SignupPage';
import SoloPage from '@/pages/SoloPage';
import WaitingPage from '@/pages/WaitingPage';

/** 지면마다 경로 1개. 상태는 쿼리스트링 state 로 고른다 — docs/spec/web-client.md §1. */
export default function App() {
  return (
    <Routes>
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/waiting" element={<WaitingPage />} />
      <Route path="/quiz" element={<QuizPage />} />
      <Route path="/done" element={<DonePage />} />
      <Route path="/result" element={<ResultPage />} />
      <Route path="/solo" element={<SoloPage />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
