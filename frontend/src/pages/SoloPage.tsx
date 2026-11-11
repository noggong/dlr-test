import { useMemo, useState } from 'react';
import { kstParts } from '@/app/clock';
import { dayWord, hhmm, nextGameStartMs, useNextGame, useServerNow } from '@/app/serverNow';
import { useSession } from '@/app/session';
import { groupPeople } from './groupPeople';
import { SoloView } from './SoloView';

/**
 * 1명 안내 컨테이너 — 그날 그룹원 1명(`games/today`) 또는 어느 API 든 `SOLO_GROUP`(§6.3)으로 온다.
 * 다음 게임(서버 시각)이 되면 `games/today` 를 다시 받고 첫 화면 규칙으로 간다.
 */
export default function SoloPage() {
  const { me, today, clock } = useSession();
  const now = useServerNow(clock);
  const t = today.status === 'ready' ? today.today : null;
  // 다음 게임은 지면을 열 때 한 번 정한다 — 그 시각을 넘기면 다음 날 정보로 다시 시작한다
  const [nextMs] = useState(() => (t ? nextGameStartMs(t, clock.nowMs()) : 0));
  useNextGame(nextMs, now, t !== null);

  const next = useMemo(() => ({ until: nextMs, now: () => clock.nowMs() }), [nextMs, clock]);
  if (!t) return null;
  // 그 게임 날짜의 07:00 전(00:00~07:00) — 08:00 뒤의 today 는 다음 날 것이라(GS1) 날짜까지 본다
  const beforeStart = kstParts(clock.now()).date === t.date && now < Date.parse(t.startAt);
  return (
    <SoloView
      group={{ name: t.group.name, size: `오늘 ${t.group.members.length}명` }}
      clockText={hhmm(now)}
      members={groupPeople(t.group, me)}
      next={next}
      nextWhen={`${dayWord(nextMs, now)} 07:00 시작`}
      beforeStart={beforeStart}
    />
  );
}
