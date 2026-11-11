import { refreshSession, request } from './http';
import type { components, operations } from './schema';

// REST 14개(13 + v0.5.0 `/auth/me` · SSE 연결은 realtime 모듈). 요청 · 응답 타입은 openapi 생성 타입(schema.ts)에서 꺼낸다.
type Schemas = components['schemas'];
type Json<
  K extends keyof operations,
  S extends keyof operations[K]['responses'],
> = operations[K]['responses'][S] extends { content: { 'application/json': infer B } } ? B : never;

const AUTH = '/api/v1/auth';
const TODAY = '/api/v1/games/today';

export const signup = (body: Schemas['SignUpRequest'], signal?: AbortSignal) =>
  request<Json<'signUp', 201>>('POST', `${AUTH}/signup`, { body, signal });

export const login = (body: Schemas['LoginRequest'], signal?: AbortSignal) =>
  request<Json<'login', 200>>('POST', `${AUTH}/login`, { body, signal });

/** 자동 refresh 와 같은 single-flight 를 탄다(AS12). */
export const refresh = (): Promise<Json<'refreshSession', 200>> => refreshSession();

export const logout = (signal?: AbortSignal) =>
  request<undefined>('POST', `${AUTH}/logout`, { signal });

/** 지금 로그인한 나(prd D23) — 401 이면 refresh 한 번(W21). */
export const getMe = (signal?: AbortSignal) =>
  request<Json<'getMe', 200>>('GET', `${AUTH}/me`, { signal });

export const getToday = (signal?: AbortSignal) =>
  request<Json<'getGameInfo', 200>>('GET', TODAY, { signal });

export const postQuestionBank = (signal?: AbortSignal) =>
  request<Json<'receiveQuestionBank', 200>>('POST', `${TODAY}/question-bank`, { signal });

export const postShown = (body: Schemas['ShownRequest'], signal?: AbortSignal) =>
  request<Json<'reportShown', 200>>('POST', `${TODAY}/shown`, { body, signal, idempotent: true });

export const postSolve = (body: Schemas['SolveRequest'], signal?: AbortSignal) =>
  request<Json<'submitSolve', 200>>('POST', `${TODAY}/solves`, { body, signal, idempotent: true });

export const postPass = (body: Schemas['PassRequest'], signal?: AbortSignal) =>
  request<Json<'submitPass', 200>>('POST', `${TODAY}/passes`, { body, signal, idempotent: true });

export const postFailure = (signal?: AbortSignal) =>
  request<Json<'reportCheatFailure', 200>>('POST', `${TODAY}/failures`, {
    signal,
    idempotent: true,
  });

export const getBoard = (signal?: AbortSignal) =>
  request<Json<'getBoard', 200>>('GET', `${TODAY}/board`, { signal });

export const postTicket = (signal?: AbortSignal) =>
  request<Json<'issueRealtimeTicket', 201>>('POST', '/api/v1/realtime/tickets', { signal });

export const getLatestResult = (signal?: AbortSignal) =>
  request<Json<'getLatestResult', 200>>('GET', '/api/v1/games/results/latest', { signal });
