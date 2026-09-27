import { defineConfig, devices } from '@playwright/test'

/**
 * M1 E2E 冒烟：登录/语言切换/三角色/首登改密/双壳。
 * 栈为 hermetic 一次性 MySQL（e2e/start-backend.mjs）+ jar + Vite dev——与开发库零耦合。
 * 单栈共享（workers=1 串行），避免种子数据与会话竞争。
 */
const APP_PORT = process.env.E2E_APP_PORT ?? '18080'
const WEB_PORT = process.env.E2E_WEB_PORT ?? '5173'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: `http://127.0.0.1:${WEB_PORT}`,
  },
  projects: [
    { name: 'desktop-chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 5'] } },
  ],
  webServer: [
    {
      // 后端 jar + 一次性 MySQL（先跑 mvn package）
      command: 'node e2e/start-backend.mjs',
      url: `http://127.0.0.1:${APP_PORT}/actuator/health`,
      timeout: 300_000,
      reuseExistingServer: false,
    },
    {
      command: `npm run dev -- --port ${WEB_PORT} --strictPort`,
      url: `http://127.0.0.1:${WEB_PORT}`,
      timeout: 60_000,
      reuseExistingServer: false,
      env: {
        KCGL_API_TARGET: `http://127.0.0.1:${APP_PORT}`,
      },
    },
  ],
  globalTeardown: './e2e/global-teardown.ts',
})
