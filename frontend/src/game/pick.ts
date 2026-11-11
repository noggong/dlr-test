import type { components } from '@/api/schema';

type Question = components['schemas']['Question'];
type QuestionNo = components['schemas']['QuestionNo'];

/** [0, 1) 난수 */
export type Rng = () => number;

/** `crypto.getRandomValues` 로 만든 [0, 1) 난수. */
export const cryptoRng: Rng = () => crypto.getRandomValues(new Uint32Array(1))[0] / 2 ** 32;

/**
 * 그 번호 난이도(difficulty == questionNo)에서 제외 목록을 뺀 문제 하나를 무작위로 고른다(SPEC §6.4 ③).
 * 후보가 없으면 `null`.
 */
export function pickQuestion<Q extends Pick<Question, 'questionId' | 'difficulty'>>(
  bank: readonly Q[],
  questionNo: QuestionNo,
  excludeIds: Iterable<string>,
  rng: Rng = cryptoRng,
): Q | null {
  const excluded = new Set(excludeIds);
  const candidates = bank.filter((q) => q.difficulty === questionNo && !excluded.has(q.questionId));
  if (candidates.length === 0) return null;
  const i = Math.min(Math.max(Math.floor(rng() * candidates.length), 0), candidates.length - 1);
  return candidates[i];
}
