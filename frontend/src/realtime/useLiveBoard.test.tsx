import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { getBoard } from '@/api/endpoints';
import type { MemberEntry } from './mapEntry';
import { useLiveBoard } from './useLiveBoard';

type Board = Awaited<ReturnType<typeof getBoard>>;

const entry = (userId: string, progress: number): MemberEntry => ({
  userId,
  rank: 1,
  progress,
  status: 'IN_PROGRESS',
  started: true,
  lastSolvedAt: null,
  passedAtQuestionNo: null,
});

interface Fake {
  push: (event: string, data: object) => void;
  signal: AbortSignal;
}
let streams: Fake[];
let boardReply: { resolve: (b: Board) => void };

function deps() {
  return {
    random: () => 0,
    issueTicket: async () => ({ ticket: 't' }),
    getBoard: () =>
      new Promise<Board>((resolve) => {
        boardReply = { resolve };
      }),
    fetch: async (_url: string, init: RequestInit) => {
      const enc = new TextEncoder();
      let ctrl!: ReadableStreamDefaultController<Uint8Array>;
      const body = new ReadableStream<Uint8Array>({ start: (c) => void (ctrl = c) });
      streams.push({
        push: (event, data) =>
          ctrl.enqueue(enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)),
        signal: init.signal as AbortSignal,
      });
      return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
    },
  };
}

const settle = () => act(() => vi.advanceTimersByTimeAsync(0));

beforeEach(() => {
  vi.useFakeTimers();
  streams = [];
});
afterEach(() => vi.useRealTimers());

describe('useLiveBoard (W26 · W29)', () => {
  it('O29 조회(v10)와 snapshot(v8) 중 큰 쪽을 남기고, 그보다 큰 이벤트부터 적용한다', async () => {
    const { result, unmount } = renderHook(() => useLiveBoard({ viewerId: 'minji', deps: deps() }));
    await settle();
    boardReply.resolve({
      date: '2026-11-12',
      groupStatus: 'IN_PROGRESS',
      version: 10,
      entries: [entry('junho', 2), entry('minji', 1)],
    });
    await settle();
    streams[0].push('board.snapshot', {
      version: 8,
      groupStatus: 'IN_PROGRESS',
      entries: [entry('junho', 1), entry('minji', 1)],
    });
    await settle();
    expect(result.current.version).toBe(10);
    expect(result.current.rows.map((r) => [r.id, r.progress, r.me ?? false])).toEqual([
      ['junho', 2, false],
      ['minji', 1, true],
    ]);
    expect(result.current.connection.state).toBe('live');

    streams[0].push('member.progressed', {
      version: 11,
      groupStatus: 'IN_PROGRESS',
      userId: 'minji',
      entries: [entry('minji', 2), entry('junho', 2)],
    });
    await settle();
    expect(result.current.version).toBe(11);
    expect(result.current.rows.map((r) => r.id)).toEqual(['minji', 'junho']);
    expect(result.current.lastEvent).toEqual({
      type: 'member.progressed',
      version: 11,
      userId: 'minji',
      progress: 2,
    });

    streams[0].push('group.pending', { version: 12, groupStatus: 'PENDING' });
    await settle();
    expect(result.current.groupStatus).toBe('PENDING');
    expect(result.current.rows).toHaveLength(2); // pending 은 현황판을 싣지 않는다

    unmount();
    expect(streams[0].signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('O29 snapshot 이 조회보다 새것이면 snapshot 을 남기고, 늦게 온 옛 조회는 버린다', async () => {
    const { result, unmount } = renderHook(() => useLiveBoard({ deps: deps() }));
    await settle();
    streams[0].push('board.snapshot', {
      version: 12,
      groupStatus: 'IN_PROGRESS',
      entries: [entry('junho', 3)],
    });
    await settle();
    boardReply.resolve({
      date: '2026-11-12',
      groupStatus: 'IN_PROGRESS',
      version: 10,
      entries: [entry('junho', 1)],
    });
    await settle();
    expect(result.current.version).toBe(12);
    expect(result.current.rows[0].progress).toBe(3);
    unmount();
  });
});
