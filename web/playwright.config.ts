import { defineConfig, devices } from '@playwright/test'
import { hasStackMarker } from './e2e/stack-shared.mjs'

/**
 * M1 E2E 冒烟：登录/语言切换/三角色/首登改密/双壳。
 * 栈为 hermetic 一次性 MySQL（e2e/start-backend.mjs）+ jar + Vite dev——与开发库零耦合。
 * 单栈共享（workers=1 串行），避免种子数据与会话竞争。
 *
 * 日常节奏（改一处就全量的那 4 分钟是可以省掉的）：
 *   改单个页面 → `npx playwright test e2e/<该页的 spec>.ts`（约 30s，栈自己起自己收）
 *   反复迭代   → `npm run e2e:stack` 起一套常驻栈，之后每次跑只用几秒（复用见下）
 *   推送前     → `npm run e2e:down` 后跑全量（干净库，CI 走的也是这条）
 * 全量之所以要 4 分钟，成本在 desktop 那 167s 的用例本身（占 86%），不在启动；
 * 复用栈省的是**迭代**的那 30s 固定成本。
 */
const APP_PORT = process.env.E2E_APP_PORT ?? '18080'
const WEB_PORT = process.env.E2E_WEB_PORT ?? '5173'

/**
 * 只复用 `npm run e2e:stack` 起的那套常驻栈（有标记才复用），不用 Playwright 默认的
 * `!process.env.CI`：那样本地只要有人把开发服务开在 5173/18080 上就会被卷进 E2E 并
 * 被用例改数据。没有标记时（含 CI）行为与从前一致——自己起、自己收。
 */
const REUSE_STACK = hasStackMarker()

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
    // WebKit 冒烟（docs/01 E2E 节）：iOS Safari 为最高优先适配目标，WebKit+iPhone
    // 视口/UA 是最接近的无真机代理——webkit-smoke.spec 跑登录/录入/扫码/盘点离线
    // 关键路径；其余 spec 仍只在 desktop-chromium 执行（各自 project 守卫）。
    // 真机全项见 docs/qa/device-matrix.md（G6）。
    { name: 'webkit-smoke', use: { ...devices['iPhone 13'] } },
    // 平板档（docs/07 §1 三档表：768–1024）。UA 里带 iPad → detectShell 判为 mobile，
    // 所以平板走的是移动壳，只是宽度到 768+：这一档原先零 @media、9 个页面各写一份
    // 560px，平板下就是"居中一条窄柱 + 大片空白"（"PC 页面压小了"的观感来源）。
    // 只跑 mobile-layout 的平板断言，其余 spec 仍由各自 project 守卫挡掉。
    {
      name: 'tablet-chromium',
      use: { ...devices['iPad Mini'], viewport: { width: 834, height: 1112 } },
    },
  ],
  webServer: [
    {
      // 后端 jar + 一次性 MySQL（先跑 mvn package）
      command: 'node e2e/start-backend.mjs',
      url: `http://127.0.0.1:${APP_PORT}/actuator/health`,
      timeout: 300_000,
      reuseExistingServer: REUSE_STACK,
    },
    {
      command: `npm run dev -- --port ${WEB_PORT} --strictPort`,
      url: `http://127.0.0.1:${WEB_PORT}`,
      timeout: 60_000,
      reuseExistingServer: REUSE_STACK,
      env: {
        KCGL_API_TARGET: `http://127.0.0.1:${APP_PORT}`,
      },
    },
  ],
  globalTeardown: './e2e/global-teardown.ts',
})
