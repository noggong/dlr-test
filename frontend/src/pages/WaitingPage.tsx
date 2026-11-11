import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getLatestResult } from '@/api/endpoints';
import { ApiError } from '@/api/http';
import { hhmm, useServerNow } from '@/app/serverNow';
import { landingPath, useSession } from '@/app/session';
import { groupPeople } from './groupPeople';
import { yesterdayCard } from './resultText';
import { WaitingView, type YesterdayCard } from './WaitingView';

/**
 * 대기 지면 컨테이너 — 오늘 게임(`games/today`, 세션의 것) + 직전 결과(`results/latest`, W11).
 * 카운트다운 · 07:00 전환은 서버 시각(W30)으로 잰다.
 */
export default function WaitingPage() {
  const { me, today, clock } = useSession();
  const navigate = useNavigate();
  const now = useServerNow(clock);
  const t = today.status === 'ready' ? today.today : null;
  // undefined = 아직 묻는 중 · null = 카드 없음(noyday)
  const [yesterday, setYesterday] = useState<YesterdayCard | null | undefined>(undefined);

  useEffect(() => {
    if (!t) return;
    const c = new AbortController();
    getLatestResult(c.signal).then(
      (r) => !c.signal.aborted && setYesterday(yesterdayCard(r, t) ?? null),
      (e: unknown) => {
        if (c.signal.aborted) return;
        if (e instanceof ApiError && e.code === 'SOLO_GROUP') {
          navigate('/solo', { replace: true });
          return;
        }
        // NO_RESULT_YET(§6.3) · 그 밖의 오류 — 어제 결과 카드만 빼고 대기는 그대로
        setYesterday(null);
      },
    );
    return () => c.abort();
  }, [t, navigate]);

  // 07:00(서버 시각)이 되면 /quiz, 그룹원 1명이면 /solo — 첫 화면 규칙(LLD §3.4)을 매초 다시 본다
  const target = t ? landingPath(t, clock) : '/waiting';
  useEffect(() => {
    if (target !== '/waiting') navigate(target, { replace: true });
  }, [target, navigate]);

  const startIn = useMemo(
    () => ({ until: t ? Date.parse(t.startAt) : 0, now: () => clock.nowMs() }),
    [t, clock],
  );
  if (!t) return null;
  return (
    <WaitingView
      group={{ name: t.group.name, size: `${t.group.members.length}명` }}
      clockText={hhmm(now)}
      startIn={startIn}
      members={groupPeople(t.group, me)}
      yesterday={yesterday ?? undefined}
      loading={yesterday === undefined}
    />
  );
}
