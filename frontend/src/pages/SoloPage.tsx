import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { hhmm, nextGameStartMs, useServerNow } from '@/app/serverNow';
import { useSession } from '@/app/session';
import { SoloView } from './SoloView';

/**
 * 1명 안내 컨테이너 — 그날 그룹원 1명(`games/today`) 또는 어느 API 든 `SOLO_GROUP`(§6.3)으로 온다.
 * 다음 게임(서버 시각)이 되면 `games/today` 를 다시 받고 첫 화면 규칙으로 간다.
 */
export default function SoloPage() {
  const { me, today, clock, loadSession } = useSession();
  const navigate = useNavigate();
  const now = useServerNow(clock);
  const t = today.status === 'ready' ? today.today : null;
  // 다음 게임은 지면을 열 때 한 번 정한다 — 그 시각을 넘기면 다음 날 정보로 다시 시작한다
  const [nextMs] = useState(() => (t ? nextGameStartMs(t, clock.nowMs()) : 0));
  const leaving = useRef(false);

  useEffect(() => {
    if (!t || leaving.current || now < nextMs) return;
    leaving.current = true;
    void loadSession({ quiet: true }).then(() => navigate('/', { replace: true }));
  }, [t, now, nextMs, loadSession, navigate]);

  const next = useMemo(() => ({ until: nextMs, now: () => clock.nowMs() }), [nextMs, clock]);
  if (!t) return null;
  const beforeStart = nextMs === Date.parse(t.startAt);
  return (
    <SoloView
      group={{ name: t.group.name, size: `오늘 ${t.group.members.length}명` }}
      clockText={hhmm(now)}
      members={t.group.members.map((m) => ({ id: m.userId, me: m.userId === me || undefined }))}
      next={next}
      nextWhen={beforeStart ? '오늘 07:00 시작' : '내일 07:00 시작'}
      beforeStart={beforeStart}
    />
  );
}
