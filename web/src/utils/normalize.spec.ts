import { describe, expect, it } from 'vitest'
import { normalizeNumericText, parseAmount, trimText } from './normalize'

describe('normalizeNumericText', () => {
  it('converts full-width digits to half-width (IME full-width input)', () => {
    expect(normalizeNumericText('１０００')).toBe('1000')
  })

  it('converts full-width letters and symbols to half-width', () => {
    expect(normalizeNumericText('ＨＴ９')).toBe('HT9')
  })

  it('strips currency symbols, thousand separators, yen notation, and whitespace', () => {
    expect(normalizeNumericText('¥1,500')).toBe('1500')
    expect(normalizeNumericText('￥ 3 000 円')).toBe('3000')
  })

  it('keeps an empty string empty', () => {
    expect(normalizeNumericText('')).toBe('')
  })
})

describe('parseAmount', () => {
  it('returns null for an empty string (distinguishes unset from 0)', () => {
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('  ')).toBeNull()
  })

  it('treats 0 as valid (fees allow zero yen)', () => {
    expect(parseAmount('0')).toBe(0)
  })

  it('parses input mixing full-width digits and currency symbols correctly', () => {
    expect(parseAmount('１,２００円')).toBe(1200)
  })

  it('returns null for input containing non-digits (no implicit coercion to 0)', () => {
    expect(parseAmount('12ab')).toBeNull()
    expect(parseAmount('-5')).toBeNull()
    expect(parseAmount('1.5')).toBeNull()
  })

  it('returns null outside the safe integer range', () => {
    expect(parseAmount('99999999999999999999')).toBeNull()
  })
})

describe('trimText', () => {
  it('trims only surrounding whitespace without NFKC normalization (preserves ㈱/ⅩⅢ)', () => {
    expect(trimText('  備考 ㈱テスト ⅩⅢ ')).toBe('備考 ㈱テスト ⅩⅢ')
  })
})
