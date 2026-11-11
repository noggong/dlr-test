import { describe, expect, it } from 'vitest';
import {
  afterPass,
  afterSolve,
  chooseQuestion,
  errorAction,
  freshProgress,
  gameWindow,
  reconcile,
  type Question,
} from './flow';
import type { Progress } from './storage';

const DAY = '2026-11-12';
const id = (c: string) => c.repeat(64);
const q = (c: string, difficulty: 1 | 2 | 3): Question => ({
  questionId: id(c),
  difficulty,
  body: `문제 ${c}`,
  category: 'GENERAL',
  answerHash: id('f'),
});
const bank = [q('a', 1), q('b', 1), q('c', 2), q('d', 3)];
const stored = (over: Partial<Progress> = {}): Progress => ({ ...freshProgress(DAY), ...over });

describe('flow — 게임 창 (GS1)', () => {
  it('서버 시각의 KST 날짜 07:00 ~ 08:00 — 08:00 뒤에도 그날 창을 쓴다', () => {
    const w = gameWindow(Date.parse('2026-11-12T08:00:01+09:00'));
    expect(w.date).toBe(DAY);
    expect(w.startMs).toBe(Date.parse('2026-11-12T07:00:00+09:00'));
    expect(w.deadlineMs).toBe(Date.parse('2026-11-12T08:00:00+09:00'));
    // UTC 로는 전날인 KST 새벽
    expect(gameWindow(Date.parse('2026-11-12T07:30:00+09:00')).date).toBe(DAY);
  });
});

describe('flow — ⑦ 맞추기 · ③ 고르기', () => {
  it('O41 서버 진행도를 믿는다 — 번호가 바뀌면 지금 문제를 비운다', () => {
    const s = stored({ questionNo: 1, questionId: id('a'), servedIds: [id('a')] });
    expect(reconcile(s, { progress: 2, passedAtQuestionNo: null }, DAY)).toMatchObject({
      questionNo: 3,
      questionId: null,
      servedIds: [id('a')],
    });
    // 같은 번호면 그대로(이어 풀기)
    expect(reconcile(s, { progress: 0, passedAtQuestionNo: null }, DAY).questionId).toBe(id('a'));
    // 거부(QUESTION_MISMATCH) 뒤에는 같은 번호라도 비운다
    expect(
      reconcile(s, { progress: 0, passedAtQuestionNo: null }, DAY, true).questionId,
    ).toBeNull();
  });

  it('O41 패스 여부 · 완료도 서버를 따른다', () => {
    expect(reconcile(stored(), { progress: 0, passedAtQuestionNo: 1 }, DAY).passUsed).toBe(true);
    expect(reconcile(stored(), { progress: 3, passedAtQuestionNo: null }, DAY).done).toBe(true);
    expect(reconcile(null, undefined, DAY)).toEqual(freshProgress(DAY));
  });

  it('O41 저장된 지금 문제가 은행에 있으면 그것 · 없으면 servedIds 밖에서 골라 더한다', () => {
    const kept = chooseQuestion(stored({ questionId: id('b'), servedIds: [id('b')] }), bank);
    expect(kept?.question.questionId).toBe(id('b'));
    const picked = chooseQuestion(stored({ servedIds: [id('a')] }), bank, () => 0);
    expect(picked?.question.questionId).toBe(id('b'));
    expect(picked?.progress.servedIds).toEqual([id('a'), id('b')]);
    // 은행에 없는 id(다른 날 · 난이도 다름)는 버리고 새로
    expect(
      chooseQuestion(stored({ questionId: id('c') }), bank, () => 0)?.question.questionId,
    ).toBe(id('a'));
    expect(chooseQuestion(stored({ servedIds: [id('a'), id('b')] }), bank)).toBeNull();
  });

  it('O38 · O39 풀이 · 패스 뒤에는 지금 문제를 null 로 둔다', () => {
    const s = stored({ questionNo: 1, questionId: id('a'), servedIds: [id('a')] });
    expect(afterSolve(s)).toMatchObject({ questionNo: 2, questionId: null, done: false });
    expect(afterSolve({ ...s, questionNo: 3 })).toMatchObject({ questionId: null, done: true });
    expect(afterPass(s)).toMatchObject({
      questionNo: 1,
      questionId: null,
      passUsed: true,
      servedIds: [id('a')],
    });
  });
});

describe('flow — 오류 code → 할 일 (SPEC §6.3 · I8)', () => {
  const table: Array<[string, boolean, ReturnType<typeof errorAction>]> = [
    ['GAME_NOT_OPEN', false, { kind: 'navigate', to: '/waiting' }],
    ['SUBMISSION_CLOSED', false, { kind: 'navigate', to: '/done' }],
    ['SUBMISSION_CLOSED', true, { kind: 'navigate', to: '/result' }],
    ['MISSION_ALREADY_FINISHED', false, { kind: 'navigate', to: '/done' }],
    ['MISSION_ALREADY_FINISHED', true, { kind: 'navigate', to: '/result' }],
    ['NOT_PARTICIPANT', false, { kind: 'notice', notice: 'notParticipant', then: '/result' }],
    ['SOLO_GROUP', false, { kind: 'navigate', to: '/solo' }],
    ['QUESTIONS_EXHAUSTED', false, { kind: 'notice', notice: 'exhausted' }],
    ['MISSION_NOT_STARTED', false, { kind: 'restart' }],
    ['QUESTION_OUT_OF_ORDER', false, { kind: 'resync' }],
    ['QUESTION_MISMATCH', false, { kind: 'resync' }],
    ['PASS_ALREADY_USED', false, { kind: 'passLocked' }],
    ['INTERNAL_ERROR', false, { kind: 'notice', notice: 'serverError' }],
    ['HTTP_502', false, { kind: 'notice', notice: 'serverError' }],
    ['NETWORK', false, { kind: 'notice', notice: 'serverError' }],
  ];
  for (const [code, late, want] of table) {
    it(`O42 ${code}${late ? '(08:00 뒤)' : ''} → ${JSON.stringify(want)}`, () => {
      expect(errorAction(code as Parameters<typeof errorAction>[0], late)).toEqual(want);
    });
  }
});
