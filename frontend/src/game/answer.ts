import type { components } from '@/api/schema';
import { normalizeAnswer } from './normalize';

type Question = components['schemas']['Question'];

/** `SHA-256(UTF-8("<questionId>:<정규화한 답>"))` 소문자 hex(prd D7). 빈 결과도 해시한다. */
export async function answerHash(questionId: string, answer: string): Promise<string> {
  const bytes = new TextEncoder().encode(`${questionId}:${normalizeAnswer(answer)}`);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** 문제은행의 `answerHash` 와 같으면 정답(SPEC W19). */
export async function isCorrect(
  question: Pick<Question, 'questionId' | 'answerHash'>,
  answer: string,
): Promise<boolean> {
  return (await answerHash(question.questionId, answer)) === question.answerHash;
}
