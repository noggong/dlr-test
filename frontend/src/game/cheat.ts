import type { components } from '@/api/schema';
import type { Progress } from './storage';

type MemberStatus = components['schemas']['MemberStatus'];

export interface CheatInput {
  /** 서버 기준 시각이 07:00 ~ 08:00 안 */
  inWindow: boolean;
  /** 현황판 내 줄의 `started` */
  started: boolean;
  /** 현황판 내 줄의 `status` */
  status: MemberStatus;
  /** `loadProgress` 결과 — `null` 이 「빔」 */
  stored: Progress | null;
}

/**
 * 부정 실패 신호를 보낼까(SPEC W22 · prd R-F2). 창 안 · started · 진행 중 · 저장 빔일 때만 참.
 * 창 안에서 started 인 내 줄은 IN_PROGRESS · SUCCEEDED · CHEAT_FAILED 뿐이라 「CHEAT_FAILED · SUCCEEDED 가 아님」과
 * 같은 뜻이고, 어긋난 값에는 되돌릴 수 없는 실패 신호를 보내지 않는다(LLD §1.2).
 */
export function shouldReportCheat({ inWindow, started, status, stored }: CheatInput): boolean {
  return inWindow && started && status === 'IN_PROGRESS' && stored === null;
}
