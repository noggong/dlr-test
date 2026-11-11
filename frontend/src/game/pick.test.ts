import { describe, expect, it } from 'vitest';
import { cryptoRng, pickQuestion } from './pick';

const q = (id: string, difficulty: 1 | 2 | 3) => ({ questionId: id.repeat(64), difficulty });
const BANK = [q('a', 1), q('b', 1), q('c', 1), q('d', 2), q('e', 2), q('f', 3)];

describe('O27 문제 고르기', () => {
  it('O27 주입한 난수로 그 난이도 후보 중 하나를 고른다', () => {
    expect(pickQuestion(BANK, 1, [], () => 0)).toBe(BANK[0]);
    expect(pickQuestion(BANK, 1, [], () => 0.5)).toBe(BANK[1]);
    expect(pickQuestion(BANK, 1, [], () => 0.999999)).toBe(BANK[2]);
    expect(pickQuestion(BANK, 2, [], () => 0)).toBe(BANK[3]);
  });

  it('O27 난수가 범위를 벗어나도 후보 안에서 고른다', () => {
    expect(pickQuestion(BANK, 1, [], () => 1)).toBe(BANK[2]);
    expect(pickQuestion(BANK, 1, [], () => -0.5)).toBe(BANK[0]);
  });

  it('O27 늘 그 번호 난이도 · 제외 목록 밖', () => {
    const exclude = [BANK[0].questionId, BANK[3].questionId];
    for (const no of [1, 2, 3] as const)
      for (let i = 0; i < 200; i++) {
        const got = pickQuestion(BANK, no, exclude)!;
        expect(got.difficulty).toBe(no);
        expect(exclude).not.toContain(got.questionId);
      }
  });

  it('O27 후보가 없으면 null', () => {
    expect(pickQuestion(BANK, 3, [BANK[5].questionId])).toBeNull();
    expect(pickQuestion([], 1, [])).toBeNull();
    expect(pickQuestion([q('a', 2)], 1, [])).toBeNull();
  });

  it('O27 1000번 뽑아 후보마다 1번 이상 나온다(기본 crypto 난수)', () => {
    const seen = new Map<string, number>();
    for (let i = 0; i < 1000; i++) {
      const got = pickQuestion(BANK, 1, [BANK[2].questionId])!;
      seen.set(got.questionId, (seen.get(got.questionId) ?? 0) + 1);
    }
    expect([...seen.keys()].sort()).toEqual([BANK[0].questionId, BANK[1].questionId]);
    for (let i = 0; i < 1000; i++) {
      const r = cryptoRng();
      expect(r).toBeGreaterThanOrEqual(0);
      expect(r).toBeLessThan(1);
    }
  });
});
