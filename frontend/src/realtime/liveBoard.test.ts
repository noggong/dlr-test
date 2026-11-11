import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Connection, LiveBoard, LiveEvent } from './liveBoard';

// http 는 모듈 상태(진행 중인 refresh · 죽은 세션)를 가진다 — 테스트마다 새로 불러온다.
type LiveBoardModule = typeof import('./liveBoard');
let createLiveBoard: LiveBoardModule['createLiveBoard'];

const TICKETS = '/api/v1/realtime/tickets';
const REFRESH = '/api/v1/auth/refresh';
const EVENTS = '/realtime/v1/games/today/events';

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const err = (status: number, code: string) => () => json(status, { code, message: '' });

/** 서버가 열어 둔 SSE 스트림 — 테스트가 프레임을 밀어 넣고 끝낸다. */
interface FakeStream {
  push: (text: string) => void;
  end: () => void;
  fail: () => void;
  signal: AbortSignal;
  closed: boolean;
}

let ticketReplies: Array<(signal: AbortSignal) => Response | Promise<Response>>;
let eventReplies: Array<() => Response>;
let streams: FakeStream[];
let ticketCalls: number;
let refreshCalls: number;
let eventUrls: string[];

function openStream(signal: AbortSignal): Response {
  const enc = new TextEncoder();
  let ctrl!: ReadableStreamDefaultController<Uint8Array>;
  const s: FakeStream = {
    push: (t) => ctrl.enqueue(enc.encode(t)),
    end: () => {
      s.closed = true;
      ctrl.close();
    },
    fail: () => {
      s.closed = true;
      ctrl.error(new TypeError('network'));
    },
    signal,
    closed: false,
  };
  const body = new ReadableStream<Uint8Array>({
    start: (c) => {
      ctrl = c;
    },
    cancel: () => {
      s.closed = true;
    },
  });
  streams.push(s);
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
  });
}

async function fakeFetch(input: string | URL | Request, init: RequestInit = {}) {
  const url = String(input);
  if (init.signal?.aborted) throw init.signal.reason;
  if (url === TICKETS) {
    ticketCalls++;
    const reply = ticketReplies.shift();
    return reply
      ? reply(init.signal as AbortSignal)
      : json(201, { ticket: `t${ticketCalls}`, expiresAt: '' });
  }
  if (url === REFRESH) {
    refreshCalls++;
    return json(200, { userId: 'minji' });
  }
  if (url.startsWith(EVENTS)) {
    eventUrls.push(url);
    const reply = eventReplies.shift();
    return reply ? reply() : openStream(init.signal as AbortSignal);
  }
  throw new Error(`unexpected ${url}`);
}

let events: LiveEvent[];
let states: Connection[];
let board: LiveBoard | undefined;

function startBoard() {
  board = createLiveBoard(
    { onEvent: (e) => events.push(e), onConnection: (c) => states.push(c) },
    { random: () => 0 },
  );
  board.start();
  return board;
}

// 끊김 → 분류 → 대기 → 새 티켓은 마이크로태스크 여러 겹이다 — 0초 타이머까지 몇 번 돌려 비운다
async function flush() {
  for (let i = 0; i < 5; i++) await vi.advanceTimersByTimeAsync(0);
}
const last = <T>(xs: T[]) => xs[xs.length - 1];
const stream = () => last(streams);

const frame = (event: string, data: object) =>
  `id: ${(data as { version: number }).version}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const snapshot = (version: number, groupStatus = 'IN_PROGRESS') =>
  frame('board.snapshot', { version, date: '2026-11-12', groupStatus, entries: [], sentAt: '' });
const progressed = (version: number) =>
  frame('member.progressed', {
    version,
    date: '2026-11-12',
    groupStatus: 'IN_PROGRESS',
    userId: 'dohyun',
    entries: [],
    occurredAt: '',
  });
const result = (event: 'group.result' | 'group.ranking_final', version: number, final: boolean) =>
  frame(event, {
    version,
    date: '2026-11-12',
    groupStatus: 'FAILED',
    decidedAt: '',
    rankingFinal: final,
    entries: [],
  });

beforeEach(async () => {
  vi.useFakeTimers();
  vi.resetModules();
  ({ createLiveBoard } = await import('./liveBoard'));
  ticketReplies = [];
  eventReplies = [];
  streams = [];
  ticketCalls = 0;
  refreshCalls = 0;
  eventUrls = [];
  events = [];
  states = [];
  vi.stubGlobal('fetch', vi.fn(fakeFetch));
});

afterEach(() => {
  board?.stop();
  board = undefined;
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('liveBoard — version (RB6 · W27)', () => {
  it('O29 snapshot(v5) 뒤 v4 · v5 는 버리고 v6 은 적용, 새 연결의 snapshot(v3)은 받는다', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(5) + progressed(4) + progressed(5) + progressed(6));
    await flush();
    expect(events.map((e) => [e.type, e.data.version])).toEqual([
      ['board.snapshot', 5],
      ['member.progressed', 6],
    ]);

    stream().end(); // rankingFinal 없이 끝 → 다시 붙는다
    await flush();
    expect(streams).toHaveLength(2);
    stream().push(snapshot(3) + progressed(3) + progressed(4));
    await flush();
    expect(events.slice(2).map((e) => [e.type, e.data.version])).toEqual([
      ['board.snapshot', 3],
      ['member.progressed', 4],
    ]);
  });

  it('O29 snapshot 전에 온 이벤트는 기준이 없어 버린다', async () => {
    startBoard();
    await flush();
    stream().push(progressed(9) + snapshot(5));
    await flush();
    expect(events.map((e) => e.type)).toEqual(['board.snapshot']);
  });
});

describe('liveBoard — 재연결 대기 (RB7 ③ · W28)', () => {
  /** 다음 티켓 요청이 정확히 ms 뒤에 가는지 본다. */
  async function expectNextTicketAfter(ms: number) {
    const before = ticketCalls;
    if (ms > 0) {
      await vi.advanceTimersByTimeAsync(ms - 1);
      expect(ticketCalls, `${ms}ms 전에 다시 보냄`).toBe(before);
      await vi.advanceTimersByTimeAsync(1);
    }
    await flush();
    expect(ticketCalls, `${ms}ms 뒤`).toBe(before + 1);
  }

  it('O30 연속 실패 대기 0 · 1 · 2 · 4 · 8 · 16 · 30 · 30초, snapshot 뒤 다시 0', async () => {
    eventReplies = Array.from({ length: 8 }, () => err(503, 'UNAVAILABLE'));
    startBoard();
    await flush();
    // 첫 연결이 실패하면 첫 재시도는 바로(0초) — 같은 순간에 두 번째 티켓
    expect(ticketCalls).toBe(2);
    expect(states.map((s) => s.state)).toEqual(['connecting', 'retrying']);
    for (const s of [1, 2, 4, 8, 16, 30, 30]) await expectNextTicketAfter(s * 1000);

    // 9번째는 붙는다 → snapshot → 끊김 → 대기가 처음(0)부터
    stream().push(snapshot(1));
    await flush();
    expect(board?.connection.state).toBe('live');
    stream().fail();
    await expectNextTicketAfter(0);
    stream().end();
    await expectNextTicketAfter(1000);
  });

  it('O30 대기에 0~1초 무작위를 더한다(첫 재시도는 바로)', async () => {
    const { backoffDelay } = await import('./liveBoard');
    expect(backoffDelay(0, () => 0.999)).toBe(0);
    expect(backoffDelay(1, () => 0.5)).toBe(1500);
    expect(backoffDelay(6, () => 0.999)).toBe(30999);
    expect(backoffDelay(20, () => 0)).toBe(30000);
  });

  it('O30 45초 동안 프레임이 없으면 끊고 새 티켓으로 다시 붙는다', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(1));
    await flush();
    const first = stream();
    await vi.advanceTimersByTimeAsync(44_999);
    expect(first.signal.aborted).toBe(false);
    expect(ticketCalls).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(first.signal.aborted).toBe(true);
    expect(ticketCalls).toBe(2); // snapshot 을 받았으니 대기 0
    expect(streams).toHaveLength(2);
  });

  it('O30 하트비트(: ping)가 오면 끊지 않는다', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(1));
    await flush();
    for (let i = 0; i < 8; i++) {
      await vi.advanceTimersByTimeAsync(15_000);
      stream().push(': ping\n\n');
      await flush();
    }
    expect(stream().signal.aborted).toBe(false);
    expect(ticketCalls).toBe(1);
    expect(board?.connection.state).toBe('live');
  });

  it('O30 티켓 요청이 응답 없이 걸려도 45초 뒤 끊고 다시 한다', async () => {
    let hung: AbortSignal | undefined;
    ticketReplies = [
      (signal) => {
        hung = signal;
        return new Promise<Response>((_, reject) =>
          signal.addEventListener('abort', () => reject(signal.reason)),
        );
      },
    ];
    startBoard();
    await vi.advanceTimersByTimeAsync(44_999);
    expect(ticketCalls).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(hung?.aborted).toBe(true);
    expect(ticketCalls).toBe(2);
  });
});

describe('liveBoard — 실패 종류 (RB7 ④⑤ · W28)', () => {
  it.each([
    ['UNAVAILABLE', 503],
    ['REALTIME_TICKET_INVALID', 401],
  ])('O31 연결 %s(%i) → 새 티켓으로 다시', async (code, status) => {
    eventReplies = [err(status, code)];
    startBoard();
    await flush();
    await flush();
    expect(ticketCalls).toBe(2);
    expect(eventUrls).toEqual([`${EVENTS}?ticket=t1`, `${EVENTS}?ticket=t2`]);
    expect(board?.connection.state).toBe('retrying');
  });

  it('O31 티켓 401 UNAUTHORIZED → refresh 뒤 한 번 더', async () => {
    ticketReplies = [err(401, 'UNAUTHORIZED')];
    startBoard();
    await flush();
    expect(refreshCalls).toBe(1);
    expect(ticketCalls).toBe(2);
    expect(eventUrls).toEqual([`${EVENTS}?ticket=t2`]);
    stream().push(snapshot(1));
    await flush();
    expect(states.map((s) => s.state)).toEqual(['connecting', 'live']);
  });

  it('O31 refresh 뒤에도 티켓 401 → stopped(UNAUTHORIZED)', async () => {
    ticketReplies = [err(401, 'UNAUTHORIZED'), err(401, 'UNAUTHORIZED')];
    startBoard();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(refreshCalls).toBe(1);
    expect(ticketCalls).toBe(2);
    expect(board?.connection).toEqual({ state: 'stopped', code: 'UNAUTHORIZED' });
  });

  it.each([
    ['GAME_NOT_OPEN', 409],
    ['NOT_PARTICIPANT', 403],
    ['SOLO_GROUP', 409],
    ['INTERNAL_ERROR', 500],
  ])('O31 티켓 %s(%i) → stopped, 다시 연결 안 함', async (code, status) => {
    ticketReplies = [err(status, code)];
    startBoard();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(ticketCalls).toBe(1);
    expect(eventUrls).toEqual([]);
    expect(board?.connection).toEqual({ state: 'stopped', code });
  });

  it.each([
    ['GAME_NOT_OPEN', 409],
    ['INTERNAL_ERROR', 500],
  ])('O31 연결 %s(%i) → stopped, 다시 연결 안 함', async (code, status) => {
    eventReplies = [err(status, code)];
    startBoard();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(ticketCalls).toBe(1);
    expect(board?.connection).toEqual({ state: 'stopped', code });
  });

  it('O31 티켓 503 · 네트워크 오류 · 스트림 도중 끊김 → 대기부터 다시', async () => {
    ticketReplies = [
      err(503, 'UNAVAILABLE'),
      () => {
        throw new TypeError('Failed to fetch');
      },
    ];
    startBoard();
    await flush(); // 503 → 바로
    await vi.advanceTimersByTimeAsync(1000); // 네트워크 오류 → 1초
    expect(ticketCalls).toBe(3);
    stream().push(snapshot(1));
    await flush();
    stream().fail();
    await flush();
    expect(ticketCalls).toBe(4);
  });
});

describe('liveBoard — 끝난 스트림 (RB8 · W28)', () => {
  it('O32 group.ranking_final 뒤 끝 → ended, 다시 연결 안 함', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(30, 'FAILED'));
    stream().push(result('group.ranking_final', 34, true));
    await flush();
    stream().end();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(events.map((e) => e.type)).toEqual(['board.snapshot', 'group.ranking_final']);
    expect(board?.connection.state).toBe('ended');
    expect(ticketCalls).toBe(1);
  });

  it('O32 group.result(rankingFinal true) 뒤 끊김(네트워크)도 ended', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(25) + result('group.result', 26, true));
    await flush();
    stream().fail();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(board?.connection.state).toBe('ended');
    expect(ticketCalls).toBe(1);
  });

  it('O32 확정 그룹의 snapshot 하나 뒤 끝 → ended', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(40, 'SUCCEEDED'));
    stream().end();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(board?.connection.state).toBe('ended');
    expect(ticketCalls).toBe(1);
  });

  it('O32 rankingFinal 없이 끝(잠정 group.result 뒤 포함) → 재연결', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(30, 'PENDING') + result('group.result', 31, false));
    await flush();
    stream().end();
    await flush();
    expect(ticketCalls).toBe(2);
    expect(board?.connection.state).toBe('retrying');
  });

  it('O32 stop() 은 연결 · 대기 · 감시 타이머를 모두 거둔다', async () => {
    startBoard();
    await flush();
    stream().push(snapshot(1));
    await flush();
    const s = stream();
    board?.stop();
    await flush();
    expect(s.signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(120_000);
    expect(ticketCalls).toBe(1);

    // 대기 중에 멈춰도 타이머가 남지 않는다
    eventReplies = [err(503, 'UNAVAILABLE'), err(503, 'UNAVAILABLE')];
    startBoard();
    await flush();
    await flush();
    expect(vi.getTimerCount()).toBe(1); // 1초 대기
    board?.stop();
    expect(vi.getTimerCount()).toBe(0);
  });
});
