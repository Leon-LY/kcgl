import { spawnSync } from 'node:child_process'

/** E2E 收尾：删除一次性 MySQL 容器（Playwright 杀 webServer 进程树不覆盖容器，统一在此兜底）。 */
export default async function globalTeardown(): Promise<void> {
  spawnSync('docker', ['rm', '-f', 'kcgl-e2e-mysql'], { stdio: 'ignore' })
}
