import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useServerNow } from '@/app/serverNow';
import { useSession } from '@/app/session';
import { gameWindow, inWindow } from '@/game/flow';
import { useLiveBoard } from '@/realtime/useLiveBoard';
import { DoneView, type DoneSelf } from './DoneView';
import { doneResultSub, liveBoardProps, useProgressToast } from './liveView';

const GROUP_ING = { tone: 'ing', label: '그룹 진행 중' } as const;

/**
 * 완료 컨테이너 — 내 상태는 실시간 현황판의 내 줄(`SUCCEEDED` → 완료 · `CHEAT_FAILED` → 실패 처리).
 * 아직 진행 중이고 그룹 결과가 확정 전이면 `/quiz`. 그룹 결과가 확정되면 대화상자 + 랭킹(W29),
 * 서버 기준 08:00 이면 `/result`, 07:00 전이면 `/waiting`(W25).
 */
export default function DonePage() {
  const { me, today, clock } = useSession();
  const navigate = useNavigate();
  const [win] = useState(() => gameWindow(clock.nowMs()));
  const [liveOn] = useState(() => inWindow(win, clock.nowMs()));
  const nowMs = useServerNow(clock);
  const live = useLiveBoard({ viewerId: me ?? undefined, enabled: liveOn });
  const toast = useProgressToast(live.lastEvent, me);

  const mine = live.entries.find((e) => e.userId === me);
  const decided = live.groupStatus === 'SUCCEEDED' || live.groupStatus === 'FAILED';
  const loaded = live.version !== undefined;
  const self: DoneSelf | null =
    mine?.status === 'SUCCEEDED'
      ? 'ok'
      : mine?.status === 'CHEAT_FAILED'
        ? 'selffail'
        : decided
          ? 'ended'
          : null;

  // 경계 · 서버가 알려 준 곳으로
  const stopCode = live.connection.state === 'stopped' ? live.connection.code : undefined;
  const target =
    nowMs < win.startMs || stopCode === 'GAME_NOT_OPEN'
      ? '/waiting'
      : nowMs >= win.deadlineMs || stopCode === 'NOT_PARTICIPANT'
        ? '/result'
        : stopCode === 'SOLO_GROUP'
          ? '/solo'
          : loaded && self === null
            ? '/quiz'
            : null;
  useEffect(() => {
    if (target) navigate(target, { replace: true });
  }, [target, navigate]);

  const now = useCallback(() => clock.nowMs(), [clock]);
  if (today.status !== 'ready') return null;
  const t = today.today;
  const board = liveBoardProps(live);

  return (
    <DoneView
      group={{ name: t.group.name, size: `${t.group.members.length}명` }}
      clock={{ deadline: { until: win.deadlineMs, now }, tone: 'hot' }}
      self={self ?? 'ok'}
      loading={self === null}
      board={{ ...board, groupStatus: board.groupStatus ?? GROUP_ING }}
      result={
        live.groupStatus === 'SUCCEEDED' || live.groupStatus === 'FAILED'
          ? {
              tone: live.groupStatus === 'SUCCEEDED' ? 'ok' : 'bad',
              sub: doneResultSub(live.groupStatus, live.entries),
              ranking: live.rows,
            }
          : undefined
      }
      toast={toast}
    />
  );
}
