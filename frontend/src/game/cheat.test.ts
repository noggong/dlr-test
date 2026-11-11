import { describe, expect, it } from 'vitest';
import type { components } from '@/api/schema';
import { shouldReportCheat } from './cheat';
import type { Progress } from './storage';

type MemberStatus = components['schemas']['MemberStatus'];
const STATUSES: MemberStatus[] = [
  'NOT_STARTED',
  'IN_PROGRESS',
  'SUCCEEDED',
  'CHEAT_FAILED',
  'FAILED',
];
const STORED: Progress = {
  date: '2026-11-11',
  questionNo: 1,
  questionId: 'a'.repeat(64),
  servedIds: ['a'.repeat(64)],
  passUsed: false,
  done: false,
};

describe('O26 부정 실패 판정', () => {
  it('O26 진리표(창 × started × 상태 × 저장)에서 참은 「창 안 · started · 진행 중 · 빔」 한 칸뿐', () => {
    const trues: string[] = [];
    let cells = 0;
    for (const inWindow of [false, true])
      for (const started of [false, true])
        for (const status of STATUSES)
          for (const stored of [null, STORED]) {
            cells++;
            if (shouldReportCheat({ inWindow, started, status, stored }))
              trues.push(`${inWindow}/${started}/${status}/${stored === null ? '빔' : '있음'}`);
          }
    expect(cells).toBe(40);
    expect(trues).toEqual(['true/true/IN_PROGRESS/빔']);
  });

  it('O26 done 으로 저장된 진행은 빔이 아니다', () => {
    expect(
      shouldReportCheat({
        inWindow: true,
        started: true,
        status: 'IN_PROGRESS',
        stored: { ...STORED, done: true },
      }),
    ).toBe(false);
  });
});
