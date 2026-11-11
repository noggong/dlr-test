import { createContext, type ReactNode } from 'react';

/**
 * 상단 바 오른쪽 끝에 서는 것(로그인 필요 지면의 로그아웃 버튼). 지면 코드를 고치지 않고
 * 가드(`RequireAuth`)가 넣는다 — 스토리 · 가입 · 로그인 지면에는 없다.
 */
export const ShellActions = createContext<ReactNode>(null);
