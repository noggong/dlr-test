import type { Person } from '@/components/Blocks';
import type { Today } from '@/app/session';

/**
 * 대기 · 1명 지면의 그룹원 목록(SPEC W32) — 그날 그룹원 다음에 늦은 그룹원(06:50 부터 가입 — game-session GS15)을
 * 「내일부터」로 붙인다. 표기 전용이라 인원 수 · 1명 판정은 `members` 만 센다.
 */
export function groupPeople(group: Today['group'], me: string | null): Person[] {
  const person = (userId: string, later?: true): Person => ({
    id: userId,
    me: userId === me || undefined,
    later,
  });
  return [
    ...group.members.map((m) => person(m.userId)),
    ...group.laterMembers.map((m) => person(m.userId, true)),
  ];
}
