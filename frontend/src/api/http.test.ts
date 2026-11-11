import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getBoard, postShown, refresh } from './endpoints';
import { ApiError, NetworkError, request, setOnUnauthenticated } from './http';

type Handler = (url: string, init: RequestInit) => Promise<Response> | Response;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const unauthorized = () => json(401, { code: 'UNAUTHORIZED', message: 'login required' });
const unavailable = () => json(503, { code: 'UNAVAILABLE', message: 'try later' });

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

let fetchMock: ReturnType<typeof vi.fn<Handler>>;
const calls = (path: string) => fetchMock.mock.calls.filter(([url]) => url === path);

beforeEach(() => {
  fetchMock = vi.fn<Handler>();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  setOnUnauthenticated(undefined);
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('http — 오류 본문 (W16)', () => {
  it('O14 JSON 오류 본문은 ApiError{status, code, message}', async () => {
    fetchMock.mockResolvedValue(json(409, { code: 'GAME_NOT_OPEN', message: 'not open' }));
    const err = await request('GET', '/api/v1/games/today/board').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: 'GAME_NOT_OPEN', message: 'not open' });
  });

  it('O14 JSON 이 아닌 502 는 code HTTP_502', async () => {
    fetchMock.mockResolvedValue(
      new Response('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' }),
    );
    const err = await request('GET', '/api/v1/games/today').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 502, code: 'HTTP_502' });
  });

  it('O14 204 는 undefined', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(request('POST', '/api/v1/auth/logout')).resolves.toBeUndefined();
  });

  it('O14 2xx JSON 은 본문을 돌려주고 같은 출처 쿠키 · 상대 경로로 부른다', async () => {
    fetchMock.mockResolvedValue(json(200, { ok: 1 }));
    await expect(request('POST', '/api/v1/x', { body: { a: 1 } })).resolves.toEqual({ ok: 1 });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/x');
    expect(init).toMatchObject({ method: 'POST', credentials: 'same-origin', body: '{"a":1}' });
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('O14 fetch 가 실패하면 NetworkError', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(request('GET', '/api/v1/games/today')).rejects.toBeInstanceOf(NetworkError);
  });
});

describe('http — refresh single-flight (W18 · AS12)', () => {
  it('O15 동시 401 세 건에 refresh 한 번 · 원 요청은 각 두 번', async () => {
    const refreshGate = deferred<Response>();
    const authed = new Set<string>();
    let refreshed = false;
    fetchMock.mockImplementation((url) => {
      if (url === '/api/v1/auth/refresh') {
        return refreshGate.promise.then((r) => {
          refreshed = true;
          return r;
        });
      }
      if (!refreshed) return unauthorized();
      authed.add(url);
      return json(200, { url });
    });

    const paths = ['/api/v1/a', '/api/v1/b', '/api/v1/c'];
    const all = Promise.all(paths.map((p) => request('GET', p)));
    await vi.waitFor(() => expect(calls('/api/v1/auth/refresh')).toHaveLength(1));
    await new Promise((r) => setTimeout(r, 0)); // 세 건 모두 401 을 받고 refresh 를 기다리게 둔다
    refreshGate.resolve(json(200, {}));

    await expect(all).resolves.toEqual(paths.map((url) => ({ url })));
    expect(calls('/api/v1/auth/refresh')).toHaveLength(1);
    for (const p of paths) expect(calls(p)).toHaveLength(2);
  });

  it('O15 refresh 가 401 이면 onUnauthenticated 한 번 · 세 요청 모두 실패', async () => {
    const onUnauth = vi.fn();
    setOnUnauthenticated(onUnauth);
    const refreshGate = deferred<Response>();
    fetchMock.mockImplementation((url) =>
      url === '/api/v1/auth/refresh' ? refreshGate.promise : unauthorized(),
    );

    const paths = ['/api/v1/a', '/api/v1/b', '/api/v1/c'];
    const all = Promise.allSettled(paths.map((p) => request('GET', p)));
    await vi.waitFor(() => expect(calls('/api/v1/auth/refresh')).toHaveLength(1));
    await new Promise((r) => setTimeout(r, 0));
    refreshGate.resolve(unauthorized());

    const results = await all;
    for (const r of results) {
      expect(r.status).toBe('rejected');
      expect((r as PromiseRejectedResult).reason).toMatchObject({
        status: 401,
        code: 'UNAUTHORIZED',
      });
    }
    expect(onUnauth).toHaveBeenCalledTimes(1);
    expect(calls('/api/v1/auth/refresh')).toHaveLength(1);
    for (const p of paths) expect(calls(p)).toHaveLength(1);
  });

  it('O15 /auth/* 의 401 은 refresh 를 부르지 않는다', async () => {
    fetchMock.mockResolvedValue(unauthorized());
    await expect(request('POST', '/api/v1/auth/logout')).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('O15 UNAUTHORIZED 가 아닌 401 은 refresh 를 부르지 않는다', async () => {
    fetchMock.mockResolvedValue(json(401, { code: 'REALTIME_TICKET_INVALID', message: '' }));
    await expect(request('GET', '/api/v1/x')).rejects.toMatchObject({
      code: 'REALTIME_TICKET_INVALID',
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('O15 refresh 뒤에도 401 이면 다시 refresh 하지 않고 실패', async () => {
    fetchMock.mockImplementation((url) =>
      url === '/api/v1/auth/refresh' ? json(200, {}) : unauthorized(),
    );
    await expect(getBoard()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(calls('/api/v1/auth/refresh')).toHaveLength(1);
    expect(calls('/api/v1/games/today/board')).toHaveLength(2);
  });

  it('O15 명시적 refresh() 도 자동 refresh 와 같은 한 번을 탄다', async () => {
    const refreshGate = deferred<Response>();
    let refreshed = false;
    fetchMock.mockImplementation((url) => {
      if (url === '/api/v1/auth/refresh') {
        return refreshGate.promise.then((r) => {
          refreshed = true;
          return r;
        });
      }
      return refreshed ? json(200, {}) : unauthorized();
    });
    const both = Promise.all([refresh(), getBoard()]);
    await vi.waitFor(() => expect(calls('/api/v1/games/today/board')).toHaveLength(1));
    await new Promise((r) => setTimeout(r, 0));
    refreshGate.resolve(json(200, { userId: 'user_1' }));
    await both;
    expect(calls('/api/v1/auth/refresh')).toHaveLength(1);
  });
});

describe('http — 신호 API 재시도 (W17)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  const body = { questionNo: 1, questionId: 'a'.repeat(64) } as const;

  it('O16 503 두 번 뒤 200 → 호출 3번 · 1초/2초 간격 · 같은 본문', async () => {
    fetchMock
      .mockResolvedValueOnce(unavailable())
      .mockResolvedValueOnce(unavailable())
      .mockResolvedValueOnce(json(200, { ok: true }));
    const p = postShown(body);

    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1999);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(3);

    await expect(p).resolves.toEqual({ ok: true });
    const bodies = fetchMock.mock.calls.map(([, init]) => init.body);
    expect(new Set(bodies)).toEqual(new Set([JSON.stringify(body)]));
  });

  it('O16 503 네 번 → 4번 뒤 실패', async () => {
    fetchMock.mockImplementation(() => unavailable());
    const p = postShown(body).catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(1000 + 2000 + 4000);
    expect(await p).toMatchObject({ status: 503, code: 'UNAVAILABLE' });
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('O16 네트워크 오류도 다시 보낸다', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json(200, { ok: true }));
    const p = postShown(body);
    await vi.advanceTimersByTimeAsync(1000);
    await expect(p).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it.each([
    [500, 'INTERNAL_ERROR'],
    [409, 'QUESTION_MISMATCH'],
  ])('O16 %i 은 다시 보내지 않는다 → 1번', async (status, code) => {
    fetchMock.mockImplementation(() => json(status, { code, message: '' }));
    const p = postShown(body).catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(await p).toMatchObject({ status, code });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('O16 idempotent 가 아니면 503 이어도 1번', async () => {
    fetchMock.mockImplementation(() => unavailable());
    const p = getBoard().catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(await p).toMatchObject({ status: 503 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('O16 대기 중에 abort 하면 더 보내지 않는다', async () => {
    fetchMock.mockImplementation(() => unavailable());
    const ctrl = new AbortController();
    const p = postShown(body, ctrl.signal).catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(500);
    ctrl.abort();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(await p).toBeInstanceOf(DOMException);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
