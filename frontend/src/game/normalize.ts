// 정답 정규화(SPEC W19 · prd R-A2 · D22). 서버 AnswerNormalizer 와 같은 순서 · 같은 문자 목록이어야 맞는 답이 맞는다.

/** prd D22 표의 「공백으로 보는 문자」 33개 — 테스트가 공유 벡터의 `removed` 와 대조한다. */
export const REMOVED_CODE_POINTS: readonly number[] = [
  // Unicode White_Space 전부(25)
  0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x0085, 0x00a0, 0x1680, 0x2000, 0x2001, 0x2002,
  0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f,
  0x3000,
  // 폭 0 문자(5)
  0x200b, 0x200c, 0x200d, 0x2060, 0xfeff,
  // 예전 공백(1)
  0x180e,
  // 한글 채움 문자(2)
  0x3164, 0xffa0,
];

const REMOVED = new Set(REMOVED_CODE_POINTS);

/**
 * ① 표의 문자를 위치 상관없이 전부 지운다 → ② NFC(NFKC 아님) → ③ A-Z 만 a-z.
 * 로캘 소문자(`toLowerCase`)는 쓰지 않는다 — `İ` 등이 런타임마다 다르게 바뀐다.
 */
export function normalizeAnswer(s: string): string {
  let kept = '';
  for (const ch of s) {
    if (!REMOVED.has(ch.codePointAt(0)!)) kept += ch;
  }
  return kept.normalize('NFC').replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32));
}
