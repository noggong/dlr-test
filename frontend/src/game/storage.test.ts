import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearOther,
  loadProgress,
  progressKey,
  saveProgress,
  type Progress,
  type StorageSource,
} from './storage';

/** Web Storage 와 같은 동작의 메모리 저장소 — 테스트마다 새로 만든다. */
class MemoryStorage implements Storage {
  private m = new Map<string, string>();
  get length() {
    return this.m.size;
  }
  clear() {
    this.m.clear();
  }
  getItem(k: string) {
    return this.m.get(k) ?? null;
  }
  key(i: number) {
    return [...this.m.keys()][i] ?? null;
  }
  removeItem(k: string) {
    this.m.delete(k);
  }
  setItem(k: string, v: string) {
    this.m.set(k, String(v));
  }
}

const ID = (c: string) => c.repeat(64);
const TODAY = '2026-11-11';
const P: Progress = {
  date: TODAY,
  questionNo: 2,
  questionId: ID('b'),
  servedIds: [ID('a'), ID('b')],
  passUsed: true,
  done: false,
};

const setup = () => {
  const mem = new MemoryStorage();
  const src: StorageSource = () => mem;
  return { mem, src };
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('O25 로컬 스토리지 저장 · 복원', () => {
  it('O25 같은 날짜면 그대로 복원', () => {
    const { mem, src } = setup();
    expect(saveProgress('u1', P, src)).toBe(true);
    expect(mem.getItem('gm:v1:u1')).not.toBeNull();
    expect(loadProgress('u1', TODAY, src)).toEqual(P);
  });

  it('O25 기본 저장소는 브라우저 Storage 의 gm:v1:<userId> 키', () => {
    const set = vi.spyOn(Storage.prototype, 'setItem');
    expect(saveProgress('u9', P)).toBe(true);
    expect(set).toHaveBeenCalledWith('gm:v1:u9', expect.any(String));
    expect(loadProgress('u9', TODAY)).toEqual(P);
    clearOther('nobody', TODAY);
    expect(loadProgress('u9', TODAY)).toBeNull();
  });

  it('O25 키는 gm:v1:<userId> 하나, 모양 밖 필드는 저장하지 않는다', () => {
    const { mem, src } = setup();
    saveProgress('u1', { ...P, token: 'x' } as Progress, src);
    expect(progressKey('u1')).toBe('gm:v1:u1');
    expect(mem.length).toBe(1);
    expect(Object.keys(JSON.parse(mem.getItem('gm:v1:u1')!)).sort()).toEqual([
      'date',
      'done',
      'passUsed',
      'questionId',
      'questionNo',
      'servedIds',
    ]);
  });

  it('O25 없음 · 다른 날짜 · 다른 유저 → 빔', () => {
    const { src } = setup();
    expect(loadProgress('u1', TODAY, src)).toBeNull();
    saveProgress('u1', P, src);
    expect(loadProgress('u1', '2026-11-12', src)).toBeNull();
    expect(loadProgress('u2', TODAY, src)).toBeNull();
  });

  it.each([
    ['깨진 JSON', '{"date":'],
    ['JSON 이 객체가 아님', '"x"'],
    ['null', 'null'],
    ['배열', '[]'],
  ])('O25 %s → 빔', (_n, raw) => {
    const { mem, src } = setup();
    mem.setItem('gm:v1:u1', raw);
    expect(loadProgress('u1', TODAY, src)).toBeNull();
  });

  const fields = ['date', 'questionNo', 'questionId', 'servedIds', 'passUsed', 'done'] as const;
  it.each(fields)('O25 모자란 필드 %s → 빔', (f) => {
    const { mem, src } = setup();
    const raw: Record<string, unknown> = { ...P };
    delete raw[f];
    mem.setItem('gm:v1:u1', JSON.stringify(raw));
    expect(loadProgress('u1', TODAY, src)).toBeNull();
  });

  it.each([
    ['questionNo 4', { questionNo: 4 }],
    ['questionNo 문자열', { questionNo: '1' }],
    ['questionId 대문자', { questionId: ID('B') }],
    ['servedIds 숫자', { servedIds: [1] }],
    ['passUsed 문자열', { passUsed: 'true' }],
    ['done null', { done: null }],
  ])('O25 틀린 값 %s → 빔', (_n, patch) => {
    const { mem, src } = setup();
    mem.setItem('gm:v1:u1', JSON.stringify({ ...P, ...patch }));
    expect(loadProgress('u1', TODAY, src)).toBeNull();
  });

  it('O25 로그인 때 다른 유저 · 지난 날짜 키 삭제, 오늘 내 키와 gm:v1 밖 키는 남긴다', () => {
    const { mem, src } = setup();
    saveProgress('me', P, src);
    saveProgress('other', P, src);
    mem.setItem('gm:v1:old', JSON.stringify({ ...P, date: '2026-11-10' }));
    mem.setItem('gm:me', 'me');
    mem.setItem('unrelated', '1');
    clearOther('me', TODAY, src);
    expect(loadProgress('me', TODAY, src)).toEqual(P);
    expect(mem.getItem('gm:v1:other')).toBeNull();
    expect(mem.getItem('gm:v1:old')).toBeNull();
    expect(mem.getItem('gm:me')).toBe('me');
    expect(mem.getItem('unrelated')).toBe('1');

    saveProgress('me', { ...P, date: '2026-11-10' }, src);
    clearOther('me', TODAY, src);
    expect(mem.getItem('gm:v1:me')).toBeNull();
  });

  it('O25 읽기 예외는 빔, 저장 예외는 false 로 알린다', () => {
    const { mem, src } = setup();
    saveProgress('u1', P, src);
    vi.spyOn(mem, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(loadProgress('u1', TODAY, src)).toBeNull();
    vi.spyOn(mem, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    expect(saveProgress('u1', P, src)).toBe(false);
    expect(() => clearOther('u1', TODAY, src)).not.toThrow();
  });

  it('O25 저장소 접근 자체가 예외여도 빔 · false', () => {
    const denied: StorageSource = () => {
      throw new DOMException('denied', 'SecurityError');
    };
    expect(loadProgress('u1', TODAY, denied)).toBeNull();
    expect(saveProgress('u1', P, denied)).toBe(false);
    expect(() => clearOther('u1', TODAY, denied)).not.toThrow();
  });
});
