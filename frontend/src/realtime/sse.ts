import { ApiError, NetworkError, type ApiErrorCode } from '@/api/http';

/** SSE 이벤트 한 개(빈 줄로 끝난 프레임). 하트비트 `: ping` 은 이벤트가 아니다. */
export interface SseFrame {
  event: string;
  id: string | undefined;
  data: string;
}

export interface SseHandlers {
  onFrame: (frame: SseFrame) => void;
  /** 주석 줄(`: ping` 등) — 하트비트(RB9). 인자는 `:` 뒤 글(앞 공백 하나 뺌). */
  onComment?: (text: string) => void;
}

/**
 * 문자 청크 → SSE 프레임(WHATWG 이벤트 스트림 해석의 필요한 만큼). 줄 끝은 `\n` · `\r\n` · `\r`,
 * 청크가 줄 중간 · `\r\n` 사이에서 끊겨도 같은 결과를 낸다. `retry:` 는 서버가 보내지 않으므로 무시한다.
 */
export class SseParser {
  private buffer = '';
  // 앞 청크가 `\r` 로 끝났다 — 다음 청크 첫 `\n` 은 같은 줄 끝이다
  private pendingCr = false;
  private event = '';
  private id: string | undefined;
  private data: string[] = [];
  private started = false;

  constructor(private readonly handlers: SseHandlers) {}

  feed(chunk: string): void {
    let text = chunk;
    if (!this.started && text.length > 0) {
      this.started = true;
      if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    }
    if (this.pendingCr && text.startsWith('\n')) text = text.slice(1);
    this.pendingCr = false;
    this.buffer += text;

    let start = 0;
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer.charCodeAt(i);
      if (c !== 10 && c !== 13) continue;
      const line = this.buffer.slice(start, i);
      if (c === 13) {
        if (i + 1 === this.buffer.length) this.pendingCr = true;
        else if (this.buffer.charCodeAt(i + 1) === 10) i++;
      }
      start = i + 1;
      this.line(line);
    }
    this.buffer = this.buffer.slice(start);
  }

  private line(line: string): void {
    if (line === '') {
      this.dispatch();
      return;
    }
    if (line.startsWith(':')) {
      this.handlers.onComment?.(stripSpace(line.slice(1)));
      return;
    }
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : stripSpace(line.slice(colon + 1));
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
    else if (field === 'id' && !value.includes('\0')) this.id = value;
  }

  private dispatch(): void {
    const { event, id, data } = this;
    this.event = '';
    this.id = undefined;
    this.data = [];
    if (data.length === 0) return; // data 없는 프레임은 올리지 않는다(EventSource 와 같다)
    this.handlers.onFrame({ event: event || 'message', id, data: data.join('\n') });
  }
}

function stripSpace(v: string): string {
  return v.startsWith(' ') ? v.slice(1) : v;
}

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export interface SseStream {
  /** 스트림이 끝날 때까지 읽는다. 서버가 정상 종료하면 resolve, 끊기면 reject, abort 면 `signal.reason` 으로 reject. */
  read: (handlers: SseHandlers) => Promise<void>;
}

/**
 * SSE 를 `fetch` + `ReadableStream` 으로 연다 — `EventSource` 는 연결 실패의 상태 · `code` 를 못 읽고
 * 1회용 티켓으로 자동 재연결해 401 이 되므로 쓰지 않는다(HLD §5 · W26). 2xx 가 아니면 본문을 `ApiError` 로 던진다.
 */
export async function openSse(
  url: string,
  signal: AbortSignal,
  fetchImpl: FetchLike = (input, init) => fetch(input, init),
): Promise<SseStream> {
  let res: Response;
  try {
    res = await fetchImpl(url, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { Accept: 'text/event-stream' },
      cache: 'no-store',
      signal,
    });
  } catch (e) {
    if (signal.aborted) throw signal.reason ?? e;
    throw new NetworkError(e);
  }
  if (!res.ok) {
    let text = '';
    try {
      text = await res.text();
    } catch (e) {
      if (signal.aborted) throw signal.reason ?? e;
    }
    throw toApiError(res, text);
  }
  const body = res.body;
  if (!body || !(res.headers.get('Content-Type') ?? '').startsWith('text/event-stream')) {
    // 2xx 인데 스트림이 아니다(경로 오타로 SPA index.html 등) — 오류로 다룬다
    void body?.cancel().catch(() => undefined);
    throw new ApiError(res.status, `HTTP_${res.status}`, res.statusText);
  }
  return { read: (handlers) => pump(body, signal, handlers) };
}

async function pump(
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal,
  handlers: SseHandlers,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser(handlers);
  // fetch 구현이 signal 을 본문 읽기에 이어 주지 않아도 abort 하면 읽기가 끝나게 한다
  const onAbort = () => void reader.cancel(signal.reason).catch(() => undefined);
  if (signal.aborted) onAbort();
  else signal.addEventListener('abort', onAbort, { once: true });
  let finished = false;
  try {
    for (;;) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } catch (e) {
        if (signal.aborted) throw signal.reason ?? e;
        throw new NetworkError(e);
      }
      if (signal.aborted) throw signal.reason;
      if (chunk.done) break;
      parser.feed(decoder.decode(chunk.value, { stream: true }));
    }
    finished = true;
    parser.feed(decoder.decode());
  } finally {
    signal.removeEventListener('abort', onAbort);
    // 처리기가 던져서 나가면 연결이 열린 채 남지 않게 닫는다
    if (!finished) void reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function toApiError(res: Response, text: string): ApiError {
  try {
    const parsed: unknown = JSON.parse(text);
    if (
      parsed &&
      typeof parsed === 'object' &&
      typeof (parsed as { code?: unknown }).code === 'string'
    ) {
      const { code, message } = parsed as { code: ApiErrorCode; message?: unknown };
      return new ApiError(res.status, code, typeof message === 'string' ? message : '');
    }
  } catch {
    // JSON 이 아니다(프록시 502 HTML 등) — 아래로
  }
  return new ApiError(res.status, `HTTP_${res.status}`, res.statusText);
}
