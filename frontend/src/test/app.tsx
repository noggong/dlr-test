import { render } from '@testing-library/react';
import { vi } from 'vitest';
import type { ServerClock } from '@/app/clock';

// 앱 전체를 메모리 라우터로 그리고, 지금 경로를 data-testid="loc" 에 적는다.
// http 는 모듈 상태(진행 중인 refresh · 죽은 세션)를 가진다 — 테스트마다 vi.resetModules() 뒤 새로 불러온다.

export type Handler = (init: RequestInit) => Response | Promise<Response>;

export const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
export const err = (status: number, code: string) => json(status, { code, message: 'x' });
export const noContent = () => new Response(null, { status: 204 });

/** `'METHOD /path'` → 응답들(차례대로 쓰고 마지막 것을 되풀이). 없는 경로는 테스트 실패로 404. */
export function mockFetch(routes: Record<string, Handler[]>) {
  const used: Record<string, number> = {};
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const key = `${init.method ?? 'GET'} ${url}`;
    const list = routes[key];
    if (!list) return err(404, `HTTP_404_${key}`);
    const i = Math.min(used[key] ?? 0, list.length - 1);
    used[key] = (used[key] ?? 0) + 1;
    return list[i](init);
  });
  vi.stubGlobal('fetch', fetchMock);
  return {
    fetchMock,
    count: (key: string) =>
      fetchMock.mock.calls.filter(([url, init]) => `${init?.method ?? 'GET'} ${url}` === key)
        .length,
  };
}

export async function renderApp(path: string, clock?: ServerClock) {
  vi.resetModules();
  const { MemoryRouter, useLocation } = await import('react-router-dom');
  const { default: App } = await import('@/App');
  function Probe() {
    return <output data-testid="loc">{useLocation().pathname}</output>;
  }
  return render(
    <MemoryRouter
      initialEntries={[path]}
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
    >
      <App clock={clock} />
      <Probe />
    </MemoryRouter>,
  );
}

const DAY = '2026-11-12';
export function todayBody(serverTime: string, members = ['dohyun', 'jiwoo_k', 'minseo01']) {
  return {
    date: DAY,
    startAt: `${DAY}T07:00:00+09:00`,
    deadlineAt: `${DAY}T08:00:00+09:00`,
    serverTime,
    group: { name: '아침수영', members: members.map((userId) => ({ userId })) },
  };
}
export const kst = (hms: string) => `${DAY}T${hms}+09:00`;
