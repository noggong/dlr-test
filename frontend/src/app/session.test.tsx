import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { err, json, kst, mockFetch, noContent, renderApp, todayBody } from '@/test/app';
import { ServerClock } from './clock';
import { landingPath, type Today } from './session';

const HOUR = 60 * 60 * 1000;
const loc = () => screen.getByTestId('loc').textContent;

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

  it('O21 / 는 서버 시각으로 첫 화면을 고른다(기기 −3시간 · 서버 07:00:00 → /quiz)', async () => {
    localStorage.setItem('gm:me', 'alice_01');
    const serverTime = kst('07:00:00.000');
    const clock = skewedClock(serverTime, -3 * HOUR);
    mockFetch({ 'GET /api/v1/games/today': [() => json(200, todayBody(serverTime))] });
    await renderApp('/', clock);
    await waitFor(() => expect(loc()).toBe('/quiz'));
  });

  it('O21 / 는 로그인돼 있지 않으면 /login', async () => {
    const { count } = mockFetch({});
    await renderApp('/');
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count('GET /api/v1/games/today')).toBe(0);
  });
});

describe('RequireAuth — 가드 (W18 · §6.3)', () => {
  it('O20 로그인 필요 경로에서 games/today 401 + refresh 401 → /login · gm:me 지움', async () => {
    localStorage.setItem('gm:me', 'alice_01');
    const { count } = mockFetch({
      'GET /api/v1/games/today': [() => err(401, 'UNAUTHORIZED')],
      'POST /api/v1/auth/refresh': [() => err(401, 'UNAUTHORIZED')],
    });
    await renderApp('/waiting');
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(localStorage.getItem('gm:me')).toBeNull();
    expect(count('POST /api/v1/auth/refresh')).toBe(1);
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument();
  });

  it('O20 games/today 401 + refresh 200 이면 다시 불러 지면을 연다', async () => {
    localStorage.setItem('gm:me', 'alice_01');
    const { count } = mockFetch({
      'GET /api/v1/games/today': [
        () => err(401, 'UNAUTHORIZED'),
        () => json(200, todayBody(kst('06:55:00.000'))),
      ],
      'POST /api/v1/auth/refresh': [() => json(200, { userId: 'alice_01' })],
    });
    await renderApp('/waiting');
    await screen.findByRole('button', { name: '로그아웃' });
    expect(loc()).toBe('/waiting');
    expect(count('GET /api/v1/games/today')).toBe(2);
  });

  it('O20 gm:me 가 없으면 로그아웃한 뒤 /login', async () => {
    const { count } = mockFetch({ 'POST /api/v1/auth/logout': [noContent] });
    await renderApp('/quiz');
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count('POST /api/v1/auth/logout')).toBe(1);
    expect(count('GET /api/v1/games/today')).toBe(0);
  });

  it('O20 games/today 500 이면 「잠시 뒤 다시 열어 주세요」 · 다시 시도', async () => {
    localStorage.setItem('gm:me', 'alice_01');
    mockFetch({
      'GET /api/v1/games/today': [
        () => err(500, 'INTERNAL_ERROR'),
        () => json(200, todayBody(kst('06:55:00.000'))),
      ],
    });
    await renderApp('/waiting');
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 뒤 다시 열어 주세요');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByRole('button', { name: '로그아웃' });
    expect(loc()).toBe('/waiting');
  });
});

describe('로그아웃 (auth-signup §2.4)', () => {
  const signedIn = () => {
    localStorage.setItem('gm:me', 'alice_01');
    return { 'GET /api/v1/games/today': [() => json(200, todayBody(kst('06:55:00.000')))] };
  };

  it('O22 POST /auth/logout 204 → gm:me 지움 · /login', async () => {
    const { count } = mockFetch({ ...signedIn(), 'POST /api/v1/auth/logout': [noContent] });
    await renderApp('/waiting');
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(localStorage.getItem('gm:me')).toBeNull();
    expect(count('POST /api/v1/auth/logout')).toBe(1);
  });

  it('O22 access 만료(401)면 refresh 뒤 한 번 더 로그아웃', async () => {
    const { count } = mockFetch({
      ...signedIn(),
      'POST /api/v1/auth/logout': [() => err(401, 'UNAUTHORIZED'), noContent],
      'POST /api/v1/auth/refresh': [() => json(200, { userId: 'alice_01' })],
    });
    await renderApp('/waiting');
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
    await waitFor(() => expect(loc()).toBe('/login'));
    expect(count('POST /api/v1/auth/logout')).toBe(2);
    expect(count('POST /api/v1/auth/refresh')).toBe(1);
    expect(localStorage.getItem('gm:me')).toBeNull();
  });

  it('O22 서버 오류(500)면 로그인 상태를 그대로 두고 알린다', async () => {
    mockFetch({ ...signedIn(), 'POST /api/v1/auth/logout': [() => err(500, 'INTERNAL_ERROR')] });
    await renderApp('/waiting');
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃하지 못했어요');
    expect(loc()).toBe('/waiting');
    expect(localStorage.getItem('gm:me')).toBe('alice_01');
  });
});
