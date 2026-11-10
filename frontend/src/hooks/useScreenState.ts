import { useSearchParams } from 'react-router-dom';

/** 모든 지면이 공통으로 받는 상태 — 뼈대 · 빈 상태. */
export const commonStates = ['loading', 'empty'] as const;
export type CommonState = (typeof commonStates)[number];

/**
 * 쿼리스트링 `state` 를 읽는다. 지면의 목록과 공통 상태 밖의 값이면 기본 상태.
 * 예) /quiz?state=pass
 */
export function useScreenState<T extends string>(
  states: readonly T[],
  fallback: T,
): T | CommonState {
  const [params] = useSearchParams();
  const raw = params.get('state');
  if (raw && (states as readonly string[]).includes(raw)) return raw as T;
  if (raw && (commonStates as readonly string[]).includes(raw)) return raw as CommonState;
  return fallback;
}
