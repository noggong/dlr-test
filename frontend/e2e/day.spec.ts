import { expect, test } from '@playwright/test';
import {
  acceptDialogs,
  current,
  login,
  pass,
  progressOf,
  RUN,
  signup,
  solve,
  waitForQuiz,
} from './support';

// ORACLE O45 — 2명 그룹의 하루: 가입 · 로그인 → 대기 → 07:00 → a 패스 · 풀이 · 새로고침 이어 풀기 → b 현황판(SSE) → b 풀이 → 그룹 성공 → 결과
test('O45 2명 그룹의 하루', async ({ browser }) => {
  const idA = `a_${RUN}`;
  const idB = `b_${RUN}`;
  const group = `e2e ${RUN}`;
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const a = await ctxA.newPage();
  const b = await ctxB.newPage();
  acceptDialogs(a);
  acceptDialogs(b);

  await test.step('가입 · 로그인 → 대기', async () => {
    await signup(a, idA, { mode: 'new', name: group });
    await signup(b, idB, { mode: 'join', name: group });
    await login(a, idA);
    await login(b, idB);
  });

  await test.step('07:00 전환', async () => {
    await Promise.all([waitForQuiz(a), waitForQuiz(b)]);
  });

  await test.step('a — 1번 패스 → 1번 풀이 · b 현황판에 1/3', async () => {
    expect((await current(a)).no).toBe(1);
    await pass(a);
    await solve(a);
    // b 는 새로고침하지 않는다 — 실시간(SSE member.progressed)으로만 바뀐다
    await expect(progressOf(b, idA, 1)).toBeVisible();
    await expect(
      b.getByRole('status').filter({ hasText: `${idA} 님이 1번까지 풀었어요` }),
    ).toBeVisible();
  });

  await test.step('a — 2번에서 새로고침 → 같은 문제로 이어 감', async () => {
    const before = await current(a);
    expect(before.no).toBe(2);
    await a.reload();
    await expect(a).toHaveURL(/\/quiz$/);
    expect(await current(a)).toEqual(before);
    // 패스는 그날 끝까지 잠긴 채다(W7)
    await expect(a.getByRole('button', { name: '패스 사용함' })).toBeDisabled();
  });

  await test.step('a — 2 · 3번 풀이 → 완료 · b 현황판에 3/3', async () => {
    await solve(a);
    await expect(progressOf(b, idA, 2)).toBeVisible();
    await solve(a);
    await expect(a.getByRole('heading', { name: '3문제를 다 풀었어요' })).toBeVisible();
    await expect(progressOf(b, idA, 3)).toBeVisible();
  });

  await test.step('b — 3문제 → 그룹 성공 대화상자', async () => {
    for (let i = 0; i < 3; i++) await solve(b);
    for (const p of [a, b]) {
      await expect(p.getByRole('dialog', { name: '그룹 미션 성공!' })).toBeVisible();
    }
  });

  await test.step('결과 화면', async () => {
    for (const p of [a, b]) {
      await p
        .getByRole('dialog', { name: '그룹 미션 성공!' })
        .getByRole('link', { name: '결과 보기' })
        .click();
      await expect(p).toHaveURL(/\/result$/);
      await expect(p.getByRole('heading', { name: '그룹 미션 성공' })).toBeVisible();
    }
  });

  await ctxA.close();
  await ctxB.close();
});
