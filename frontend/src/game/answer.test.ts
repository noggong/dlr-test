import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { answerHash, isCorrect } from './answer';
import { normalizeAnswer, REMOVED_CODE_POINTS } from './normalize';

// 공유 벡터를 그대로 읽는다(SPEC W20) — 프론트에 사본을 두지 않는다. 앱 코드는 이 파일을 import 하지 않는다(Docker 빌드 컨텍스트 밖).
interface Vectors {
  removed: string[];
  questionId: string;
  vectors: { name: string; input: string; normalized: string; hash: string }[];
  seedQuestions: { questionId: string; answer: string; normalized: string; answerHash: string }[];
}
const V: Vectors = JSON.parse(
  readFileSync(
    resolve(
      dirname(fileURLToPath(import.meta.url)),
      '../../../docs/api/answer-normalization-vectors.json',
    ),
    'utf8',
  ),
);

const toU = (cp: number) => `U+${cp.toString(16).toUpperCase().padStart(4, '0')}`;

describe('O23 공유 벡터와 정규화 · 해시가 같다', () => {
  it('O23 지우는 문자 목록이 벡터의 removed 33개와 같다', () => {
    expect(V.removed).toHaveLength(33);
    expect(REMOVED_CODE_POINTS.map(toU)).toEqual(V.removed);
  });

  it('O23 vectors 35줄', () => {
    expect(V.vectors).toHaveLength(35);
  });

  it.each(V.vectors.map((v) => [v.name, v] as const))('O23 %s', async (_name, v) => {
    expect(normalizeAnswer(v.input)).toBe(v.normalized);
    expect(await answerHash(V.questionId, v.input)).toBe(v.hash);
  });

  it('O23 빈 결과도 해시한다', async () => {
    const empty = V.vectors.filter((v) => v.normalized === '');
    expect(empty.length).toBeGreaterThan(0);
    for (const v of empty)
      expect(await answerHash(V.questionId, v.input)).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe('O24 해시가 서버 시드와 같다', () => {
  it('O24 seedQuestions 4개', () => {
    expect(V.seedQuestions).toHaveLength(4);
  });

  it.each(V.seedQuestions.map((q) => [q.questionId.slice(0, 8), q] as const))(
    'O24 %s',
    async (_id, q) => {
      expect(normalizeAnswer(q.answer)).toBe(q.normalized);
      expect(await answerHash(q.questionId, q.answer)).toBe(q.answerHash);
      expect(await isCorrect(q, q.answer)).toBe(true);
      // 공백 · 대문자 · 전각 공백 · 탭 · 폭 0 문자를 섞어도 맞다
      const messy = `\t ${q.normalized.toUpperCase().split('').join(' ')}\u3000\u200b `;
      expect(await isCorrect(q, messy)).toBe(true);
      expect(await isCorrect(q, `${q.answer}x`)).toBe(false);
    },
  );
});
