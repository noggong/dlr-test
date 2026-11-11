import type { components } from './schema';

type Session = components['schemas']['SessionResponse'];

// 한 앱 안에서 refresh 는 한 번에 하나만(AS12 · SPEC W18). 같은 refresh 쿠키로 둘이 동시에 가면
// 진 쪽이 401 을 받아 멀쩡한 세션이 로그아웃되므로, 진행 중인 Promise 를 모두가 함께 기다린다.
let inflight: Promise<Session> | null = null;

export function refreshOnce(run: () => Promise<Session>): Promise<Session> {
  if (!inflight) {
    inflight = run().finally(() => {
      inflight = null;
    });
  }
  return inflight;
}
