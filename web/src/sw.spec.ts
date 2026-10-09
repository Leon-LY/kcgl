import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * SW 更新语义的静态守卫（D-162）。
 *
 * sw.ts 是 worker 脚本、依赖 workbox 与 self，在 vitest 里跑不起来——这不是
 * 「没空写行为测试」，而是**行为测试在这里建不起防线**：真出问题的地方是
 * 「新 SW 装好却停在 waiting」，那要两个构建版本 + 真实浏览器才能复现。
 * 故退一步守源码契约：这三句只要还在，autoUpdate 的语义就成立。
 *
 * cwd 而不是 import.meta.url：vitest 里的 import.meta.url 不是 file: 方案，
 * fileURLToPath 会抛错；vitest 的 cwd 即配置根（web/）。
 */
describe('service worker update contract', () => {
  const source = readFileSync(`${process.cwd()}/src/sw.ts`, 'utf8')

  it('calls skipWaiting so a new worker activates instead of waiting forever', () => {
    expect(source).toMatch(/^self\.skipWaiting\(\)$/m)
  })

  it('claims clients so the new worker takes over already-open tabs', () => {
    expect(source).toMatch(/^clientsClaim\(\)$/m)
    expect(source).toContain("from 'workbox-core'")
  })

  it('binds navigations to the precached shell that those two calls must refresh', () => {
    expect(source).toContain("createHandlerBoundToURL('index.html')")
  })
})
