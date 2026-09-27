import { describe, expect, it } from 'vitest'
import { formatJstDate, formatJstDateTime, formatYen, toDateInputValue } from './format'

// 本套件运行于 TZ=Asia/Shanghai（vite.config.ts test.env 钉死，docs/01 7.8）：
// 故意用与 JST 不同的本地时区跑边界用例——若实现误用本地解析（dayjs(str)），
// +08 环境下 23:59 用例会偏移到次日 00:59，测试当场失败。

describe('formatYen', () => {
  it('formats thousands separators without decimals', () => {
    const formatted = formatYen(1234567).replace(/[￥¥]/g, '')
    expect(formatted).toBe('1,234,567')
    expect(formatYen(0)).not.toContain('.')
  })

  it('renders the placeholder for nullish amounts', () => {
    expect(formatYen(null)).toBe('—')
    expect(formatYen(undefined)).toBe('—')
  })
})

describe('formatJstDate / formatJstDateTime (naive JST parsing)', () => {
  it('renders the 23:59 boundary on the same JST day without drifting to the next day in local time', () => {
    // 错误实现（本地解析 + tz 转换）在 +08 下会得到 2026/01/16 00:59
    expect(formatJstDate('2026-01-15 23:59')).toBe('2026/01/15')
    expect(formatJstDateTime('2026-01-15 23:59')).toBe('2026/01/15 23:59')
  })

  it('renders the 00:30 boundary on the same JST day without being pushed an hour later by local parsing', () => {
    // 错误实现在 +08 下会得到 2026/01/15 01:30
    expect(formatJstDateTime('2026-01-15 00:30')).toBe('2026/01/15 00:30')
  })

  it('formats month-start dates and truncates seconds', () => {
    expect(formatJstDate('2026-03-01')).toBe('2026/03/01')
    expect(formatJstDateTime('2026-03-01 09:05:33')).toBe('2026/03/01 09:05')
  })

  it('renders the placeholder for empty input', () => {
    expect(formatJstDate(null)).toBe('—')
    expect(formatJstDate('')).toBe('—')
    expect(formatJstDate(undefined)).toBe('—')
    expect(formatJstDateTime(null)).toBe('—')
  })
})

describe('toDateInputValue (controlled date input contract)', () => {
  it('outputs ISO YYYY-MM-DD', () => {
    expect(toDateInputValue('2026-01-15 23:59')).toBe('2026-01-15')
    expect(toDateInputValue('2026-01-15')).toBe('2026-01-15')
  })

  it('outputs an empty string for empty values (never null)', () => {
    expect(toDateInputValue(null)).toBe('')
    expect(toDateInputValue('')).toBe('')
  })
})
