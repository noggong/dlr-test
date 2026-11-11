import { refreshOnce } from './refresh';
import type { components } from './schema';

type Session = components['schemas']['SessionResponse'];

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
/** 서버 오류 코드(api-guide §4) 또는 본문이 JSON 이 아닐 때의 `HTTP_<상태>`(SPEC W16). */
export type ApiErrorCode = components['schemas']['ErrorCode'] | `HTTP_${number}`;

export interface RequestOptions {
  body?: unknown;
  signal?: AbortSignal;
  /** 신호 API(진입 · 풀이 · 패스 · 실패)만 — 네트워크 오류 · 503 에 같은 본문으로 다시 보낸다(SPEC W17). */
  idempotent?: boolean;
}

/** 2xx 가 아닌 응답. 화면 분기는 `code` 로만 한다(W16 · ORACLE I8). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode;

  constructor(status: number, code: ApiErrorCode, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

/** 응답을 받지 못함(연결 실패 · 끊김). */
export class NetworkError extends Error {
  readonly cause: unknown;

  constructor(cause: unknown) {
    super('network error');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

const AUTH_PREFIX = '/api/v1/auth/';
const REFRESH_PATH = '/api/v1/auth/refresh';
/** 재시도 대기(ms) — 1 · 2 · 4초 뒤 최대 3번 더(W17). */
export const RETRY_DELAYS_MS = [1000, 2000, 4000] as const;

let onUnauthenticated: (() => void) | undefined;

/** refresh 마저 401 일 때 한 번 불린다(→ `/login`). */
export function setOnUnauthenticated(handler: (() => void) | undefined): void {
  onUnauthenticated = handler;
}

/** `POST /auth/refresh` — 동시에 불러도 서버에는 한 번만 간다(AS12). */
export function refreshSession(): Promise<Session> {
  return refreshOnce(async () => {
    try {
      return await send<Session>('POST', REFRESH_PATH, undefined, undefined);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) onUnauthenticated?.();
      throw e;
    }
  });
}

export async function request<T>(
  method: HttpMethod,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  // 본문은 한 번만 만든다 — 재시도 · refresh 뒤 다시 보낼 때도 같은 바이트(W17).
  const body = options.body === undefined ? undefined : JSON.stringify(options.body);
  try {
    return await sendWithRetry<T>(method, path, body, options);
  } catch (e) {
    if (!isRefreshable(e, path)) throw e;
    try {
      await refreshSession();
    } catch (refreshError) {
      // refresh 401 이면 원 오류로 끝낸다(LLD §3 ②). 그 밖의 실패는 그 오류를 그대로.
      if (refreshError instanceof ApiError && refreshError.status === 401) throw e;
      throw refreshError;
    }
    return sendWithRetry<T>(method, path, body, options); // 한 번만 다시 — 또 401 이면 그대로 던진다
  }
}

function isRefreshable(e: unknown, path: string): boolean {
  return (
    e instanceof ApiError &&
    e.status === 401 &&
    e.code === 'UNAUTHORIZED' &&
    !path.startsWith(AUTH_PREFIX)
  );
}

async function sendWithRetry<T>(
  method: HttpMethod,
  path: string,
  body: string | undefined,
  options: RequestOptions,
): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await send<T>(method, path, body, options.signal);
    } catch (e) {
      const retryable =
        options.idempotent === true &&
        (e instanceof NetworkError || (e instanceof ApiError && e.status === 503));
      if (!retryable || attempt >= RETRY_DELAYS_MS.length) throw e;
      await sleep(RETRY_DELAYS_MS[attempt], options.signal);
    }
  }
}

async function send<T>(
  method: HttpMethod,
  path: string,
  body: string | undefined,
  signal: AbortSignal | undefined,
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  let text: string;
  try {
    res = await fetch(path, { method, credentials: 'same-origin', headers, body, signal });
    text = res.status === 204 ? '' : await res.text();
  } catch (e) {
    if (isAbort(e)) throw e;
    throw new NetworkError(e);
  }
  if (res.ok) return (text === '' ? undefined : JSON.parse(text)) as T;
  throw toApiError(res, text);
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

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

function sleep(ms: number, signal: AbortSignal | undefined): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}
