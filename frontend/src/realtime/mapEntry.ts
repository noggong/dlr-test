import type { components } from '@/api/schema';
import type { BoardRowData } from '@/components/Board';
import type { Progress, Tone } from '@/lib/types';

export type MemberEntry = components['schemas']['MemberEntry'];
export type MemberStatus = components['schemas']['MemberStatus'];
export type GroupStatus = components['schemas']['GroupStatus'];

/** 서버 상태 5개 → 칩 4색(SPEC §2.1 · W5). 부정 실패와 마감 실패는 같은 「실패」다. */
const tones: Record<MemberStatus, Tone> = {
  NOT_STARTED: 'none',
  IN_PROGRESS: 'ing',
  SUCCEEDED: 'ok',
  CHEAT_FAILED: 'bad',
  FAILED: 'bad',
};

const groupChips: Record<GroupStatus, { tone: Tone; label: string }> = {
  IN_PROGRESS: { tone: 'ing', label: '그룹 진행 중' },
  PENDING: { tone: 'ing', label: '결과 확정 중' },
  SUCCEEDED: { tone: 'ok', label: '그룹 미션 성공' },
  FAILED: { tone: 'bad', label: '그룹 미션 실패' },
};

// 도착 시각은 기기 시간대와 상관없이 KST 로 적는다(api-guide §1)
const kst = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** ISO-8601 시각 → KST `HH:MM:SS`. 읽을 수 없으면 빈 글. */
export function formatArrival(iso: string): string {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? '' : kst.format(t);
}

/** `MemberEntry` → 현황판 한 줄(SPEC §2.1). `viewerId` = 로그인한 유저(그 줄이 `me`). */
export function toBoardRow(entry: MemberEntry, viewerId?: string): BoardRowData {
  const row: BoardRowData = {
    rank: entry.rank,
    id: entry.userId,
    progress: entry.started ? clampProgress(entry.progress) : -1,
    status: tones[entry.status],
  };
  if (viewerId !== undefined && entry.userId === viewerId) row.me = true;
  const note = noteOf(entry);
  if (note) row.note = note;
  return row;
}

/** 서버가 준 순서 그대로(RB11) — 다시 정렬하지 않는다. */
export function toBoardRows(entries: readonly MemberEntry[], viewerId?: string): BoardRowData[] {
  return entries.map((e) => toBoardRow(e, viewerId));
}

/** 그룹 상태 → 현황판 제목 옆 칩. */
export function groupChip(status: GroupStatus): { tone: Tone; label: string } {
  return groupChips[status];
}

function noteOf(e: MemberEntry): string | undefined {
  if (!e.started) return '접속하지 않음';
  if (e.status === 'CHEAT_FAILED') return '실패 처리';
  const parts: string[] = [];
  if (e.lastSolvedAt) {
    const at = formatArrival(e.lastSolvedAt);
    if (at) parts.push(at);
  }
  if (e.passedAtQuestionNo !== null) parts.push(`${e.passedAtQuestionNo}번에서 패스`);
  return parts.length > 0 ? parts.join(' · ') : undefined;
}

function clampProgress(n: number): Progress {
  return Math.max(0, Math.min(3, Math.trunc(n))) as Progress;
}
