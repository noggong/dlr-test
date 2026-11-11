// 게임의 「지금」은 서버 시각이다(SPEC W30 · ORACLE I9). 기기 시계(new Date() · Date.now())는
// 이 파일 밖에서 쓰지 않는다 — 기기 시계가 몇 시간 틀려도 07:00 전환 · 08:00 잠금이 서버와 같아야 한다.

/** 단조 시계(ms, 기기 기준 절대 시각 근사). 테스트에서 바꿔 끼운다. */
export type MonotonicSource = () => number;

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

const defaultMonotonic: MonotonicSource = () => performance.timeOrigin + performance.now();

export class ServerClock {
  private offset = 0;
  private synced = false;
  private readonly mono: MonotonicSource;

  constructor(mono: MonotonicSource = defaultMonotonic) {
    this.mono = mono;
  }

  /** 서버가 준 시각(`GET /games/today` 의 `serverTime`)으로 offset 을 다시 잡는다. */
  sync(serverTime: string): void {
    const serverMs = Date.parse(serverTime);
    if (Number.isNaN(serverMs)) throw new Error(`serverTime 을 읽지 못함: ${serverTime}`);
    this.offset = serverMs - this.mono();
    this.synced = true;
  }

  /** 서버와 한 번이라도 맞췄는가. 맞추기 전의 now() 는 기기 시계다. */
  get isSynced(): boolean {
    return this.synced;
  }

  /** 서버 기준 지금(ms). */
  nowMs(): number {
    return this.mono() + this.offset;
  }

  /** 서버 기준 지금. */
  now(): Date {
    return new Date(this.nowMs());
  }
}

/** `2026-11-12T07:03:21.456+09:00` — 서버로 보내는 시각 모양(api-guide §1). */
export function isoKst(d: Date): string {
  return new Date(d.getTime() + KST_OFFSET_MS).toISOString().replace('Z', '+09:00');
}

/** KST 기준 날짜 · 시 · 분(화면 표기용). */
export function kstParts(d: Date): { date: string; hour: number; minute: number } {
  const iso = isoKst(d);
  return {
    date: iso.slice(0, 10),
    hour: Number(iso.slice(11, 13)),
    minute: Number(iso.slice(14, 16)),
  };
}
