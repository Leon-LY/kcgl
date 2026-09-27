import { describe, expect, it } from 'vitest'
import { formatYen } from './format'

describe('formatYen', () => {
  it('千分位无小数', () => {
    const formatted = formatYen(1234567).replace(/[￥¥]/g, '')
    expect(formatted).toBe('1,234,567')
    expect(formatYen(0)).not.toContain('.')
  })

  it('空值渲染占位符', () => {
    expect(formatYen(null)).toBe('—')
    expect(formatYen(undefined)).toBe('—')
  })
})
