import { describe, expect, test } from 'vitest'
import { i18n } from './index'
import ja from './locales/ja-JP.json'
import zh from './locales/zh-CN.json'
import en from './locales/en-US.json'

// 三语契约（执行期原则）：所有文案必须日中英三语齐备，切换语言时全量同步。
// 本测试是 CI 强制层——任一语言漏配 key、插值参数不一致、空文案，当场失败；
// vue-i18n 的 missing-key 静默回退（返回 key 本身）不会在页面上暴露，必须在这里拦。
// 渲染契约测试额外拦「消息语法被 vue-i18n 特殊解析」类缺陷（如 ASCII | 被当复数分支）。

type Messages = Record<string, unknown>

const LOCALES: Record<string, Messages> = {
  'ja-JP': ja,
  'zh-CN': zh,
  'en-US': en,
}

const LOCALE_NAMES = ['ja-JP', 'zh-CN', 'en-US'] as const

/** 递归展平为「点路径 → 文案」映射。 */
function flatten(messages: Messages, prefix = ''): Map<string, string> {
  const result = new Map<string, string>()
  for (const [key, value] of Object.entries(messages)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (typeof value === 'string') {
      result.set(path, value)
    } else if (value && typeof value === 'object') {
      for (const [subPath, subValue] of flatten(value as Messages, path)) {
        result.set(subPath, subValue)
      }
    }
  }
  return result
}

/** 提取插值参数集合（{minutes} 等），保证三语参数一致。 */
function placeholders(text: string): Set<string> {
  return new Set([...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]))
}

describe('i18n trilingual contract', () => {
  const flattened = Object.fromEntries(
    Object.entries(LOCALES).map(([locale, messages]) => [locale, flatten(messages)]),
  ) as Record<string, Map<string, string>>
  const jaKeys = [...flattened['ja-JP']!.keys()].sort()
  const peers = ['zh-CN', 'en-US'] as const

  test('key trees are identical across the three locale files (ja-JP as baseline)', () => {
    for (const locale of peers) {
      const otherKeys = new Set(flattened[locale]!.keys())
      const missing = jaKeys.filter((key) => !otherKeys.has(key))
      const extra = [...otherKeys].filter((key) => !flattened['ja-JP']!.has(key))
      expect(missing, `${locale} 缺少 key`).toEqual([])
      expect(extra, `${locale} 多出 key`).toEqual([])
    }
  })

  test('interpolation placeholders match across locales for the same key', () => {
    for (const key of jaKeys) {
      const expected = placeholders(flattened['ja-JP']!.get(key)!)
      for (const locale of peers) {
        expect(placeholders(flattened[locale]!.get(key)!), `${locale}:${key} 插值参数不一致`).toEqual(expected)
      }
    }
  })

  test('has no empty messages', () => {
    for (const [locale, entries] of Object.entries(flattened)) {
      for (const [key, value] of entries) {
        expect(value.trim().length > 0, `${locale}:${key} 为空文案`).toBe(true)
      }
    }
  })

  test('every message renders through vue-i18n identically to direct template interpolation (guards against message-syntax misuse)', () => {
    // vue-i18n 消息里 ASCII | 是复数分支、@ 是链接语法——误用时 t() 静默丢内容
    // （实测：「{page} | {app}」渲染成「{page}」）。本测试要求每个 key 的实际渲染
    // 与「字面模板插值」逐字一致，任何被特殊解析的写法当场暴露。
    // 如未来确需复数/链接语法，对应 key 移入显式期望用例，不走本泛化断言。
    const SAMPLE = 'X'
    for (const locale of LOCALE_NAMES) {
      i18n.global.locale.value = locale
      for (const [key, template] of flattened[locale]!) {
        const params: Record<string, string> = {}
        for (const name of placeholders(template)) {
          params[name] = SAMPLE
        }
        expect(i18n.global.t(key, params), `${locale}:${key} 渲染与模板不一致`).toBe(
          interpolate(template, params),
        )
      }
    }
  })
})

/** 按模板直接插值：先展开 vue-i18n 字面量语法 {'|'}，再替换 {param}。 */
function interpolate(template: string, params: Record<string, string>): string {
  return template
    .replace(/\{'([^']*)'\}/g, '$1')
    .replace(/\{(\w+)\}/g, (_, name: string) => params[name] ?? `{${name}}`)
}
