import type { components } from '@/api/schema';

// 진행 저장(LLD §2.2 · SPEC §6.5 · prd R-F1). 키 하나에 JSON 하나 — 토큰 · 개인정보는 넣지 않는다(D16).

type QuestionNo = components['schemas']['QuestionNo'];

export interface Progress {
  /** 게임 날짜 `YYYY-MM-DD`(`GET /games/today` 의 `date`) */
  date: string;
  questionNo: QuestionNo;
  /** 지금 문제(진입 신호를 보낸 것) */
  questionId: string;
  /** 오늘 받은 · 낸 문제 PK(패스로 넘긴 것 포함) */
  servedIds: string[];
  passUsed: boolean;
  /** 3번까지 풀었거나 실패 신호를 보냄 */
  done: boolean;
}

const PREFIX = 'gm:v1:';
const HEX64 = /^[0-9a-f]{64}$/;

export const progressKey = (userId: string) => `${PREFIX}${userId}`;

/** 저장소를 꺼내는 함수 — 기본은 브라우저 로컬 스토리지, 테스트는 메모리 저장소를 넣는다. */
export type StorageSource = () => Storage;

const browserStorage: StorageSource = () => window.localStorage;

/** 접근 자체가 예외(사생활 모드 · 막힌 저장소)면 `null`. */
function open(source: StorageSource): Storage | null {
  try {
    return source();
  } catch {
    return null;
  }
}

function parse(raw: string | null, date: string): Progress | null {
  if (raw === null) return null;
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof v !== 'object' || v === null) return null;
  const p = v as Record<string, unknown>;
  if (p.date !== date) return null;
  if (p.questionNo !== 1 && p.questionNo !== 2 && p.questionNo !== 3) return null;
  if (typeof p.questionId !== 'string' || !HEX64.test(p.questionId)) return null;
  if (!Array.isArray(p.servedIds) || !p.servedIds.every((id) => typeof id === 'string'))
    return null;
  if (typeof p.passUsed !== 'boolean' || typeof p.done !== 'boolean') return null;
  return {
    date,
    questionNo: p.questionNo,
    questionId: p.questionId,
    servedIds: [...(p.servedIds as string[])],
    passUsed: p.passUsed,
    done: p.done,
  };
}

/** 그 날짜의 진행. 없음 · 깨진 JSON · 모자란 필드 · 다른 날짜 · 접근 예외 → 「빔」(`null`). */
export function loadProgress(
  userId: string,
  date: string,
  source: StorageSource = browserStorage,
): Progress | null {
  try {
    return parse(open(source)?.getItem(progressKey(userId)) ?? null, date);
  } catch {
    return null;
  }
}

/** 저장한다. 실패(접근 예외 · 용량 초과)면 `false` — 호출자가 안내한다(LLD §4). */
export function saveProgress(
  userId: string,
  progress: Progress,
  source: StorageSource = browserStorage,
): boolean {
  const s = open(source);
  if (s === null) return false;
  const { date, questionNo, questionId, servedIds, passUsed, done } = progress;
  try {
    s.setItem(
      progressKey(userId),
      JSON.stringify({ date, questionNo, questionId, servedIds, passUsed, done }),
    );
    return true;
  } catch {
    return false;
  }
}

/** 로그인 때 다른 유저의 진행 키와 내 키 중 오늘 것이 아닌 것을 지운다. `gm:v1:` 밖의 키는 건드리지 않는다. */
export function clearOther(
  userId: string,
  today: string,
  source: StorageSource = browserStorage,
): void {
  const s = open(source);
  if (s === null) return;
  try {
    const mine = progressKey(userId);
    const stale: string[] = [];
    for (let i = 0; i < s.length; i++) {
      const key = s.key(i);
      if (key === null || !key.startsWith(PREFIX)) continue;
      if (key !== mine || parse(s.getItem(key), today) === null) stale.push(key);
    }
    stale.forEach((key) => s.removeItem(key));
  } catch {
    // 지우지 못해도 읽기는 키 · 날짜로 걸러지므로 진행에는 영향이 없다.
  }
}
