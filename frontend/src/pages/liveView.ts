import { useEffect, useState } from 'react';
import type { BoardRowData } from '@/components/Board';
import { boardSummary } from '@/game/flow';
import type { Tone } from '@/lib/types';
import { formatArrival, groupChip, type MemberEntry } from '@/realtime/mapEntry';
import type { LiveBoardView } from '@/realtime/useLiveBoard';

/** 토스트를 두는 시간(W4 · W29). */
export const TOAST_MS = 4000;

/** 퀴즈 · 완료 지면 현황판 props(SPEC §2.1 · W29). 끊겨 다시 붙는 중이면 요약에 적는다(LLD §4). */
export function liveBoardProps(live: LiveBoardView): {
  summary?: string;
  groupStatus?: { tone: Tone; label: string };
  rows: BoardRowData[];
  loading: boolean;
} {
  const loaded = live.version !== undefined;
  const retrying = live.connection.state === 'retrying';
  const summary = loaded ? boardSummary(live.entries) : undefined;
  return {
    summary: retrying ? [summary, '다시 연결하는 중'].filter(Boolean).join(' · ') : summary,
    groupStatus: live.groupStatus ? groupChip(live.groupStatus) : undefined,
    rows: live.rows,
    loading: !loaded && live.connection.state !== 'stopped',
  };
}

/** 다른 그룹원이 문제를 풀면 잠깐 토스트(W29 `member.progressed`). 내 것은 띄우지 않는다. */
export function useProgressToast(
  lastEvent: LiveBoardView['lastEvent'],
  me: string | null,
): { id: string; progress: number } | undefined {
  const [toast, setToast] = useState<{ id: string; progress: number }>();
  useEffect(() => {
    if (lastEvent?.type !== 'member.progressed' || lastEvent.userId === me) return;
    setToast({ id: lastEvent.userId, progress: lastEvent.progress });
    const t = window.setTimeout(() => setToast(undefined), TOAST_MS);
    return () => window.clearTimeout(t);
  }, [lastEvent, me]);
  return toast;
}

/** 그룹 결과 대화상자 아래 줄. 성공은 마지막 풀이 도착 시각과 함께. */
export function doneResultSub(
  status: 'SUCCEEDED' | 'FAILED',
  entries: readonly MemberEntry[],
): string {
  if (status === 'FAILED') return '실패 처리된 그룹원이 있어 그룹 미션을 끝낼 수 없어요.';
  const last = entries
    .map((e) => e.lastSolvedAt)
    .filter((t): t is string => typeof t === 'string')
    .sort((a, b) => Date.parse(a) - Date.parse(b))
    .pop();
  const at = last ? formatArrival(last) : '';
  return `${at ? `${at} · ` : ''}${entries.length}명 모두 3문제를 풀었어요`;
}
