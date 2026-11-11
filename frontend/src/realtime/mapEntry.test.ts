import { describe, expect, it } from 'vitest';
import { formatArrival, groupChip, toBoardRow, toBoardRows, type MemberEntry } from './mapEntry';

const entry = (over: Partial<MemberEntry>): MemberEntry => ({
  userId: 'junho',
  rank: 1,
  progress: 2,
  status: 'IN_PROGRESS',
  started: true,
  lastSolvedAt: null,
  passedAtQuestionNo: null,
  ...over,
});

describe('mapEntry — 서버 → 화면 대응표 (SPEC §2.1 · W5)', () => {
  it('O33 상태 5개 → ing · ok · bad · bad · none', () => {
    const tones = (
      ['IN_PROGRESS', 'SUCCEEDED', 'CHEAT_FAILED', 'FAILED', 'NOT_STARTED'] as const
    ).map((status) => toBoardRow(entry({ status })).status);
    expect(tones).toEqual(['ing', 'ok', 'bad', 'bad', 'none']);
  });

  it('O33 started false → 진행도 -1 · 「접속하지 않음」(미접속 · 마감 실패 모두)', () => {
    expect(toBoardRow(entry({ status: 'NOT_STARTED', started: false, progress: 0 }))).toEqual({
      rank: 1,
      id: 'junho',
      progress: -1,
      status: 'none',
      note: '접속하지 않음',
    });
    expect(toBoardRow(entry({ status: 'FAILED', started: false, progress: 0 }))).toMatchObject({
      progress: -1,
      status: 'bad',
      note: '접속하지 않음',
    });
  });

  it('O33 lastSolvedAt → 도착 시각(KST HH:MM:SS) · passedAtQuestionNo → 「n번에서 패스」', () => {
    expect(
      toBoardRow(entry({ lastSolvedAt: '2026-11-12T07:04:02.553001+09:00', passedAtQuestionNo: 1 }))
        .note,
    ).toBe('07:04:02 · 1번에서 패스');
    expect(toBoardRow(entry({ lastSolvedAt: '2026-11-11T22:31:09.000001Z' })).note).toBe(
      '07:31:09',
    );
    expect(toBoardRow(entry({ passedAtQuestionNo: 2, progress: 0 })).note).toBe('2번에서 패스');
    expect(toBoardRow(entry({ progress: 0 })).note).toBeUndefined();
    expect(formatArrival('nope')).toBe('');
  });

  it('O33 부정 실패는 「실패 처리」 — 마감 실패와 아래 줄로 구분된다', () => {
    const at = '2026-11-12T07:02:10.000250+09:00';
    expect(toBoardRow(entry({ status: 'CHEAT_FAILED', lastSolvedAt: at })).note).toBe('실패 처리');
    expect(toBoardRow(entry({ status: 'FAILED', lastSolvedAt: at })).note).toBe('07:02:10');
  });

  it('O33 내 줄 me · 서버 순서 그대로(다시 정렬 안 함) · 동률 rank 유지', () => {
    const rows = toBoardRows(
      [
        entry({ userId: 'minji', rank: 1 }),
        entry({ userId: 'junho', rank: 1 }),
        entry({ userId: 'aaaa', rank: 3 }),
      ],
      'junho',
    );
    expect(rows.map((r) => [r.rank, r.id, r.me ?? false])).toEqual([
      [1, 'minji', false],
      [1, 'junho', true],
      [3, 'aaaa', false],
    ]);
  });

  it('O33 그룹 상태 → 그룹 칩', () => {
    expect(groupChip('IN_PROGRESS')).toEqual({ tone: 'ing', label: '그룹 진행 중' });
    expect(groupChip('PENDING')).toEqual({ tone: 'ing', label: '결과 확정 중' });
    expect(groupChip('SUCCEEDED').tone).toBe('ok');
    expect(groupChip('FAILED').tone).toBe('bad');
  });
});
