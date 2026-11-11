import { expect, test } from '@playwright/test';
import {
  acceptDialogs,
  current,
  login,
  PASSWORD,
  RUN,
  scenarioTimeout,
  waitForQuiz,
} from './support';

// 부정 실패(SPEC W22 · prd R-F2) — 미션을 시작한 뒤 로컬 스토리지가 비면 새로고침에서 실패 신호 → 완료 지면 「실패 처리」
test('부정 실패 — 로컬 스토리지를 지우고 새로고침하면 /done 실패 처리', async ({
  page,
  request,
}) => {
  test.setTimeout(scenarioTimeout());
  const idC = `c_${RUN}`;
  const idD = `d_${RUN}`;
  const group = `e2e cheat ${RUN}`;
  acceptDialogs(page);

  await test.step('가입(API — 화면 가입은 하루 시나리오가 본다) · 로그인 → 대기', async () => {
    for (const [loginId, mode] of [
      [idC, 'NEW'],
      [idD, 'EXISTING'],
    ] as const) {
      const res = await request.post('/api/v1/auth/signup', {
        data: { loginId, password: PASSWORD, group: { mode, name: group } },
      });
      expect(res.status(), await res.text()).toBe(201);
    }
    await login(page, idC);
  });

  await test.step('07:00 → 1번 문제(미션 시작)', async () => {
    await waitForQuiz(page);
    expect((await current(page)).no).toBe(1);
  });

  await test.step('로컬 스토리지를 지우고 새로고침 → 실패 처리', async () => {
    // 함수 대신 식 문자열 — e2e 는 Node 타입만 본다(DOM lib 없음)
    await page.evaluate('window.localStorage.clear()');
    await page.reload();
    await expect(page).toHaveURL(/\/done$/);
    await expect(page.getByRole('heading', { name: '오늘 미션은 실패 처리됐어요' })).toBeVisible();
  });
});
