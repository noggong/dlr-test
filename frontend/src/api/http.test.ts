import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
// http 는 모듈 상태(진행 중인 refresh · 죽은 세션)를 가진다 — 테스트마다 새로 불러온다.
type Http = typeof import('./http');
type Endpoints = typeof import('./endpoints');
let ApiError: Http['ApiError'];
let NetworkError: Http['NetworkError'];
let request: Http['request'];
let setOnUnauthenticated: Http['setOnUnauthenticated'];
let getBoard: Endpoints['getBoard'];
let postShown: Endpoints['postShown'];
let refresh: Endpoints['refresh'];
let login: Endpoints['login'];

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

beforeEach(async () => {
  vi.resetModules();
  ({ ApiError, NetworkError, request, setOnUnauthenticated } = await import('./http'));
  ({ getBoard, postShown, refresh, login } = await import('./endpoints'));
  fetchMock = vi.fn<Handler>();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
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

  it('O14 2xx 인데 JSON 이 아니면 code HTTP_<상태>', async () => {
    fetchMock.mockResolvedValue(new Response('<!doctype html>', { status: 200 }));
    await expect(request('GET', '/api/v1/typo')).rejects.toMatchObject({
      status: 200,
      code: 'HTTP_200',
    });
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

  it('O15 가입 · 로그인 · refresh · 로그아웃의 401 은 refresh 를 부르지 않는다', async () => {
    fetchMock.mockImplementation(() => unauthorized());
    for (const p of ['signup', 'login', 'logout']) {
      await expect(request('POST', `/api/v1/auth/${p}`)).rejects.toMatchObject({ status: 401 });
    }
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(calls('/api/v1/auth/refresh')).toHaveLength(0);
  });

  it('O15 /auth/me 의 401 은 refresh 뒤 한 번 다시(W21)', async () => {
    let me = 0;
    fetchMock.mockImplementation((url) => {
      if (url === '/api/v1/auth/refresh') return json(200, {});
      return ++me === 1 ? unauthorized() : json(200, { userId: 'alice_01', groupName: 'g' });
    });
    await expect(request('GET', '/api/v1/auth/me')).resolves.toMatchObject({ userId: 'alice_01' });
    expect(calls('/api/v1/auth/refresh')).toHaveLength(1);
    expect(calls('/api/v1/auth/me')).toHaveLength(2);
  });

  it('O15 refresh 401 뒤 늦게 온 401 은 refresh 를 다시 부르지 않고 onUnauthenticated 도 한 번', async () => {
    const onUnauth = vi.fn();
    setOnUnauthenticated(onUnauth);
    fetchMock.mockImplementation(() => unauthorized());
    await expect(request('GET', '/api/v1/a')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    await expect(request('GET', '/api/v1/b')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(calls('/api/v1/auth/refresh')).toHaveLength(1);
    expect(onUnauth).toHaveBeenCalledTimes(1);
  });

  it('O15 다시 로그인하면 401 에 refresh 를 다시 부른다', async () => {
    fetchMock.mockImplementation((url) =>
      url === '/api/v1/auth/login' ? json(200, {}) : unauthorized(),
    );
    await expect(request('GET', '/api/v1/a')).rejects.toBeInstanceOf(ApiError);
    await login({ loginId: 'user_1', password: 'password1' });
    await expect(request('GET', '/api/v1/a')).rejects.toBeInstanceOf(ApiError);
    expect(calls('/api/v1/auth/refresh')).toHaveLength(2);
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
    // 경과 시각 → 그때까지의 호출 수: 0 → 1, 1초 → 2, 3초 → 3, 7초 → 4
    for (const [step, count] of [
      [0, 1],
      [999, 1],
      [1, 2],
      [1999, 2],
      [1, 3],
      [3999, 3],
      [1, 4],
    ] as const) {
      await vi.advanceTimersByTimeAsync(step);
      expect(fetchMock).toHaveBeenCalledTimes(count);
    }
    expect(await p).toMatchObject({ status: 503, code: 'UNAVAILABLE' });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('O16 네트워크 오류도 다시 보낸다', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json(200, { ok: true }));
    const p = postShown(body);
    await vi.advanceTimersByTimeAsync(999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await expect(p).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].body).toBe(fetchMock.mock.calls[0][1].body);
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

  it('O16 abort 이유가 무엇이든 NetworkError 로 바꾸거나 다시 보내지 않는다', async () => {
    const ctrl = new AbortController();
    const reason = new Error('page left');
    fetchMock.mockImplementation(() => {
      ctrl.abort(reason);
      return Promise.reject(reason);
    });
    const p = postShown(body, ctrl.signal).catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(await p).toBe(reason);
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
