import type { getLatestResult } from '@/api/endpoints';
import type { Today } from '@/app/session';
import { formatArrival } from '@/realtime/mapEntry';
import type { YesterdayCard } from './WaitingView';

export type LatestResult = Awaited<ReturnType<typeof getLatestResult>>;
export type DecidedResult = LatestResult & { groupStatus: 'SUCCEEDED' | 'FAILED' };

const DAY_MS = 24 * 60 * 60 * 1000;

export function isDecided(r: LatestResult): r is DecidedResult {
  return r.groupStatus === 'SUCCEEDED' || r.groupStatus === 'FAILED';
}

/** `YYYY-MM-DD` 하루 앞(KST 날짜끼리 — 기기 시간대와 상관없다). */
export function dayBefore(date: string): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) - DAY_MS).toISOString().slice(0, 10);
}

/** 내 순위 줄 — 그날 그룹원이 아니었으면(07:00 뒤 가입) `myRank` 가 null 이다. */
export function rankText(r: DecidedResult): string {
  return r.myRank === null
    ? `그날은 참가하지 않았어요 · 그룹원 ${r.ranking.length}명`
    : `내 순위 ${r.myRank}위 / ${r.ranking.length}명`;
}

/** 대기 지면의 어제 결과 카드(SPEC W11). 결과가 아직 없거나 확정 전이면 카드 없음. */
export function yesterdayCard(r: LatestResult, today: Today): YesterdayCard | undefined {
  if (!isDecided(r)) return undefined;
  const ok = r.groupStatus === 'SUCCEEDED';
  const [, m, d] = r.date.split('-').map(Number);
  const done = ok && r.decidedAt ? ` · ${formatArrival(r.decidedAt)} 완료` : '';
  return {
    title: r.date === dayBefore(today.date) ? '어제 결과' : `${m}월 ${d}일 결과`,
    tone: ok ? 'ok' : 'bad',
    label: ok ? '그룹 미션 성공' : '그룹 미션 실패',
    detail: rankText(r) + done,
  };
}

/** 결과 지면 머리 아래 줄. 08:00 전 확정 실패는 부정 실패로 끝난 것이다(즉시 실패). */
export function resultSub(r: DecidedResult): string {
  const at = r.decidedAt ? formatArrival(r.decidedAt) : '';
  if (r.groupStatus === 'SUCCEEDED') {
    return `${at ? `${at} 완료 · ` : ''}그룹원 ${r.ranking.length}명 모두 3문제`;
  }
  const deadline = Date.parse(`${r.date}T08:00:00+09:00`);
  if (r.decidedAt && Date.parse(r.decidedAt) < deadline) {
    return `${at} 실패 처리된 그룹원이 있어 미션이 끝났어요.`;
  }
  return '08:00까지 그룹원 모두가 3문제를 끝내지 못했어요.';
}
