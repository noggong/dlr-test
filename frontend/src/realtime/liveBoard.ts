import { postTicket } from '@/api/endpoints';
import { ApiError, type ApiErrorCode } from '@/api/http';
import type { GroupStatus, MemberEntry } from './mapEntry';
import { openSse, type FetchLike, type SseFrame } from './sse';

// ── 이벤트(realtime-board SPEC §6 · asyncapi) ─────────────────────────────

export interface SnapshotData {
  version: number;
  date: string;
  groupStatus: GroupStatus;
  entries: MemberEntry[];
  sentAt: string;
}
export interface ProgressedData {
  version: number;
  date: string;
  groupStatus: 'IN_PROGRESS' | 'PENDING';
  userId: string;
  entries: MemberEntry[];
  occurredAt: string;
}
export interface PendingData {
  version: number;
  date: string;
  groupStatus: 'PENDING';
  occurredAt: string;
}
export interface ResultData {
  version: number;
  date: string;
  groupStatus: 'SUCCEEDED' | 'FAILED';
  decidedAt: string;
  rankingFinal: boolean;
  entries: MemberEntry[];
}

export type LiveEvent =
  | { type: 'board.snapshot'; data: SnapshotData }
  | { type: 'member.progressed'; data: ProgressedData }
  | { type: 'group.pending'; data: PendingData }
  | { type: 'group.result'; data: ResultData }
  | { type: 'group.ranking_final'; data: ResultData };

const EVENT_TYPES: ReadonlySet<string> = new Set<LiveEvent['type']>([
  'board.snapshot',
  'member.progressed',
  'group.pending',
  'group.result',
  'group.ranking_final',
]);

// ── 연결 상태 ────────────────────────────────────────────────────────────

/**
 * `connecting` 첫 연결 중 · `live` snapshot 을 받아 붙어 있음 · `retrying` 끊겨서 다시 붙는 중 ·
 * `stopped` 다시 붙지 않음(`code` 가 이유) · `ended` 끝난 스트림(RB8) — 둘 다 끝이다.
 */
export type ConnectionState = 'connecting' | 'live' | 'retrying' | 'stopped' | 'ended';
export interface Connection {
  state: ConnectionState;
  /** `stopped` 의 이유 — 화면은 이것으로만 가른다(I8). */
  code?: ApiErrorCode;
}

/** 45초(하트비트 3회) 동안 프레임이 없으면 끊긴 것으로 본다(RB9). */
export const WATCHDOG_MS = 45_000;
/** 재시도 대기 — 첫 재시도는 바로, 그 뒤 1 · 2 · 4 · 8 · 16 · 30초 상한(RB7 ③). */
export const BACKOFF_MS = [0, 1000, 2000, 4000, 8000, 16000, 30000] as const;
/** 첫 재시도가 아니면 대기에 0~1초 무작위를 더한다. */
export const JITTER_MS = 1000;
export const EVENTS_PATH = '/realtime/v1/games/today/events';

/** 티켓 · 연결이 이 code 면 다시 붙지 않는다(RB7 ④⑤ · W28). */
const STOP_CODES: ReadonlySet<ApiErrorCode> = new Set<ApiErrorCode>([
  'GAME_NOT_OPEN',
  'NOT_PARTICIPANT',
  'SOLO_GROUP',
  'INTERNAL_ERROR',
]);

export interface LiveBoardDeps {
  /** SSE 연결용 fetch. 기본은 그때의 전역 `fetch`. */
  fetch?: FetchLike;
  /** 티켓 발급. 기본 `postTicket` — 401 이면 refresh(single-flight) 뒤 한 번 더 보낸다(W18). */
  issueTicket?: (signal: AbortSignal) => Promise<{ ticket: string }>;
  setTimeout?: (fn: () => void, ms: number) => unknown;
  clearTimeout?: (handle: unknown) => void;
  /** 0 이상 1 미만. 대기 무작위(RB7 ③). */
  random?: () => number;
}

export interface LiveBoardHandlers {
  /** version 비교(RB6)를 거친 이벤트만 온다. */
  onEvent: (event: LiveEvent) => void;
  onConnection?: (connection: Connection) => void;
}

export interface LiveBoard {
  start: () => void;
  /** 연결 · 대기 · 감시 타이머를 모두 거둔다. 다시 `start` 할 수 없다. */
  stop: () => void;
  readonly connection: Connection;
}

type Outcome = { kind: 'retry' } | { kind: 'stop'; code: ApiErrorCode } | { kind: 'ended' };

/** 재시도 `attempt`(0부터) 앞의 대기(ms). */
export function backoffDelay(attempt: number, random: () => number): number {
  if (attempt <= 0) return 0;
  const base = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
  return base + Math.floor(random() * JITTER_MS);
}

/** 실시간 현황판 연결 관리자 — 티켓 → SSE → RB6 version 비교 → RB7 재연결 → RB8 끝. */
export function createLiveBoard(handlers: LiveBoardHandlers, deps: LiveBoardDeps = {}): LiveBoard {
  const fetchImpl = deps.fetch;
  const issueTicket = deps.issueTicket ?? ((signal: AbortSignal) => postTicket(signal));
  const setTimer = deps.setTimeout ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer =
    deps.clearTimeout ?? ((h: unknown) => clearTimeout(h as ReturnType<typeof setTimeout>));
  const random = deps.random ?? Math.random;

  let connection: Connection = { state: 'connecting' };
  let started = false;
  let disposed = false;
  let attempt = 0; // 다음 재시도의 대기 단계 — snapshot 을 받으면 0(RB7 ⑥)
  let current: AbortController | undefined;
  let waitTimer: unknown;
  let wakeWait: (() => void) | undefined;

  function setConnection(next: Connection) {
    if (disposed) return;
    if (next.state === connection.state && next.code === connection.code) return;
    connection = next;
    handlers.onConnection?.(next);
  }

  function wait(ms: number): Promise<void> {
    if (ms <= 0) return Promise.resolve(); // 첫 재시도는 바로 — 타이머를 거치지 않는다
    return new Promise((resolve) => {
      wakeWait = resolve;
      waitTimer = setTimer(() => {
        waitTimer = undefined;
        wakeWait = undefined;
        resolve();
      }, ms);
    });
  }

  async function run() {
    while (!disposed) {
      const outcome = await connectOnce();
      if (disposed) return;
      if (outcome.kind === 'ended') {
        setConnection({ state: 'ended' });
        return;
      }
      if (outcome.kind === 'stop') {
        setConnection({ state: 'stopped', code: outcome.code });
        return;
      }
      setConnection({ state: 'retrying' });
      await wait(backoffDelay(attempt, random));
      attempt++;
    }
  }

  /** 티켓 한 장 = 연결 하나(RB4). 끝나면 다음에 할 일을 돌려준다. */
  async function connectOnce(): Promise<Outcome> {
    const controller = new AbortController();
    current = controller;
    let watchdog: unknown;
    // 티켓 · 연결 요청이 걸려 멈춰도 45초 뒤 끊고 다시 한다
    const disarm = () => {
      if (watchdog !== undefined) clearTimer(watchdog);
      watchdog = undefined;
    };
    const arm = () => {
      disarm();
      if (controller.signal.aborted) return;
      watchdog = setTimer(() => {
        watchdog = undefined;
        controller.abort(new DOMException('no frame for 45s', 'TimeoutError'));
      }, WATCHDOG_MS);
    };
    let lastVersion: number | undefined; // 연결마다 새로(RB6)
    let finished = false; // RB8 — 이 연결에서 끝난 스트림의 표시를 받았다

    const onFrame = (frame: SseFrame) => {
      arm();
      if (!EVENT_TYPES.has(frame.event)) return;
      const data = JSON.parse(frame.data) as LiveEvent['data'];
      const type = frame.event as LiveEvent['type'];
      if (type === 'board.snapshot') {
        lastVersion = data.version;
        attempt = 0;
        const status = (data as SnapshotData).groupStatus;
        if (status === 'SUCCEEDED' || status === 'FAILED') finished = true;
        setConnection({ state: 'live' });
      } else {
        // snapshot 전에 온 이벤트(RB5 상 오지 않는다)도 기준이 없으니 버린다
        if (lastVersion === undefined || data.version <= lastVersion) return;
        lastVersion = data.version;
        if ((data as Partial<ResultData>).rankingFinal === true) finished = true;
      }
      handlers.onEvent({ type, data } as LiveEvent);
    };

    // stop() 이 abort 하는 그 순간 감시 타이머도 걷는다(읽기가 끝나기를 기다리지 않고)
    controller.signal.addEventListener('abort', disarm, { once: true });
    arm();
    try {
      let ticket: string;
      try {
        ticket = (await issueTicket(controller.signal)).ticket;
      } catch (e) {
        return classify(e, controller);
      }
      try {
        const url = `${EVENTS_PATH}?ticket=${encodeURIComponent(ticket)}`;
        const stream = await openSse(url, controller.signal, fetchImpl);
        arm();
        await stream.read({ onFrame, onComment: arm });
      } catch (e) {
        // 끝난 스트림의 표시를 받은 뒤라면 어떻게 끊겼든 다시 붙지 않는다(RB7 ②)
        if (finished) return { kind: 'ended' };
        return classify(e, controller);
      }
      // 서버의 정상 종료 — rankingFinal 없이 끝났으면 끝난 스트림이 아니다(RB8)
      return finished ? { kind: 'ended' } : { kind: 'retry' };
    } finally {
      disarm();
      controller.abort();
      if (current === controller) current = undefined;
    }
  }

  function classify(e: unknown, controller: AbortController): Outcome {
    // 감시 타이머 · stop 의 abort, 네트워크 오류, 503 · 티켓 무효 · 그 밖 → 대기부터
    if (controller.signal.aborted || !(e instanceof ApiError)) return { kind: 'retry' };
    if (STOP_CODES.has(e.code)) return { kind: 'stop', code: e.code };
    // refresh 뒤에도 401 — 세션이 죽었다(onUnauthenticated 가 로그인으로 보낸다)
    if (e.code === 'UNAUTHORIZED') return { kind: 'stop', code: e.code };
    return { kind: 'retry' };
  }

  return {
    start() {
      if (started || disposed) return;
      started = true;
      handlers.onConnection?.(connection);
      void run();
    },
    stop() {
      if (disposed) return;
      disposed = true;
      current?.abort();
      if (waitTimer !== undefined) clearTimer(waitTimer);
      waitTimer = undefined;
      wakeWait?.();
      wakeWait = undefined;
    },
    get connection() {
      return connection;
    },
  };
}
