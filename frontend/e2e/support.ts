import { execFileSync } from 'node:child_process';
import { expect, type Locator, type Page } from '@playwright/test';

// 종단 테스트 공용 — 화면 조작은 사람이 보는 이름(라벨 · 역할)으로만 한다.

/** 로컬 전용 가짜 비밀번호(AS2 — 8자 이상 출력 가능 ASCII). */
export const PASSWORD = 'e2e-local-only';

/** 이 실행만의 꼬리 — 아이디 · 그룹명이 앞 실행과 겹치지 않게(AS1 `[a-z0-9_]`). */
export const RUN = Date.now().toString(36).slice(-6);

/** 시나리오 시간 제한 — 07:00 까지 기다림 + 하루 흐름(E2E_LEAD_SEC 를 크게 줘도 맞게). */
export function scenarioTimeout(): number {
  return untilStartMs() + 4 * 60_000;
}

/** 07:00 전환까지 남은 시간 + 여유. run.sh 가 넘긴 실제 시각(ms)이 없으면 넉넉히. */
export function untilStartMs(): number {
  const start = Number(process.env.E2E_GAME_START_MS);
  return (Number.isFinite(start) && start > 0 ? Math.max(0, start - Date.now()) : 180_000) + 30_000;
}

/** 새로고침 때 `beforeunload` 확인 창(SPEC W24)을 받아들인다 — 기본은 닫기(= 머물기)라 새로고침이 멈춘다. */
export function acceptDialogs(page: Page): void {
  page.on('dialog', (d) => void d.accept());
}

let answers: Map<string, string> | undefined;

/**
 * 문제 본문 → 시드 정답. 테스트만 postgres `questions` 를 한 번 읽는다(앱 코드에 정답 우회로를 두지 않는다 — LLD §3.8).
 * 컨테이너는 run.sh 의 전용 프로젝트(E2E_COMPOSE_PROJECT)의 postgres 서비스다.
 */
export function answerOf(body: string): string {
  answers ??= loadAnswers();
  const answer = answers.get(body);
  if (answer === undefined) throw new Error(`시드에 없는 문제 본문: ${body}`);
  return answer;
}

function loadAnswers(): Map<string, string> {
  const project = process.env.E2E_COMPOSE_PROJECT;
  if (!project)
    throw new Error('E2E_COMPOSE_PROJECT 가 없습니다 — pnpm e2e(e2e/run.sh)로 돌리세요');
  const id = execFileSync(
    'docker',
    [
      'ps',
      '-q',
      '--filter',
      `label=com.docker.compose.project=${project}`,
      '--filter',
      'label=com.docker.compose.service=postgres',
    ],
    { encoding: 'utf8' },
  ).trim();
  if (!id) throw new Error(`프로젝트 ${project} 의 postgres 컨테이너가 없습니다`);
  const out = execFileSync(
    'docker',
    [
      'exec',
      id,
      'sh',
      '-c',
      `psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -A -t -F '\t' -c 'SELECT body, answer FROM questions'`,
    ],
    { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 },
  );
  const map = new Map<string, string>();
  for (const line of out.split('\n')) {
    const tab = line.indexOf('\t');
    if (tab > 0) map.set(line.slice(0, tab), line.slice(tab + 1));
  }
  if (map.size === 0) throw new Error('questions 가 비었습니다');
  return map;
}

/**
 * 가입(화면). 완료 화면의 「오늘부터 / 날짜부터」는 서버가 실제 가입 시각(DB now())으로 정한 firstGameDate 로 그려
 * 여기서는 보지 않는다(단위 테스트 O18) — 07:00 전인지는 로그인 뒤 대기 지면으로 본다(LLD §3.8).
 */
export async function signup(
  page: Page,
  loginId: string,
  group: { mode: 'new' | 'join'; name: string },
) {
  await page.goto('/signup');
  await page.getByLabel('아이디', { exact: true }).fill(loginId);
  await page.getByLabel('비밀번호', { exact: true }).fill(PASSWORD);
  if (group.mode === 'join') {
    await page.getByRole('tab', { name: '기존 그룹 들어가기' }).click();
    await page.getByLabel('들어갈 그룹명', { exact: true }).fill(group.name);
  } else {
    await page.getByLabel('새 그룹명', { exact: true }).fill(group.name);
  }
  await page.getByRole('button', { name: '가입하기' }).click();
  await expect(page.getByRole('heading', { name: '가입했어요' })).toBeVisible();
}

/** 로그인(화면) → 07:00 전이라 대기 지면. 기동이 늦어 07:00 을 넘겼으면 여기서 실패한다. */
export async function login(page: Page, loginId: string) {
  await page.goto('/login');
  await page.getByLabel('아이디', { exact: true }).fill(loginId);
  await page.getByLabel('비밀번호', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page, '로그인이 게임 시각 07:00 을 넘겼다 — E2E_LEAD_SEC 를 늘린다').toHaveURL(
    /\/waiting$/,
  );
  await expect(page.getByRole('heading', { name: /후 게임 시작$/ })).toBeVisible();
}

/** 07:00(서버 시각)에 대기 지면이 스스로 문제 풀이로 넘어간다. */
export async function waitForQuiz(page: Page) {
  await expect(page).toHaveURL(/\/quiz$/, { timeout: untilStartMs() });
}

export interface Shown {
  no: number;
  text: string;
}

function card(page: Page): Locator {
  return page.locator('section[aria-label$="번 문제"]');
}

/** 지금 화면의 문제(번호 · 본문). */
export async function current(page: Page): Promise<Shown> {
  const c = card(page);
  await expect(c).toBeVisible();
  const label = (await c.getAttribute('aria-label')) ?? '';
  const text = ((await c.locator('p').first().textContent()) ?? '').trim();
  return { no: Number.parseInt(label, 10), text };
}

/** 지금 문제를 시드 정답으로 푼다 → 다음 번호 문제, 3번이면 완료 지면. */
export async function solve(page: Page): Promise<Shown> {
  const q = await current(page);
  const c = card(page);
  await c.getByLabel('답', { exact: true }).fill(answerOf(q.text));
  await c.getByRole('button', { name: '제출', exact: true }).click();
  if (q.no < 3) {
    await expect(page.locator(`section[aria-label="${q.no + 1}번 문제"]`)).toBeVisible();
  } else {
    await expect(page).toHaveURL(/\/done$/);
  }
  return q;
}

/** 패스 — 확인 시트 → 같은 번호 · 다른 문제. */
export async function pass(page: Page): Promise<Shown> {
  const before = await current(page);
  await card(page).getByRole('button', { name: '패스 1회 남음' }).click();
  await page
    .getByRole('dialog', { name: `${before.no}번 문제를 패스할까요?` })
    .getByRole('button', { name: '패스하기' })
    .click();
  await expect(page.getByText('패스했어요', { exact: false })).toBeVisible();
  await expect(card(page).getByRole('button', { name: '패스 사용함' })).toBeDisabled();
  const after = await current(page);
  expect(after.no).toBe(before.no);
  expect(after.text).not.toBe(before.text);
  return after;
}

/** 현황판(문제 풀이 · 완료 지면의 「그룹 현황」)에서 그 유저 줄의 진행도. */
export function progressOf(page: Page, loginId: string, n: number): Locator {
  return page
    .getByRole('region', { name: '그룹 현황' })
    .locator('li')
    .filter({ hasText: loginId })
    .getByRole('img', { name: `진행도 ${n}/3` });
}
