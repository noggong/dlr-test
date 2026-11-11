import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ServerClock } from '@/app/clock';
import { answerHash } from '@/game/answer';
import { err, json, kst, mockFetch, renderApp, todayBody, type Handler } from '@/test/app';

// 문제 풀이 · 완료 지면(T-WC-6) — ORACLE O38 ~ O44. 서버는 fetch 가짜, 시각은 가짜 타이머 + 서버 시계.

const ME = 'jiwoo_k';
const DAY = '2026-11-12';
const KEY = `gm:v1:${ME}`;
const R = {
  me: 'GET /api/v1/auth/me',
  today: 'GET /api/v1/games/today',
  board: 'GET /api/v1/games/today/board',
  bank: 'POST /api/v1/games/today/question-bank',
  shown: 'POST /api/v1/games/today/shown',
  solve: 'POST /api/v1/games/today/solves',
  pass: 'POST /api/v1/games/today/passes',
  failure: 'POST /api/v1/games/today/failures',
  ticket: 'POST /api/v1/realtime/tickets',
  events: 'GET /realtime/v1/games/today/events?ticket=t',
  result: 'GET /api/v1/games/results/latest',
};

// 가짜 타이머 밖의 진짜 차례 넘기기 — Web Crypto 해시는 타이머가 아니라 스레드에서 끝난다
const realImmediate = setImmediate;
const loc = () => screen.getByTestId('loc').textContent;
const tick = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));
async function until(cond: () => boolean, limit = 300) {
  for (let i = 0; i < limit && !cond(); i++) {
    await act(async () => {
      await new Promise((r) => realImmediate(r));
      await vi.advanceTimersByTimeAsync(0);
    });
  }
  expect(cond()).toBe(true);
}

// ── 문제은행 ──
const qid = (c: string) => c.repeat(64);
const ANSWER: Record<string, string> = {};
const bankQs = [
  ['1', 1],
  ['2', 1],
  ['3', 2],
  ['4', 2],
  ['5', 3],
  ['6', 3],
] as const;
let questions: Array<Record<string, unknown>> = [];
beforeAll(async () => {
  questions = await Promise.all(
    bankQs.map(async ([c, difficulty]) => {
      ANSWER[qid(c)] = `답${c}`;
      return {
        questionId: qid(c),
        difficulty,
        body: `문제 ${c}`,
        category: 'GENERAL',
        answerHash: await answerHash(qid(c), `답${c}`),
      };
    }),
  );
});
const bankBody = () => ({ missionStartedAt: kst('07:00:01.000000'), questions });

// ── 현황판 ──
interface Mine {
  progress?: number;
  started?: boolean;
  status?: string;
  passedAtQuestionNo?: number | null;
}
const boardBody = (mine: Mine = {}, groupStatus = 'IN_PROGRESS') => ({
  date: DAY,
  groupStatus,
  version: 1,
  entries: [
    {
      userId: ME,
      rank: 1,
      progress: mine.progress ?? 0,
      started: mine.started ?? false,
      status: mine.status ?? 'NOT_STARTED',
      lastSolvedAt: null,
      passedAtQuestionNo: mine.passedAtQuestionNo ?? null,
    },
    {
      userId: 'dohyun',
      rank: 1,
      progress: 0,
      started: true,
      status: 'IN_PROGRESS',
      lastSolvedAt: null,
      passedAtQuestionNo: null,
    },
  ],
});

const body = (init: RequestInit) => JSON.parse(String(init.body)) as Record<string, unknown>;
const stored = () => JSON.parse(localStorage.getItem(KEY) ?? 'null') as Record<string, unknown>;
const store = (over: Record<string, unknown>) =>
  localStorage.setItem(
    KEY,
    JSON.stringify({
      date: DAY,
      questionNo: 1,
      questionId: null,
      servedIds: [],
      passUsed: false,
      done: false,
      ...over,
    }),
  );

/** 실시간 스트림 — 열어 두기만 한다(현황판은 조회 응답으로 그린다). */
const quietStream: Handler = () =>
  new Response(new ReadableStream<Uint8Array>({ start: () => {} }), {
    headers: { 'Content-Type': 'text/event-stream' },
  });

/** 기록하는 신호 처리기 — 본문을 모으고 200. */
function recorder(res: (b: Record<string, unknown>) => unknown = () => ({})) {
  const bodies: Array<Record<string, unknown>> = [];
  const handler: Handler = (init) => {
    const b = init.body ? body(init) : {};
    bodies.push(b);
    return json(200, res(b));
  };
  return { bodies, handler };
}

function setup(serverHms: string, routes: Record<string, Handler[]>) {
  const serverTime = kst(serverHms);
  vi.setSystemTime(Date.parse(serverTime) + 2 * 60 * 60 * 1000); // 기기 시계는 2시간 틀렸다
  const clock = new ServerClock(() => Date.now());
  const m = mockFetch({
    [R.me]: [() => json(200, { userId: ME, groupName: '아침수영' })],
    [R.today]: [() => json(200, todayBody(serverTime, [ME, 'dohyun', 'minseo01']))],
    [R.board]: [() => json(200, boardBody())],
    [R.bank]: [() => json(200, bankBody())],
    [R.ticket]: [() => json(201, { ticket: 't' })],
    [R.events]: [quietStream],
    [R.result]: [() => err(404, 'NO_RESULT_YET')],
    ...routes,
  });
  return { clock, ...m };
}

const input = () => screen.getByLabelText('답') as HTMLInputElement;
const submitButton = () => screen.getByRole('button', { name: '제출' });
const passButton = () => screen.getByRole('button', { name: /패스/ });
function answer(text: string) {
  fireEvent.change(input(), { target: { value: text } });
  fireEvent.click(submitButton());
}

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('문제 풀이 — 한 문제 흐름 (SPEC §6.4 ②~⑤)', () => {
  it('O38 은행 전 저장 → 은행 → 고른 문제 저장 → shown → 오답(호출 없음) → 정답 → solves → 2번 shown', async () => {
    let atBank: Record<string, unknown> | null = null;
    const shown = recorder((b) => ({ ...b, progress: 0, missionStatus: 'IN_PROGRESS' }));
    const solve = recorder(() => ({
      progress: 1,
      missionStatus: 'IN_PROGRESS',
      groupStatus: 'IN_PROGRESS',
    }));
    const { clock, count } = setup('07:10:00.000', {
      [R.bank]: [
        () => {
          atBank = stored();
          return json(200, bankBody());
        },
      ],
      [R.shown]: [shown.handler],
      [R.solve]: [solve.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(loc()).toBe('/quiz');
    // 은행(미션 시작)보다 먼저 저장했다 — 그 사이 끊겨도 「빔」이 아니다
    expect(atBank).toMatchObject({ date: DAY, questionNo: 1, questionId: null });
    const first = shown.bodies[0];
    expect(first.questionNo).toBe(1);
    expect(['1', '2'].map(qid)).toContain(first.questionId);
    expect(stored()).toMatchObject({ questionNo: 1, questionId: first.questionId });
    expect(stored().servedIds).toEqual([first.questionId]);
    expect(count(R.failure)).toBe(0);

    await until(() => screen.queryByText(`문제 ${String(first.questionId)[0]}`) !== null);
    answer('틀린 답');
    await until(() => screen.queryByText('틀렸어요. 같은 문제를 다시 풀어 보세요.') !== null);
    expect(count(R.solve)).toBe(0);

    answer(` ${ANSWER[String(first.questionId)]} `);
    await until(() => shown.bodies.length === 2);
    expect(solve.bodies).toHaveLength(1);
    const s = solve.bodies[0];
    expect(s).toMatchObject({ questionNo: 1, questionId: first.questionId });
    expect(s.clickedAt).toMatch(/^2026-11-12T07:10:00\.\d{3}\+09:00$/);
    expect(s.successTime).toMatch(/^2026-11-12T07:10:00\.\d{3}\+09:00$/);
    expect(shown.bodies[1].questionNo).toBe(2);
    expect(['3', '4'].map(qid)).toContain(shown.bodies[1].questionId);
    expect(stored()).toMatchObject({ questionNo: 2, questionId: shown.bodies[1].questionId });
  });

  it('O38 3번을 맞히면 done 저장 → /done', async () => {
    store({ questionNo: 3, questionId: qid('5'), servedIds: [qid('1'), qid('3'), qid('5')] });
    const solve = recorder(() => ({
      progress: 3,
      missionStatus: 'SUCCEEDED',
      groupStatus: 'IN_PROGRESS',
    }));
    const mine = { progress: 2, started: true, status: 'IN_PROGRESS' };
    const { clock } = setup('07:30:00.000', {
      [R.board]: [
        () => json(200, boardBody(mine)),
        () => json(200, boardBody({ ...mine, progress: 3, status: 'SUCCEEDED' })),
      ],
      [R.shown]: [() => json(200, {})],
      [R.solve]: [solve.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText('문제 5') !== null);
    answer('답5');
    await until(() => loc() === '/done');
    expect(solve.bodies[0]).toMatchObject({ questionNo: 3, questionId: qid('5') });
    expect(stored()).toMatchObject({ done: true, questionId: null });
    await until(() => screen.queryByRole('heading', { name: '3문제를 다 풀었어요' }) !== null);
  });
});

describe('문제 풀이 — 패스 (SPEC §6.4 ⑥ · W7)', () => {
  it('O39 passes → 같은 번호 · 다른 문제로 shown → 패스 잠금(새로고침 뒤에도)', async () => {
    const shown = recorder();
    const pass = recorder((b) => ({
      passedAtQuestionNo: b.questionNo,
      questionNo: b.questionNo,
      progress: 0,
    }));
    const { clock } = setup('07:10:00.000', {
      [R.board]: [
        () => json(200, boardBody()),
        () => json(200, boardBody({ started: true, status: 'IN_PROGRESS', passedAtQuestionNo: 1 })),
      ],
      [R.shown]: [shown.handler],
      [R.pass]: [pass.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    const first = shown.bodies[0].questionId;
    fireEvent.click(passButton());
    expect(screen.getByRole('dialog', { name: '1번 문제를 패스할까요?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '패스하기' }));
    await until(() => shown.bodies.length === 2);
    expect(pass.bodies).toEqual([{ questionNo: 1, questionId: first }]);
    expect(shown.bodies[1].questionNo).toBe(1);
    expect(shown.bodies[1].questionId).not.toBe(first);
    expect(stored()).toMatchObject({ passUsed: true, questionId: shown.bodies[1].questionId });
    expect(stored().servedIds).toEqual([first, shown.bodies[1].questionId]);
    await until(() => screen.queryByRole('button', { name: '패스 사용함' }) !== null);
    expect(screen.getByRole('button', { name: '패스 사용함' })).toBeDisabled();

    // 새로고침 — 같은 문제로 이어 풀고 패스는 잠겨 있다
    cleanup();
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 3);
    expect(shown.bodies[2]).toEqual(shown.bodies[1]);
    await until(() => screen.queryByRole('button', { name: '패스 사용함' }) !== null);
    expect(pass.bodies).toHaveLength(1);
  });

  it('O39 PASS_ALREADY_USED → 패스 잠금 · passUsed 저장 · 같은 문제 그대로', async () => {
    const shown = recorder();
    const { clock, count } = setup('07:10:00.000', {
      [R.shown]: [shown.handler],
      [R.pass]: [() => err(409, 'PASS_ALREADY_USED')],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    fireEvent.click(passButton());
    fireEvent.click(screen.getByRole('button', { name: '패스하기' }));
    await until(() => screen.queryByRole('button', { name: '패스 사용함' }) !== null);
    expect(stored()).toMatchObject({ passUsed: true, questionId: shown.bodies[0].questionId });
    expect(count(R.shown)).toBe(1);
  });
});

describe('문제 풀이 — 부정 실패 신호 (W22 · prd R-F2)', () => {
  it('O40 started · 진행 중 · 저장 빔 → failures 1번 → /done(실패 처리), 은행 없음', async () => {
    const mine = { progress: 1, started: true, status: 'IN_PROGRESS' };
    const { clock, count } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody(mine)),
        () => json(200, boardBody({ ...mine, status: 'CHEAT_FAILED' })),
      ],
      [R.failure]: [
        () => json(200, { progress: 1, missionStatus: 'CHEAT_FAILED', groupStatus: 'IN_PROGRESS' }),
      ],
    });
    await renderApp('/quiz', clock);
    await until(() => loc() === '/done');
    await until(
      () => screen.queryByRole('heading', { name: '오늘 미션은 실패 처리됐어요' }) !== null,
    );
    expect(count(R.failure)).toBe(1);
    expect(count(R.bank)).toBe(0);
    expect(stored()).toMatchObject({ done: true });
  });

  it('O40 started 아님 → failures 없이 은행', async () => {
    const shown = recorder();
    const { clock, count } = setup('07:20:00.000', { [R.shown]: [shown.handler] });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(count(R.failure)).toBe(0);
    expect(count(R.bank)).toBe(1);
  });

  it('O40 started 인데 저장이 있으면(이어 풀기) failures 없음', async () => {
    store({ questionNo: 2, questionId: qid('3'), servedIds: [qid('1'), qid('3')] });
    const shown = recorder();
    const { clock, count } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody({ progress: 1, started: true, status: 'IN_PROGRESS' })),
      ],
      [R.shown]: [shown.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(count(R.failure)).toBe(0);
  });
});

describe('문제 풀이 — 이어 풀기 (W23 · ⑦)', () => {
  it('O41 2번을 풀던 저장 → 같은 2번 문제로 shown · 새로 고르지 않는다', async () => {
    store({ questionNo: 2, questionId: qid('4'), servedIds: [qid('1'), qid('4')] });
    const shown = recorder();
    const { clock, count } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody({ progress: 1, started: true, status: 'IN_PROGRESS' })),
      ],
      [R.shown]: [shown.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(shown.bodies[0]).toEqual({ questionNo: 2, questionId: qid('4') });
    expect(count(R.bank)).toBe(1);
    expect(stored().servedIds).toEqual([qid('1'), qid('4')]);
  });

  it('O41 서버 진행도가 다르면 서버를 따른다 — 1번 저장 · 서버 2문제 → 3번', async () => {
    store({ questionNo: 1, questionId: qid('1'), servedIds: [qid('1')] });
    const shown = recorder();
    const { clock } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody({ progress: 2, started: true, status: 'IN_PROGRESS' })),
      ],
      [R.shown]: [shown.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(shown.bodies[0].questionNo).toBe(3);
    expect(['5', '6'].map(qid)).toContain(shown.bodies[0].questionId);
    expect(stored()).toMatchObject({ questionNo: 3, questionId: shown.bodies[0].questionId });
  });
});

describe('문제 풀이 — 오류 code 대응 (SPEC §6.3)', () => {
  it('O42 shown QUESTION_MISMATCH → 현황판 다시 읽기 · 다른 문제로 shown', async () => {
    store({ questionNo: 1, questionId: qid('1'), servedIds: [qid('1')] });
    const shown = recorder();
    const { clock, count } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody({ progress: 0, started: true, status: 'IN_PROGRESS' })),
      ],
      [R.shown]: [() => err(409, 'QUESTION_MISMATCH'), shown.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(count(R.board)).toBeGreaterThanOrEqual(2);
    expect(shown.bodies[0]).toEqual({ questionNo: 1, questionId: qid('2') });
    expect(stored()).toMatchObject({ questionId: qid('2'), servedIds: [qid('1'), qid('2')] });
  });

  it('O42 MISSION_NOT_STARTED 가 계속되면 두 번 다시 한 뒤 멈추고 「잠시 뒤 다시 열어 주세요」', async () => {
    const { clock, count } = setup('07:20:00.000', {
      [R.shown]: [() => err(409, 'MISSION_NOT_STARTED')],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText('잠시 뒤 다시 열어 주세요.') !== null);
    expect(count(R.bank)).toBe(3);
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('O42 MISSION_NOT_STARTED → 은행부터 다시', async () => {
    const shown = recorder();
    const { clock, count } = setup('07:20:00.000', {
      [R.shown]: [() => err(409, 'MISSION_NOT_STARTED'), shown.handler],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(count(R.bank)).toBe(2);
  });

  it('O42 은행 QUESTIONS_EXHAUSTED → 「오늘은 문제가 준비되지 않았어요」', async () => {
    const { clock } = setup('07:20:00.000', {
      [R.bank]: [() => err(409, 'QUESTIONS_EXHAUSTED')],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText('오늘은 문제가 준비되지 않았어요.') !== null);
  });

  it('O42 현황판 NOT_PARTICIPANT → 「내일부터 참가해요」 + 결과 보기', async () => {
    const { clock, count } = setup('07:20:00.000', {
      [R.board]: [() => err(403, 'NOT_PARTICIPANT')],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText(/내일부터 참가해요/) !== null);
    expect(screen.getByRole('link', { name: '결과 보기' })).toHaveAttribute('href', '/result');
    expect(count(R.bank)).toBe(0);
  });

  it('O42 SOLO_GROUP → /solo', async () => {
    const serverTime = kst('07:20:00.000');
    const { clock } = setup('07:20:00.000', {
      [R.today]: [() => json(200, todayBody(serverTime, [ME]))],
      [R.bank]: [() => err(409, 'SOLO_GROUP')],
    });
    await renderApp('/quiz', clock);
    await until(() => loc() === '/solo');
  });

  it('O42 GAME_NOT_OPEN → /waiting', async () => {
    // 서버가 아직 07:00 전이라 했다 — 대기 지면(어제 결과 조회)으로 간다. 서버 시계로는 이미 07:00 뒤라
    // 대기 지면이 다시 /quiz 로 보내므로, 대기 지면에 닿았다는 것을 그 조회로 확인한다
    const { clock, count } = setup('07:20:00.000', {
      [R.shown]: [() => err(409, 'GAME_NOT_OPEN'), () => json(200, {})],
    });
    await renderApp('/quiz', clock);
    await until(() => count(R.result) >= 1);
  });

  it('O42 풀이 MISSION_ALREADY_FINISHED(08:00 전) → /done', async () => {
    const mine = { progress: 0, started: true, status: 'IN_PROGRESS' };
    store({ questionNo: 1, questionId: qid('1'), servedIds: [qid('1')] });
    const { clock } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody(mine)),
        () => json(200, boardBody({ ...mine, status: 'CHEAT_FAILED' })),
      ],
      [R.shown]: [() => json(200, {})],
      [R.solve]: [() => err(409, 'MISSION_ALREADY_FINISHED')],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText('문제 1') !== null);
    answer('답1');
    await until(() => loc() === '/done');
  });

  it('O42 풀이 INTERNAL_ERROR → 「잠시 뒤 다시 열어 주세요」 · 문제는 그대로 · 저장 그대로', async () => {
    store({ questionNo: 1, questionId: qid('1'), servedIds: [qid('1')] });
    const { clock } = setup('07:20:00.000', {
      [R.board]: [
        () => json(200, boardBody({ progress: 0, started: true, status: 'IN_PROGRESS' })),
      ],
      [R.shown]: [() => json(200, {})],
      [R.solve]: [() => err(500, 'INTERNAL_ERROR')],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText('문제 1') !== null);
    answer('답1');
    await until(() => screen.queryByText('잠시 뒤 다시 열어 주세요.') !== null);
    expect(screen.getByText('문제 1')).toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
    expect(stored()).toMatchObject({ questionNo: 1, questionId: qid('1') });
  });

  it('O42 Web Crypto 가 없는 주소 → 판정하지 않고 안내 · 제출 잠금(W19)', async () => {
    vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) });
    const shown = recorder();
    const { clock } = setup('07:20:00.000', { [R.shown]: [shown.handler] });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(
      screen.getByText('이 주소에서는 정답을 확인할 수 없어요(https 또는 localhost 로 여세요).'),
    ).toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
  });
});

describe('문제 풀이 — 08:00 잠금 (W25 · D1)', () => {
  it('O43 서버 08:00 이 되면 입력 · 패스 · 제출 disabled, 07:59:59.9 에 누른 제출은 응답을 기다려 반영 → /result', async () => {
    store({ questionNo: 1, questionId: qid('1'), servedIds: [qid('1')] });
    let release!: () => void;
    const solve = recorder();
    const { clock } = setup('07:59:59.000', {
      [R.board]: [
        () => json(200, boardBody({ progress: 0, started: true, status: 'IN_PROGRESS' })),
      ],
      [R.shown]: [() => json(200, {})],
      [R.solve]: [
        (init) =>
          new Promise<Response>((resolve) => {
            release = () => resolve(solve.handler(init) as Response);
          }),
      ],
    });
    await renderApp('/quiz', clock);
    await until(() => screen.queryByText('문제 1') !== null);
    await tick(900 - (clock.nowMs() - Date.parse(kst('07:59:59.000'))));
    answer('답1');
    await until(() => typeof release === 'function');
    await tick(300); // 08:00:00.2
    expect(clock.nowMs()).toBeGreaterThanOrEqual(Date.parse(kst('08:00:00.000')));
    expect(input()).toBeDisabled();
    expect(submitButton()).toBeDisabled();
    expect(passButton()).toBeDisabled();
    expect(loc()).toBe('/quiz'); // 응답을 기다린다
    await act(async () => release());
    await until(() => loc() === '/result');
    expect(solve.bodies).toHaveLength(1);
    expect(Date.parse(String(solve.bodies[0].clickedAt))).toBeLessThan(
      Date.parse(kst('08:00:00.000')),
    );
    expect(stored()).toMatchObject({ questionNo: 2, questionId: null });
  });

  it('O43 제출 중이 아니면 08:00 에 바로 /result', async () => {
    const shown = recorder();
    const { clock } = setup('07:59:58.000', { [R.shown]: [shown.handler] });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(loc()).toBe('/quiz');
    await tick(2100);
    await until(() => loc() === '/result');
  });
});

describe('문제 풀이 — 경고 (W24 · prd R-F3)', () => {
  let visibility: DocumentVisibilityState = 'visible';
  beforeEach(() => {
    visibility = 'visible';
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    });
  });
  const unload = () => {
    const e = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(e);
    return e.defaultPrevented;
  };
  const flip = (v: DocumentVisibilityState) =>
    act(async () => {
      visibility = v;
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);
    });

  it('O44 미션 중에는 beforeunload 를 막고 hidden → visible 때 경고 대화상자, failures 없음', async () => {
    const shown = recorder();
    const { clock, count } = setup('07:20:00.000', { [R.shown]: [shown.handler] });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1);
    expect(unload()).toBe(true);
    await flip('hidden');
    await flip('visible');
    await until(
      () =>
        screen.queryByRole('alertdialog', { name: '부정행위로 실패 처리될 수 있어요' }) !== null,
    );
    fireEvent.click(screen.getByRole('button', { name: '계속 풀기' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(count(R.failure)).toBe(0);
    expect(stored()).toMatchObject({ questionId: shown.bodies[0].questionId });
  });

  it('O44 완료 뒤 · 07:00 전에는 경고가 없다', async () => {
    let { clock, count } = setup('07:20:00.000', {
      [R.board]: [() => json(200, boardBody({ progress: 3, started: true, status: 'SUCCEEDED' }))],
    });
    await renderApp('/quiz', clock);
    await until(() => loc() === '/done');
    expect(unload()).toBe(false);
    await flip('hidden');
    await flip('visible');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(count(R.failure)).toBe(0);
    cleanup();

    ({ clock, count } = setup('06:59:00.000', {}));
    await renderApp('/quiz', clock);
    await until(() => loc() === '/waiting');
    expect(unload()).toBe(false);
    expect(count(R.failure)).toBe(0);
  });
});

describe('완료 — 그룹 결과 (W29)', () => {
  it('O38 group.result 를 받으면 /quiz 에서 /done 의 대화상자 + 랭킹, 결과 보기 → /result', async () => {
    const enc = new TextEncoder();
    let ctrl!: ReadableStreamDefaultController<Uint8Array>;
    const stream: Handler = () =>
      new Response(new ReadableStream<Uint8Array>({ start: (c) => void (ctrl = c) }), {
        headers: { 'Content-Type': 'text/event-stream' },
      });
    const push = (event: string, data: object) =>
      ctrl.enqueue(enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
    const mine = { progress: 1, started: true, status: 'IN_PROGRESS' };
    const shown = recorder();
    store({ questionNo: 2, questionId: qid('3'), servedIds: [qid('1'), qid('3')] });
    const { clock } = setup('07:20:00.000', {
      [R.board]: [() => json(200, boardBody(mine)), () => json(200, boardBody(mine, 'FAILED'))],
      [R.shown]: [shown.handler],
      [R.events]: [stream],
    });
    await renderApp('/quiz', clock);
    await until(() => shown.bodies.length === 1 && ctrl !== undefined);
    const b = boardBody(mine);
    await act(async () => push('board.snapshot', { ...b, version: 2, sentAt: kst('07:20:00') }));
    await act(async () =>
      push('group.result', {
        ...b,
        version: 3,
        groupStatus: 'FAILED',
        decidedAt: kst('07:20:01'),
        rankingFinal: true,
      }),
    );
    await until(() => loc() === '/done');
    await until(() => screen.queryByRole('dialog', { name: '그룹 미션 실패' }) !== null);
    expect(screen.getByRole('region', { name: '그룹 내 랭킹' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '결과 보기' })).toHaveAttribute('href', '/result');
  });
});
