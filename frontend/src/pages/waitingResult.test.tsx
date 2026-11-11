import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ServerClock } from '@/app/clock';
import { err, json, kst, mockFetch, renderApp, todayBody, type Handler } from '@/test/app';

// 대기 · 결과 · 1명 지면(T-WC-5) — ORACLE O34 ~ O37. 기기 시계를 서버보다 틀리게 두고 가짜 타이머로 민다.

const HOUR = 60 * 60 * 1000;
const ME = 'GET /api/v1/auth/me';
const TODAY = 'GET /api/v1/games/today';
const RESULT = 'GET /api/v1/games/results/latest';
const TICKET = 'POST /api/v1/realtime/tickets';
const EVENTS = 'GET /realtime/v1/games/today/events?ticket=t';
const loc = () => screen.getByTestId('loc').textContent;
const tick = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));
/** 응답 → 렌더 → 효과가 다음 타이머를 거는 데까지(act 한 번에 한 단계씩). */
async function settle() {
  for (let i = 0; i < 4; i++) await tick();
}
/** ms 를 step 씩 나눠 민다 — 효과가 거는 다음 타이머가 그 사이에 걸리게. */
async function walk(ms: number, step = 500) {
  for (let t = 0; t < ms; t += step) await tick(Math.min(step, ms - t));
}

/** 기기 시계 = 서버 + skew. 단조 시계도 기기 것(가짜 타이머로 흐른다). */
function deviceClock(serverTime: string, skew: number): ServerClock {
  vi.setSystemTime(Date.parse(serverTime) + skew);
  return new ServerClock(() => Date.now());
}

const member = (userId: string, rank: number, status: string, progress = 3) => ({
  userId,
  rank,
  progress,
  status,
  started: status !== 'FAILED' || progress > 0,
  lastSolvedAt: progress > 0 ? '2026-11-12T07:30:00.000000+09:00' : null,
  passedAtQuestionNo: null,
});

function resultBody(over: Record<string, unknown> = {}) {
  return {
    date: '2026-11-12',
    groupStatus: 'SUCCEEDED',
    decidedAt: '2026-11-12T07:44:10.000000+09:00',
    rankingFinal: true,
    myRank: 2,
    ranking: [
      member('dohyun', 1, 'SUCCEEDED'),
      member('jiwoo_k', 2, 'SUCCEEDED'),
      member('minseo01', 3, 'SUCCEEDED'),
    ],
    nextGameStartAt: '2026-11-13T07:00:00+09:00',
    ...over,
  };
}
const failed = (rankingFinal: boolean) =>
  resultBody({
    groupStatus: 'FAILED',
    decidedAt: '2026-11-12T08:00:00.000400+09:00',
    rankingFinal,
    ranking: [
      member('dohyun', 1, 'SUCCEEDED'),
      member('jiwoo_k', 2, 'FAILED', 2),
      member('minseo01', 3, 'FAILED', 0),
    ],
  });
const pending = resultBody({
  groupStatus: 'PENDING',
  decidedAt: null,
  rankingFinal: false,
  myRank: null,
  ranking: [],
});

/** SSE 스트림 — push 로 이벤트, close 로 서버 종료. */
function sse() {
  const enc = new TextEncoder();
  let ctrl!: ReadableStreamDefaultController<Uint8Array>;
  const handler: Handler = () =>
    new Response(new ReadableStream<Uint8Array>({ start: (c) => void (ctrl = c) }), {
      headers: { 'Content-Type': 'text/event-stream' },
    });
  return {
    handler,
    push: (event: string, data: object) =>
      ctrl.enqueue(enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)),
    close: () => ctrl.close(),
  };
}

/** 서버가 주는 게임 날짜 — 08:00 뒤면 다음 날(game-session GS1). */
const gameDay = (serverTime: string) =>
  serverTime.slice(11, 16) >= '08:00' ? '2026-11-13' : '2026-11-12';

function routes(serverTime: string, extra: Record<string, Handler[]>, members?: string[]) {
  return mockFetch({
    [ME]: [() => json(200, { userId: 'jiwoo_k', groupName: '아침수영' })],
    [TODAY]: [() => json(200, todayBody(serverTime, members, gameDay(serverTime)))],
    ...extra,
  });
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

describe('대기 — 카운트다운 · 07:00 전환 (W30)', () => {
  for (const skew of [3 * HOUR, -3 * HOUR]) {
    it(`O34 서버 06:55:00 · 기기 시계 ${skew > 0 ? '+' : '−'}3시간 → 「5분」, 5분 뒤 /quiz`, async () => {
      const serverTime = kst('06:55:00.000');
      const clock = deviceClock(serverTime, skew);
      routes(serverTime, { [RESULT]: [() => err(404, 'NO_RESULT_YET')] });
      await renderApp('/waiting', clock);
      await tick();
      expect(loc()).toBe('/waiting');
      expect(screen.getByRole('heading', { name: '5분 후 게임 시작' })).toBeInTheDocument();
      expect(screen.getByRole('timer', { name: '게임 시작까지 05:00' })).toBeInTheDocument();
      expect(screen.getByText('06:55')).toBeInTheDocument();

      await tick(4 * 60 * 1000);
      expect(screen.getByRole('timer', { name: '게임 시작까지 01:00' })).toBeInTheDocument();
      await tick(59 * 1000);
      expect(loc()).toBe('/waiting');
      await tick(1000);
      expect(loc()).toBe('/quiz');
    });
  }
});

describe('대기 — 어제 결과 (W11 · §6.3)', () => {
  it('O35 results/latest 200(어제 성공) → 어제 결과 카드(yday)', async () => {
    const serverTime = kst('06:55:00.000');
    const clock = deviceClock(serverTime, HOUR);
    routes(serverTime, { [RESULT]: [() => json(200, resultBody({ date: '2026-11-11' }))] });
    await renderApp('/waiting', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '어제 결과' })).toBeInTheDocument();
    expect(screen.getByText('그룹 미션 성공')).toBeInTheDocument();
    expect(screen.getByText('내 순위 2위 / 3명 · 07:44:10 완료')).toBeInTheDocument();
    expect(screen.getByText('jiwoo_k · 나')).toBeInTheDocument();
  });

  it('O35 NO_RESULT_YET → 어제 결과 카드 없음(noyday)', async () => {
    const serverTime = kst('02:10:00.000');
    const clock = deviceClock(serverTime, HOUR);
    routes(serverTime, { [RESULT]: [() => err(404, 'NO_RESULT_YET')] });
    await renderApp('/waiting', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '4시간 50분 후 게임 시작' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /지금 그룹원 3명/ })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /결과/ })).toBeNull();
  });

  it('O35 지난 결과가 어제보다 앞이면 날짜로 적는다 · 그날 그룹원이 아니었으면(myRank null) 그 문구', async () => {
    const serverTime = kst('06:00:00.000');
    const clock = deviceClock(serverTime, 0);
    routes(serverTime, {
      [RESULT]: [() => json(200, { ...failed(true), date: '2026-11-09', myRank: null })],
    });
    await renderApp('/waiting', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '11월 9일 결과' })).toBeInTheDocument();
    expect(screen.getByText('그날은 참가하지 않았어요 · 그룹원 3명')).toBeInTheDocument();
  });
});

describe('결과 — PENDING · 랭킹 확정 (api-guide §2 · W29)', () => {
  it('O36 PENDING → 로딩, SSE group.result 를 받으면 다시 읽어 성공 화면', async () => {
    const serverTime = kst('08:00:01.000');
    const clock = deviceClock(serverTime, -3 * HOUR);
    const stream = sse();
    const { count } = routes(serverTime, {
      [RESULT]: [() => json(200, pending), () => json(200, resultBody())],
      [TICKET]: [() => json(201, { ticket: 't' })],
      [EVENTS]: [stream.handler],
    });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '결과 확정 중' })).toBeInTheDocument();
    expect(screen.getAllByRole('status', { name: '불러오는 중' }).length).toBeGreaterThan(0);
    expect(count('GET /api/v1/games/today/board')).toBe(0); // 08:00 뒤 현황판 조회 안 함(RB13)

    stream.push('board.snapshot', { version: 5, groupStatus: 'PENDING', entries: [] });
    stream.push('group.result', {
      version: 6,
      groupStatus: 'SUCCEEDED',
      decidedAt: '2026-11-12T08:00:01.200000+09:00',
      rankingFinal: true,
      entries: [],
    });
    await tick();
    expect(screen.getByRole('heading', { name: '그룹 미션 성공' })).toBeInTheDocument();
    expect(count(RESULT)).toBe(2);
    const board = screen.getByRole('region', { name: '그룹 내 랭킹' });
    const ids = within(board)
      .getAllByRole('listitem')
      .map((li) => ['dohyun', 'jiwoo_k', 'minseo01'].find((id) => li.textContent?.includes(id)));
    expect(ids).toEqual(['dohyun', 'jiwoo_k', 'minseo01']); // 받은 순서 그대로
    expect(within(board).getAllByRole('listitem')[1]).toHaveAttribute('aria-current', 'true');
    expect(screen.queryByText('랭킹 확정 중')).toBeNull();
  });

  it('O36 PENDING(08:00:03 뒤 · 연결 없음) → 3초 뒤 다시 읽어 실패 화면 · 못 푼 그룹원도 「실패」(W10)', async () => {
    const serverTime = kst('08:00:05.000');
    const clock = deviceClock(serverTime, 3 * HOUR);
    const { count } = routes(serverTime, {
      [RESULT]: [() => json(200, pending), () => json(200, failed(true))],
    });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '결과 확정 중' })).toBeInTheDocument();
    expect(count(TICKET)).toBe(0);
    await walk(3000);
    await settle();
    expect(screen.getByRole('heading', { name: '그룹 미션 실패' })).toBeInTheDocument();
    expect(
      screen.getByText('08:00까지 그룹원 모두가 3문제를 끝내지 못했어요.'),
    ).toBeInTheDocument();
    const rows = within(screen.getByRole('region', { name: '그룹 내 랭킹' })).getAllByRole(
      'listitem',
    );
    expect(rows.map((li) => li.textContent?.endsWith('실패'))).toEqual([false, true, true]);
    await walk(30_000, 3000);
    expect(count(RESULT)).toBe(2); // 확정되면 더 읽지 않는다
  });

  it('O36 PENDING 이 계속되면 3초 간격으로 읽다가 상한에서 멈추고 다시 시도', async () => {
    const serverTime = kst('08:30:00.000');
    const clock = deviceClock(serverTime, 0);
    const { count } = routes(serverTime, { [RESULT]: [() => json(200, pending)] });
    await renderApp('/result', clock);
    await tick();
    await walk(3000 * 45);
    expect(count(RESULT)).toBe(41);
    expect(screen.getByRole('alert')).toHaveTextContent('잠시 뒤 다시 열어 주세요');
    await walk(60_000, 3000);
    expect(count(RESULT)).toBe(41);
  });

  it('O36 rankingFinal 거짓 → 「랭킹 확정 중」, 연결이 ranking_final 없이 끝나면 다시 읽고, 08:00:03 뒤 한 번 더', async () => {
    const serverTime = kst('08:00:00.500');
    const clock = deviceClock(serverTime, 3 * HOUR);
    const stream = sse();
    const { count } = routes(serverTime, {
      [RESULT]: [
        () => json(200, failed(false)),
        () => json(200, failed(false)),
        () => json(200, failed(true)),
      ],
      [TICKET]: [() => json(201, { ticket: 't' })],
      [EVENTS]: [stream.handler],
    });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '그룹 미션 실패' })).toBeInTheDocument();
    expect(screen.getByText('랭킹 확정 중')).toBeInTheDocument();
    expect(count(RESULT)).toBe(1);

    // 확정 그룹의 snapshot 뒤 스트림 끝(RB8 — ended) · ranking_final 은 못 받음
    stream.push('board.snapshot', { version: 9, groupStatus: 'FAILED', entries: [] });
    stream.close();
    await settle();
    expect(count(RESULT)).toBe(2);
    expect(screen.getByText('랭킹 확정 중')).toBeInTheDocument();

    // 서버 08:00:03 전에는 더 읽지 않는다
    await walk(2000);
    expect(count(RESULT)).toBe(2);
    await walk(1500);
    await settle();
    expect(count(RESULT)).toBe(3);
    expect(screen.queryByText('랭킹 확정 중')).toBeNull();
    await walk(60_000, 3000);
    expect(count(RESULT)).toBe(3);
  });

  it('O36 08:00:03 뒤에도 rankingFinal 이 거짓이면 한 번만 더 읽고 멈춘다(무한 재조회 없음)', async () => {
    const serverTime = kst('08:00:10.000');
    const clock = deviceClock(serverTime, 0);
    const { count } = routes(serverTime, { [RESULT]: [() => json(200, failed(false))] });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByText('랭킹 확정 중')).toBeInTheDocument();
    await walk(3000);
    await settle();
    expect(count(RESULT)).toBe(2);
    await walk(120_000, 3000);
    expect(count(RESULT)).toBe(2);
    expect(count(TICKET)).toBe(0);
  });

  it('O36 myRank null(그날 07:00 뒤 가입)이면 「그날은 참가하지 않았어요」', async () => {
    const serverTime = kst('09:00:00.000');
    const clock = deviceClock(serverTime, 0);
    routes(serverTime, { [RESULT]: [() => json(200, resultBody({ myRank: null }))] });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByText('그날은 참가하지 않았어요')).toBeInTheDocument();
  });

  it('O36 결과 화면에서 다음 게임 07:00(서버 시각)이 오면 games/today 를 다시 받아 첫 화면 규칙으로', async () => {
    const serverTime = kst('06:59:58.000');
    const clock = deviceClock(serverTime, 3 * HOUR);
    const { count } = mockFetch({
      [ME]: [() => json(200, { userId: 'jiwoo_k', groupName: '아침수영' })],
      [TODAY]: [
        () => json(200, todayBody(serverTime)),
        () => json(200, todayBody(kst('07:00:00.100'))),
      ],
      [RESULT]: [
        () => json(200, resultBody({ date: '2026-11-11', nextGameStartAt: kst('07:00:00') })),
      ],
    });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByRole('timer', { name: '다음 게임까지 00:00:02' })).toBeInTheDocument();
    await walk(2000);
    await settle();
    expect(count(TODAY)).toBe(2);
    expect(loc()).toBe('/quiz');
  });

  it('O36 NO_RESULT_YET → 결과 없이 다음 게임까지', async () => {
    const serverTime = kst('09:30:00.000');
    const clock = deviceClock(serverTime, 0);
    routes(serverTime, { [RESULT]: [() => err(404, 'NO_RESULT_YET')] });
    await renderApp('/result', clock);
    await tick();
    expect(screen.getByRole('heading', { name: '아직 결과가 없어요' })).toBeInTheDocument();
    expect(screen.getByRole('timer', { name: '다음 게임까지 21:30:00' })).toBeInTheDocument();
  });
});

describe('결과 — 08:00 뒤 games/today 는 다음 날(GS1)', () => {
  it('O36 / 는 08:00 뒤(today 가 다음 날)이면 /result — 대기로 가지 않는다', async () => {
    const serverTime = kst('09:00:00.000');
    const clock = deviceClock(serverTime, -3 * HOUR);
    routes(serverTime, { [RESULT]: [() => json(200, failed(true))] });
    await renderApp('/', clock);
    await settle();
    expect(loc()).toBe('/result');
    expect(screen.getByRole('heading', { name: '그룹 미션 실패' })).toBeInTheDocument();
    expect(screen.getByText('내일 07:00 시작')).toBeInTheDocument();
  });

  it('O36 다음 게임 전환에서 games/today 가 실패하면 지면에 둔 채 5초 · 10초 뒤 다시, 받으면 이동', async () => {
    const serverTime = kst('06:59:59.000');
    const clock = deviceClock(serverTime, 0);
    const { count } = mockFetch({
      [ME]: [() => json(200, { userId: 'jiwoo_k', groupName: '아침수영' })],
      [TODAY]: [
        () => json(200, todayBody(serverTime)),
        () => err(503, 'UNAVAILABLE'),
        () => err(503, 'UNAVAILABLE'),
        () => json(200, todayBody(kst('07:00:15.000'))),
      ],
      [RESULT]: [
        () => json(200, resultBody({ date: '2026-11-11', nextGameStartAt: kst('07:00:00') })),
      ],
    });
    await renderApp('/result', clock);
    await settle();
    await walk(1000);
    await settle();
    expect(count(TODAY)).toBe(2);
    expect(loc()).toBe('/result');
    await walk(4000);
    await settle();
    expect(count(TODAY)).toBe(2);
    await walk(1500);
    await settle();
    expect(count(TODAY)).toBe(3);
    expect(loc()).toBe('/result');
    await walk(9000);
    await settle();
    expect(count(TODAY)).toBe(3);
    await walk(1500);
    await settle();
    expect(count(TODAY)).toBe(4);
    expect(loc()).toBe('/quiz');
  });
});

describe('1명 그룹 (§6.3)', () => {
  it('O37 games/today 그룹원 1명 → /solo, 다음 게임까지(서버 기준)', async () => {
    const serverTime = kst('07:04:00.000');
    const clock = deviceClock(serverTime, -3 * HOUR);
    routes(serverTime, {}, ['jiwoo_k']);
    await renderApp('/', clock);
    await tick();
    expect(loc()).toBe('/solo');
    expect(
      screen.getByRole('heading', { name: '그룹원이 2명 이상이어야 미션을 시작할 수 있어요' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('timer', { name: '다음 게임까지 23:56:00' })).toBeInTheDocument();
    expect(screen.getByText('내일 07:00 시작')).toBeInTheDocument();
  });

  it('O37 대기 지면에서 그룹원 1명이면 /solo', async () => {
    const serverTime = kst('05:00:00.000');
    const clock = deviceClock(serverTime, 0);
    routes(serverTime, { [RESULT]: [() => err(404, 'NO_RESULT_YET')] }, ['jiwoo_k']);
    await renderApp('/waiting', clock);
    await tick();
    expect(loc()).toBe('/solo');
    expect(screen.getByRole('timer', { name: '다음 게임까지 02:00:00' })).toBeInTheDocument();
    expect(screen.getByText('오늘 07:00 시작')).toBeInTheDocument();
  });

  it('O37 08:00 뒤 1명 안내는 「내일 07:00 시작」 — 07:00 전 문구를 쓰지 않는다', async () => {
    const serverTime = kst('09:00:00.000');
    const clock = deviceClock(serverTime, 0);
    routes(serverTime, {}, ['jiwoo_k']);
    await renderApp('/solo', clock);
    await tick();
    expect(screen.getByRole('timer', { name: '다음 게임까지 22:00:00' })).toBeInTheDocument();
    expect(screen.getByText('내일 07:00 시작')).toBeInTheDocument();
    expect(screen.getByText(/오늘 07:00 기준으로/)).toBeInTheDocument();
  });

  for (const path of ['/waiting', '/result']) {
    it(`O37 ${path} 에서 results/latest 가 SOLO_GROUP → /solo`, async () => {
      const serverTime = kst(path === '/waiting' ? '06:00:00.000' : '09:00:00.000');
      const clock = deviceClock(serverTime, 0);
      routes(serverTime, { [RESULT]: [() => err(409, 'SOLO_GROUP')] });
      await renderApp(path, clock);
      await tick();
      expect(loc()).toBe('/solo');
    });
  }
});
