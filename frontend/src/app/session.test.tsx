import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { err, json, kst, mockFetch, noContent, renderApp, todayBody } from '@/test/app';
import { ServerClock } from './clock';
import { landingPath, type Today } from './session';

const HOUR = 60 * 60 * 1000;
const ME = 'GET /api/v1/auth/me';
const TODAY = 'GET /api/v1/games/today';
const REFRESH = 'POST /api/v1/auth/refresh';
const LOGOUT = 'POST /api/v1/auth/logout';
const loc = () => screen.getByTestId('loc').textContent;
const meOk =
  (userId = 'alice_01') =>
  () =>
    json(200, { userId, groupName: '아침수영' });
const unauthorized = () => err(401, 'UNAUTHORIZED');
const todayOk =
  (hms = '06:55:00.000') =>
  () =>
    json(200, todayBody(kst(hms)));

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/** 기기 시계(Date · 단조 시계)를 서버보다 skew 만큼 틀리게 한 시계 */
function skewedClock(serverTime: string, skew: number): ServerClock {
  const device = Date.parse(serverTime) + skew;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(device);
  return new ServerClock(() => device);
}

describe('landingPath — 첫 화면 (LLD §3.4 · W30)', () => {
  const cases: Array<[string, string[] | undefined, string]> = [
    ['06:59:59.000', undefined, '/waiting'],
    ['07:00:00.000', undefined, '/quiz'],
    ['07:59:59.999', undefined, '/quiz'],
    ['08:00:00.000', undefined, '/result'],
    ['07:30:00.000', ['junho'], '/solo'],
  ];
  for (const skew of [3 * HOUR, -3 * HOUR]) {
    for (const [hms, members, expected] of cases) {
      it(`O21 기기 시계 ${skew > 0 ? '+' : '−'}3시간 · 서버 ${hms}${members ? ' · 그룹원 1명' : ''} → ${expected}`, () => {
        const body = todayBody(kst(hms), members) as Today;
        const clock = skewedClock(body.serverTime, skew);
        clock.sync(body.serverTime);
        expect(landingPath(body, clock)).toBe(expected);
      });
    }
  }

  it('O21 / 는 /auth/me 뒤 서버 시각으로 첫 화면을 고른다(기기 −3시간 · 서버 07:00:00 → /quiz)', async () => {
    const serverTime = kst('07:00:00.000');
    const clock = skewedClock(serverTime, -3 * HOUR);
    mockFetch({ [ME]: [meOk()], [TODAY]: [() => json(200, todayBody(serverTime))] });
    await renderApp('/', clock);
    await waitFor(() => expect(loc()).toBe('/quiz'));
  });

  it('O21 / 는 로그인돼 있지 않으면(/auth/me 401 · refresh 401) /login', async () => {
    const { count } = mockFetch({ [ME]: [unauthorized], [REFRESH]: [unauthorized] });
    await renderApp('/');
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count(TODAY)).toBe(0);
  });
});

describe('RequireAuth — 가드 (W18 · W21 · §6.3)', () => {
  it('O20 로그인 필요 경로에서 /auth/me 401 + refresh 401 → /login', async () => {
    const { count } = mockFetch({ [ME]: [unauthorized], [REFRESH]: [unauthorized] });
    await renderApp('/waiting');
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count(ME)).toBe(1);
    expect(count(REFRESH)).toBe(1);
    expect(count(TODAY)).toBe(0);
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument();
  });

  it('O20 /auth/me 401 + refresh 200 → 다시 /auth/me 200 이면 그 userId 로 진행', async () => {
    const { count } = mockFetch({
      [ME]: [unauthorized, meOk()],
      [REFRESH]: [() => json(200, { userId: 'alice_01' })],
      [TODAY]: [todayOk()],
    });
    await renderApp('/waiting');
    await screen.findByRole('button', { name: '로그아웃' });
    expect(loc()).toBe('/waiting');
    expect(count(ME)).toBe(2);
    expect(count(REFRESH)).toBe(1);
  });

  it('O20 games/today 401 + refresh 401 → /login', async () => {
    mockFetch({ [ME]: [meOk()], [TODAY]: [unauthorized], [REFRESH]: [unauthorized] });
    await renderApp('/quiz');
    await waitFor(() => expect(loc()).toBe('/login'));
  });

  it('O20 로컬 스토리지에 「나」를 두지 않는다 — 진행 키 말고는 쓰지 않음', async () => {
    mockFetch({ [ME]: [meOk()], [TODAY]: [todayOk()] });
    await renderApp('/waiting');
    await screen.findByRole('button', { name: '로그아웃' });
    expect(Object.keys(localStorage).filter((k) => !k.startsWith('gm:v1:'))).toEqual([]);
  });

  it('O20 세션이 끝난 뒤 뒤로 가기로 가드 경로에 돌아와도 /login(다시 묻지 않음)', async () => {
    const { count } = mockFetch({ [ME]: [unauthorized], [REFRESH]: [unauthorized] });
    await renderApp(['/waiting', '/quiz']);
    await waitFor(() => expect(loc()).toBe('/login'));
    fireEvent.click(screen.getByRole('button', { name: 'test-back' }));
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(count(ME)).toBe(1);
    expect(count(LOGOUT)).toBe(0);
  });

  it('O20 games/today 500 이면 「잠시 뒤 다시 열어 주세요」 · 다시 시도', async () => {
    mockFetch({ [ME]: [meOk()], [TODAY]: [() => err(500, 'INTERNAL_ERROR'), todayOk()] });
    await renderApp('/waiting');
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 뒤 다시 열어 주세요');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByRole('button', { name: '로그아웃' });
    expect(loc()).toBe('/waiting');
  });

  it('O20 다시 보일 때 /auth/me 가 401(다른 탭에서 로그아웃) → /login', async () => {
    mockFetch({ [ME]: [meOk(), unauthorized], [REFRESH]: [unauthorized], [TODAY]: [todayOk()] });
    await renderApp('/waiting');
    await screen.findByRole('button', { name: '로그아웃' });
    fireEvent(document, new Event('visibilitychange'));
    await waitFor(() => expect(loc()).toBe('/login'));
  });

  it('O20 다시 보일 때 다른 계정이면(다른 탭에서 로그인) 그 계정의 첫 화면으로', async () => {
    mockFetch({ [ME]: [meOk('alice_01'), meOk('bob_99')], [TODAY]: [todayOk('07:10:00.000')] });
    await renderApp('/waiting');
    await screen.findByRole('button', { name: '로그아웃' });
    fireEvent(document, new Event('visibilitychange'));
    await waitFor(() => expect(loc()).toBe('/quiz'));
  });
});

describe('서버 시각 다시 맞추기 (W30)', () => {
  it('O21 화면이 다시 보이면 games/today 로 시계를 다시 맞춘다(실패해도 지면은 그대로)', async () => {
    let device = 0;
    const clock = new ServerClock(() => device);
    const { count } = mockFetch({
      [ME]: [meOk()],
      [TODAY]: [todayOk('06:30:00.000'), todayOk('06:50:00.000'), () => err(500, 'INTERNAL_ERROR')],
    });
    await renderApp('/waiting', clock);
    await screen.findByRole('button', { name: '로그아웃' });
    device += 1000; // 잠든 동안 단조 시계가 1초만 흘렀다
    fireEvent(document, new Event('visibilitychange'));
    await waitFor(() => expect(count(TODAY)).toBe(2));
    await waitFor(() => expect(clock.nowMs()).toBe(Date.parse(kst('06:50:00.000'))));
    fireEvent(document, new Event('visibilitychange'));
    await waitFor(() => expect(count(TODAY)).toBe(3));
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument();
    expect(loc()).toBe('/waiting');
  });
});

describe('로그아웃 (auth-signup §2.4)', () => {
  const signedIn = { [ME]: [meOk()], [TODAY]: [todayOk()] };

  it('O22 POST /auth/logout 204 → 세션 상태 비움 · /login(뒤로 가도 /login)', async () => {
    const { count } = mockFetch({ ...signedIn, [LOGOUT]: [noContent] });
    await renderApp(['/login', '/waiting']);
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count(LOGOUT)).toBe(1);
    // 로그인 화면이 다시 서고(나가 비었다) /auth/me 를 다시 묻지 않는다
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(count(ME)).toBe(1);
  });

  it('O22 access 만료(401)면 refresh 뒤 한 번 더 로그아웃', async () => {
    const { count } = mockFetch({
      ...signedIn,
      [LOGOUT]: [unauthorized, noContent],
      [REFRESH]: [() => json(200, { userId: 'alice_01' })],
    });
    await renderApp('/waiting');
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count(LOGOUT)).toBe(2);
    expect(count(REFRESH)).toBe(1);
  });

  it('O22 서버 오류(500)면 로그인 상태를 그대로 두고 알린다', async () => {
    mockFetch({ ...signedIn, [LOGOUT]: [() => err(500, 'INTERNAL_ERROR')] });
    await renderApp('/waiting');
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃하지 못했어요');
    expect(loc()).toBe('/waiting');
  });
});
