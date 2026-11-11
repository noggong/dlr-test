import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { err, json, kst, mockFetch, renderApp, todayBody } from '@/test/app';
import { MSG } from './authForm';

const loc = () => screen.getByTestId('loc').textContent;
const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('가입 (SPEC §6.3)', () => {
  async function fillSignup(mode: 'new' | 'join', group: string) {
    await renderApp('/signup');
    type('아이디', 'alice_01');
    type('비밀번호', 'correct-horse-9');
    if (mode === 'join') fireEvent.click(screen.getByRole('tab', { name: '기존 그룹 들어가기' }));
    type(mode === 'new' ? '새 그룹명' : '들어갈 그룹명', group);
    fireEvent.click(screen.getByRole('button', { name: '가입하기' }));
  }

  it('O18 GROUP_FULL → full 문구 · 10/10', async () => {
    const { fetchMock } = mockFetch({
      'POST /api/v1/auth/signup': [() => err(409, 'GROUP_FULL')],
    });
    await fillSignup('join', ' 새벽러닝 ');
    expect(await screen.findByText(MSG.full('새벽러닝'))).toBeInTheDocument();
    expect(screen.getByText('10/10')).toBeInTheDocument();
    expect(screen.getByLabelText('들어갈 그룹명')).toHaveAttribute('aria-invalid', 'true');
    const body = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(body).toEqual({
      loginId: 'alice_01',
      password: 'correct-horse-9',
      group: { mode: 'EXISTING', name: ' 새벽러닝 ' },
    });
  });

  it('O18 GROUP_NAME_TAKEN → dup 문구', async () => {
    mockFetch({ 'POST /api/v1/auth/signup': [() => err(409, 'GROUP_NAME_TAKEN')] });
    await fillSignup('new', '아침7시클럽');
    expect(await screen.findByText(MSG.dup)).toBeInTheDocument();
    expect(screen.getByLabelText('새 그룹명')).toHaveAttribute('aria-invalid', 'true');
  });

  it('O18 GROUP_NOT_FOUND → 그룹명 칸 오류', async () => {
    mockFetch({ 'POST /api/v1/auth/signup': [() => err(404, 'GROUP_NOT_FOUND')] });
    await fillSignup('join', '없는그룹');
    expect(await screen.findByText(MSG.notFound)).toBeInTheDocument();
    expect(screen.getByLabelText('들어갈 그룹명')).toHaveAttribute('aria-invalid', 'true');
  });

  it('O18 LOGIN_ID_TAKEN → 아이디 칸 오류', async () => {
    mockFetch({ 'POST /api/v1/auth/signup': [() => err(409, 'LOGIN_ID_TAKEN')] });
    await fillSignup('new', '아침7시클럽');
    expect(await screen.findByText(MSG.loginIdTaken)).toBeInTheDocument();
    expect(screen.getByLabelText('아이디')).toHaveAttribute('aria-invalid', 'true');
  });

  it('O18 INVALID_REQUEST → 규칙 안내 · INTERNAL_ERROR → 잠시 뒤', async () => {
    mockFetch({
      'POST /api/v1/auth/signup': [
        () => err(400, 'INVALID_REQUEST'),
        () => err(500, 'INTERNAL_ERROR'),
      ],
    });
    await fillSignup('new', '아침7시클럽');
    expect(await screen.findByRole('alert')).toHaveTextContent(MSG.invalidRequest);
    fireEvent.click(screen.getByRole('button', { name: '가입하기' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(MSG.retryLater));
  });

  it('O18 입력 규칙(AS1 ~ AS3)에 어긋나면 보내지 않고 그 칸에 규칙 안내', async () => {
    const { fetchMock } = mockFetch({});
    await renderApp('/signup');
    type('아이디', 'Alice');
    type('비밀번호', 'short');
    type('새 그룹명', '팀!');
    fireEvent.click(screen.getByRole('button', { name: '가입하기' }));
    expect(await screen.findByText(MSG.loginIdRule)).toBeInTheDocument();
    expect(screen.getByText(MSG.passwordRule)).toBeInTheDocument();
    expect(screen.getByText(MSG.groupNameRule)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('O18 201 → 가입 완료 요약(firstGameDate 가 가입 날짜면 오늘부터)', async () => {
    mockFetch({
      'POST /api/v1/auth/signup': [
        () =>
          json(201, {
            userId: 'alice_01',
            groupName: 'Team-A',
            joinedAt: '2026-11-12T06:41:09.120+09:00',
            firstGameDate: '2026-11-12',
          }),
      ],
    });
    await fillSignup('join', 'team-a');
    expect(await screen.findByRole('heading', { name: '가입했어요' })).toBeInTheDocument();
    expect(screen.getByText('alice_01')).toBeInTheDocument();
    expect(screen.getByText('Team-A')).toBeInTheDocument();
    expect(screen.getByText('06:41')).toBeInTheDocument();
    expect(screen.getByText('오늘 게임부터')).toBeInTheDocument();
    expect(localStorage.getItem('gm:me')).toBeNull(); // 가입은 로그인하지 않는다(AS7)
  });

  it('O18 201 · 21:15 가입 → 06:50 안내 + 다음 날부터', async () => {
    mockFetch({
      'POST /api/v1/auth/signup': [
        () =>
          json(201, {
            userId: 'alice_01',
            groupName: '아침7시클럽',
            joinedAt: '2026-11-11T21:15:04.512+09:00',
            firstGameDate: '2026-11-12',
          }),
      ],
    });
    await fillSignup('new', '아침7시클럽');
    expect(await screen.findByText('21:15')).toBeInTheDocument();
    expect(
      screen.getByText(/06:50 이후 가입이라 오늘 미션에는 참여할 수 없어요/),
    ).toBeInTheDocument();
    expect(screen.getByText('11월 12일부터')).toBeInTheDocument();
  });

  // 06:50 경계(prd D24 · auth-signup AS19) — 서버가 정한 firstGameDate 를 따른다(W31)
  it.each([
    ['06:49:59', '2026-11-12', true],
    ['06:50:00', '2026-11-13', false],
  ])('O18 06:50 경계 · %s 가입 · firstGameDate %s', async (time, firstGameDate, today) => {
    mockFetch({
      'POST /api/v1/auth/signup': [
        () =>
          json(201, {
            userId: 'alice_01',
            groupName: 'Team-A',
            joinedAt: `2026-11-12T${time}.000+09:00`,
            firstGameDate,
          }),
      ],
    });
    await fillSignup('join', 'team-a');
    expect(await screen.findByRole('heading', { name: '가입했어요' })).toBeInTheDocument();
    if (today) {
      expect(screen.getByText('오늘 게임부터')).toBeInTheDocument();
      expect(screen.queryByText(/06:50 이후 가입이라/)).toBeNull();
    } else {
      expect(
        screen.getByText(/06:50 이후 가입이라 오늘 미션에는 참여할 수 없어요/),
      ).toBeInTheDocument();
      expect(screen.getByText('11월 13일부터')).toBeInTheDocument();
      expect(screen.queryByText('오늘 게임부터')).toBeNull();
    }
  });

  it('O18 화면은 시각이 아니라 firstGameDate 를 따른다', async () => {
    // 06:40 가입이어도 서버가 다음 날이라 하면(설정 game.join-cutoff 가 다르면) 안내를 보인다
    mockFetch({
      'POST /api/v1/auth/signup': [
        () =>
          json(201, {
            userId: 'alice_01',
            groupName: 'Team-A',
            joinedAt: '2026-11-12T06:40:00.000+09:00',
            firstGameDate: '2026-11-13',
          }),
      ],
    });
    await fillSignup('join', 'team-a');
    expect(await screen.findByText(/06:50 이후 가입이라/)).toBeInTheDocument();
    expect(screen.getByText('11월 13일부터')).toBeInTheDocument();
  });
});

describe('로그인 (SPEC W13 · §6.3)', () => {
  it('O19 이미 로그인돼 있으면 /login 은 첫 화면으로', async () => {
    mockFetch({
      'GET /api/v1/auth/me': [() => json(200, { userId: 'alice_01', groupName: '아침수영' })],
      'GET /api/v1/games/today': [() => json(200, todayBody(kst('08:00:00.000')))],
    });
    await renderApp(['/login', '/result']);
    await screen.findByRole('button', { name: '로그아웃' });
    fireEvent.click(screen.getByRole('button', { name: 'test-back' })); // 뒤로 가기로 /login
    await waitFor(() => expect(loc()).toBe('/result'));
    expect(screen.queryByRole('heading', { name: '로그인' })).toBeNull();
  });

  async function submitLogin() {
    await renderApp('/login');
    type('아이디', 'alice_01');
    type('비밀번호', 'correct-horse-9');
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  }

  it('O19 INVALID_CREDENTIALS → 시안 fail 문구(무엇이 틀렸는지 말하지 않음)', async () => {
    mockFetch({ 'POST /api/v1/auth/login': [() => err(401, 'INVALID_CREDENTIALS')] });
    await submitLogin();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(MSG.loginFail);
    expect(alert.textContent).toBe('아이디 또는 비밀번호가 맞지 않아요. 다시 확인해 주세요.');
    expect(screen.getByLabelText('아이디')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('aria-invalid', 'true');
    expect(loc()).toBe('/login');
    expect(localStorage.getItem('gm:me')).toBeNull();
  });

  it('O19 ALREADY_LOGGED_IN → 다른 기기 안내', async () => {
    mockFetch({ 'POST /api/v1/auth/login': [() => err(409, 'ALREADY_LOGGED_IN')] });
    await submitLogin();
    expect(await screen.findByRole('alert')).toHaveTextContent(MSG.alreadyLoggedIn);
    expect(loc()).toBe('/login');
  });

  it('O19 INTERNAL_ERROR · JSON 아닌 502 → 잠시 뒤 다시 열어 주세요', async () => {
    mockFetch({
      'POST /api/v1/auth/login': [
        () => err(500, 'INTERNAL_ERROR'),
        () => new Response('<html>Bad Gateway</html>', { status: 502 }),
      ],
    });
    await submitLogin();
    expect(await screen.findByRole('alert')).toHaveTextContent(MSG.retryLater);
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(MSG.retryLater));
  });

  it('O19 200 → 응답의 userId 로 첫 화면(저장하지 않음 · /auth/me 생략) · 다른 유저 · 지난 날짜 키 지움', async () => {
    localStorage.setItem('gm:v1:bob_99', JSON.stringify({ date: '2026-11-12' }));
    localStorage.setItem('gm:v1:alice_01', JSON.stringify({ date: '2026-11-11' }));
    const { fetchMock } = mockFetch({
      'POST /api/v1/auth/login': [
        () =>
          json(200, {
            userId: 'alice_01',
            groupName: '아침수영',
            accessExpiresAt: '2026-11-12T07:55:00+09:00',
            refreshExpiresAt: '2026-11-12T08:55:00+09:00',
          }),
      ],
      'GET /api/v1/games/today': [() => json(200, todayBody(kst('06:55:12.345')))],
    });
    await submitLogin();
    await waitFor(() => expect(loc()).toBe('/waiting'));
    expect(localStorage.getItem('gm:me')).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/v1/auth/me')).toBe(false);
    expect(localStorage.getItem('gm:v1:bob_99')).toBeNull();
    expect(localStorage.getItem('gm:v1:alice_01')).toBeNull();
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      loginId: 'alice_01',
      password: 'correct-horse-9',
    });
  });

  it('O19 200 · 오늘 날짜의 내 진행 키는 남긴다', async () => {
    const mine = JSON.stringify({ date: '2026-11-12', questionNo: 2 });
    localStorage.setItem('gm:v1:alice_01', mine);
    mockFetch({
      'POST /api/v1/auth/login': [() => json(200, { userId: 'alice_01', groupName: '아침수영' })],
      'GET /api/v1/games/today': [() => json(200, todayBody(kst('07:10:00.000')))],
    });
    await submitLogin();
    await waitFor(() => expect(loc()).toBe('/quiz'));
    expect(localStorage.getItem('gm:v1:alice_01')).toBe(mine);
  });
});
