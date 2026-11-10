import type { BoardRowData } from '@/components/Board';

/** 스토리용 현황판 줄 — 시안 play 5(07:18 · 실시간 갱신)와 같은 값. */
export const liveRows: BoardRowData[] = [
  { rank: 1, id: 'minji', progress: 2, status: 'ing', note: '07:17:55' },
  { rank: 2, id: 'junho', me: true, progress: 1, status: 'ing', note: '07:06:10 · 1번에서 패스' },
  { rank: 3, id: 'hana', progress: 1, status: 'ing', note: '07:09:31' },
  { rank: 4, id: 'seoyeon', progress: 1, status: 'ing', note: '07:12:03' },
  { rank: 5, id: 'dohyun', progress: -1, status: 'ing' },
];
