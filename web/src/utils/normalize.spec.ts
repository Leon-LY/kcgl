import { describe, expect, it } from 'vitest'
import { normalizeNumericText, parseAmount, trimText } from './normalize'

describe('normalizeNumericText', () => {
  it('全角数字を半角へ変換する（IME 全角入力）', () => {
    expect(normalizeNumericText('１０００')).toBe('1000')
  })

  it('全角英字・記号も半角へ変換する', () => {
    expect(normalizeNumericText('ＨＴＫ')).toBe('HTK')
  })

  it('通貨記号・千分位・円表記・空白を除去する', () => {
    expect(normalizeNumericText('¥1,500')).toBe('1500')
    expect(normalizeNumericText('￥ 3 000 円')).toBe('3000')
  })

  it('空文字は空文字のまま', () => {
    expect(normalizeNumericText('')).toBe('')
  })
})

describe('parseAmount', () => {
  it('空文字は null（未入力と 0 を区別）', () => {
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('  ')).toBeNull()
  })

  it('0 は有効（費用は 0 円を許容）', () => {
    expect(parseAmount('0')).toBe(0)
  })

  it('全角・通貨記号混入を正しく解析する', () => {
    expect(parseAmount('１,２００円')).toBe(1200)
  })

  it('数字以外を含む入力は null（0 への暗黙変換をしない）', () => {
    expect(parseAmount('12ab')).toBeNull()
    expect(parseAmount('-5')).toBeNull()
    expect(parseAmount('1.5')).toBeNull()
  })

  it('安全整数範囲外は null', () => {
    expect(parseAmount('99999999999999999999')).toBeNull()
  })
})

describe('trimText', () => {
  it('自由テキストは前後空白のみ除去し NFKC しない（㈱/ⅩⅢ 保存）', () => {
    expect(trimText('  備考 ㈱テスト ⅩⅢ ')).toBe('備考 ㈱テスト ⅩⅢ')
  })
})
