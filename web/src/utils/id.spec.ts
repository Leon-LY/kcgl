import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { newClientId } from './id'

describe('newClientId (shared idempotency key for clientReqId/clientUuid)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('produces UUID-shaped values, unique on every call', () => {
    expect(newClientId()).toMatch(/^[0-9a-f-]{36}$/)
    expect(newClientId()).not.toBe(newClientId())
  })

  it('falls back to a timestamp key when randomUUID is absent (insecure context)', () => {
    // 复现 http://<サーバ>:<ポート> 访问：非安全上下文里 crypto.randomUUID 不存在。
    // 这里就是「详情里的删除不好用、只弹通用错误」的病根——回退分支若失效，该场景必抛 TypeError。
    vi.stubGlobal('crypto', { randomUUID: undefined })
    expect(newClientId()).toMatch(/^r-\d+-[a-z0-9]+$/)
  })
})

/** 源码根（`src/`）——扫描门的搜索起点。vitest 的 cwd 即前端工程根（web/）。 */
const SRC_DIR = join(process.cwd(), 'src')

function collectSources(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      collectSources(full, out)
    } else if (/\.(ts|vue)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) {
      out.push(full)
    }
  }
  return out
}

describe('idempotency key discipline', () => {
  it('never calls crypto.randomUUID outside this module', () => {
    // 本轮缺陷的守门测试（D-125 起因）：crypto.randomUUID 只在安全上下文（HTTPS / localhost）
    // 存在，而本系统按部署文档走 `http://<サーバ>:<ポート>`。桌面 item 目录曾四处裸调，
    // 在服务器上「删除／作废／复原／手工修正」四个动作全抛 TypeError，只显示通用错误。
    //
    // 门禁为何漏掉：单测与 E2E 都跑在 http://127.0.0.1（属安全上下文），恰好绕开这个前提。
    // 故本门不模拟浏览器，直接扫源码——凡新增裸调即在此失败，与运行环境无关。
    // 只认「调用形式」（带左括号）：注释里解释病因时会提到这个 API 名，不该被误判。
    const offenders = collectSources(SRC_DIR)
      .filter((file) => relative(SRC_DIR, file) !== join('utils', 'id.ts'))
      .filter((file) => readFileSync(file, 'utf8').includes('crypto.randomUUID('))
      .map((file) => relative(SRC_DIR, file).replace(/\\/g, '/'))

    expect(offenders).toEqual([])
  })
})
