import { describe, expect, it } from 'vitest';
import { isoKst, kstParts, ServerClock } from './clock';

describe('clock — 서버 시각 (W30)', () => {
  it('offset = 서버 ms − 단조 시계, now() 는 단조 시계를 따라 흐른다', () => {
    let mono = 1_000_000;
    const clock = new ServerClock(() => mono);
    expect(clock.isSynced).toBe(false);
    clock.sync('2026-11-12T06:59:58.500+09:00');
    expect(clock.isSynced).toBe(true);
    expect(isoKst(clock.now())).toBe('2026-11-12T06:59:58.500+09:00');
    mono += 1_500;
    expect(isoKst(clock.now())).toBe('2026-11-12T07:00:00.000+09:00');
  });

  it('isoKst 는 +09:00 · 밀리초, kstParts 는 KST 날짜 · 시 · 분', () => {
    const d = new Date(Date.parse('2026-11-11T22:03:04.005Z'));
    expect(isoKst(d)).toBe('2026-11-12T07:03:04.005+09:00');
    expect(kstParts(d)).toEqual({ date: '2026-11-12', hour: 7, minute: 3 });
  });

  it('읽지 못하는 serverTime 은 오류', () => {
    expect(() => new ServerClock(() => 0).sync('nope')).toThrow();
  });
});
