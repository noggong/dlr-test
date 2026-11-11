import { ApiError } from '@/api/http';

// 가입 · 로그인 오류를 화면으로 옮긴다(SPEC §5 · §6.3). 분기는 code 로만 한다 — message 는 보지 않는다(W16 · I8).

export type SignupMode = 'new' | 'join';

export interface SignupValues {
  loginId: string;
  password: string;
  mode: SignupMode;
  groupName: string;
}

/** 입력칸 아래 오류 문구 · 그룹 칸 「10/10」 칩 · 폼 아래 Callout(bad). */
export interface SignupErrors {
  loginId?: string;
  password?: string;
  groupName?: string;
  groupFull?: boolean;
  alert?: string;
}

export interface LoginErrors {
  /** 자격 증명 불일치 — 두 칸을 빨갛게 하고 시안 fail 문구(W13) */
  fail?: boolean;
  alert?: string;
}

export const MSG = {
  full: (name: string) =>
    `「${name}」 그룹은 10명이 모두 찼어요. 다른 그룹명을 입력하거나 새 그룹을 만드세요.`,
  dup: '이미 있는 그룹명이에요. 그 그룹에 들어가려면 「기존 그룹 들어가기」를 고르세요.',
  notFound: '없는 그룹이에요. 그룹명을 다시 확인해 주세요.',
  loginIdTaken: '이미 쓰는 아이디예요. 다른 아이디를 입력해 주세요.',
  loginIdRule: '영문 소문자 · 숫자 · _ 로 4~20자를 입력해 주세요.',
  passwordRule: '공백 없이 영문 · 숫자 · 특수문자로 8~64자를 입력해 주세요.',
  groupNameRule: '한글 · 영문 · 숫자 · 공백 · - · _ 로 2~20자를 입력해 주세요.',
  invalidRequest:
    '입력 규칙에 맞지 않는 칸이 있어요. 아이디 · 비밀번호 · 그룹명을 다시 확인해 주세요.',
  loginFail: '아이디 또는 비밀번호가 맞지 않아요. 다시 확인해 주세요.',
  alreadyLoggedIn:
    '다른 기기에서 로그인 중이에요. 그 기기에서 로그아웃하거나 최대 2시간 뒤 다시 시도하세요',
  retryLater: '잠시 뒤 다시 열어 주세요.',
} as const;

const LOGIN_ID = /^[a-z0-9_]{4,20}$/; // AS1
const PASSWORD = /^[\x21-\x7E]{8,64}$/; // AS2
const GROUP_CHARS = /^[가-힣A-Za-z0-9 _-]+$/; // AS3 ④

/** 서버가 받는 모양으로 그룹명을 맞춘다(AS3 ① ②) — 화면 문구에도 이 표기를 쓴다. */
export function cleanGroupName(raw: string): string {
  return raw.normalize('NFC').trim();
}

/** 보내기 전에 AS1 ~ AS3 을 본다 — 틀린 칸에 규칙 안내. 다 맞으면 빈 객체. */
export function validateSignup(v: SignupValues): SignupErrors {
  const errors: SignupErrors = {};
  if (!LOGIN_ID.test(v.loginId)) errors.loginId = MSG.loginIdRule;
  if (!PASSWORD.test(v.password)) errors.password = MSG.passwordRule;
  const name = cleanGroupName(v.groupName);
  const len = [...name].length;
  if (len < 2 || len > 20 || !GROUP_CHARS.test(name)) errors.groupName = MSG.groupNameRule;
  return errors;
}

export function hasErrors(e: SignupErrors): boolean {
  return Object.values(e).some(Boolean);
}

/** 가입 실패 → 화면(SPEC §6.3). */
export function signupErrorView(e: unknown, v: SignupValues): SignupErrors {
  if (!(e instanceof ApiError)) return { alert: MSG.retryLater };
  switch (e.code) {
    case 'GROUP_FULL':
      return { groupName: MSG.full(cleanGroupName(v.groupName)), groupFull: true };
    case 'GROUP_NAME_TAKEN':
      return { groupName: MSG.dup };
    case 'GROUP_NOT_FOUND':
      return { groupName: MSG.notFound };
    case 'LOGIN_ID_TAKEN':
      return { loginId: MSG.loginIdTaken };
    case 'INVALID_REQUEST':
      return { alert: MSG.invalidRequest };
    default:
      return { alert: MSG.retryLater };
  }
}

/** 로그인 실패 → 화면(SPEC W13 · §6.3). 무엇이 틀렸는지는 말하지 않는다. */
export function loginErrorView(e: unknown): LoginErrors {
  if (!(e instanceof ApiError)) return { alert: MSG.retryLater };
  switch (e.code) {
    case 'INVALID_CREDENTIALS':
    case 'INVALID_REQUEST':
      return { fail: true };
    case 'ALREADY_LOGGED_IN':
      return { alert: MSG.alreadyLoggedIn };
    default:
      return { alert: MSG.retryLater };
  }
}
