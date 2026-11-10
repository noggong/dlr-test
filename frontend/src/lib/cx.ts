/** 조건부 클래스 이름을 공백으로 잇는다. */
export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
