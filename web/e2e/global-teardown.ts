import { spawnSync } from 'node:child_process'
import { hasStackMarker } from './stack-shared.mjs'

/**
 * E2E 收尾：删除一次性 MySQL 容器（Playwright 杀 webServer 进程树不覆盖容器，统一在此兜底）。
 *
 * 例外：`npm run e2e:stack` 起的常驻栈要留着给下一次迭代复用（那套的容器就是复用对象），
 * 有标记时不删——收摊交给 `npm run e2e:down`。
 */
export default async function globalTeardown(): Promise<void> {
  if (hasStackMarker()) {
    console.log('[e2e] 常驻栈保留（收摊：npm run e2e:down）')
    return
  }
  spawnSync('docker', ['rm', '-f', 'kcgl-e2e-mysql'], { stdio: 'ignore' })
}
