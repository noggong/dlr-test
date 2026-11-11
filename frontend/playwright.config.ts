import { defineConfig, devices } from '@playwright/test';

// 종단 테스트(ORACLE O45) — e2e/run.sh 가 compose 를 띄우고 E2E_* 를 넘긴다(LLD §3.8). 직접 부르면 띄워 둔 스택을 쓴다.
export default defineConfig({
  testDir: './e2e',
  // 시나리오 둘(하루 · 부정 실패)이 모두 07:00 전에 가입해야 하므로 함께 시작한다
  fullyParallel: true,
  workers: 2,
  retries: 0,
  forbidOnly: true,
  // 07:00 전환을 기다리는 시간(E2E_LEAD_SEC) + 하루 흐름
  timeout: 6 * 60_000,
  expect: { timeout: 15_000 },
  reporter: 'list',
  outputDir: 'test-results',
  use: {
    // crypto.subtle 은 localhost 에서만 준다(HLD §6)
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
