import { describe, expect, it } from 'vitest'
import { LABEL_PRESETS, chunkSheets, splitItemCode, type LabelEntry } from './label'

/**
 * 标签预置与切页（M2-7）：栅格算术是手算校核值（docs/01 4.3），单测锁死——
 * 单值漂移（改宽度忘改间距）当场暴露而非打印出错一整版。
 */

function entry(id: number): LabelEntry {
  return {
    item: { id, itemCode: `HTK9-A${id}X`, buyDate: '2026-09-15', venueCode: 'HT', thumbUrl: null },
    qr: 'data:image/png;base64,QR',
  }
}

describe('标签预置栅格（A4=210×297mm）', () => {
  it.each(LABEL_PRESETS)('$key：横向 2·padX+cols·w+(cols−1)·gap=210', (preset) => {
    expect(
      2 * preset.padXMm + preset.cols * preset.widthMm + (preset.cols - 1) * preset.colGapMm,
    ).toBe(210)
  })

  it.each(LABEL_PRESETS)('$key：纵向 2·padY+rows·h+(rows−1)·gap=297', (preset) => {
    expect(
      2 * preset.padYMm + preset.rows * preset.heightMm + (preset.rows - 1) * preset.rowGapMm,
    ).toBe(297)
  })

  it('每页容量：65／36／33（预置文档口径）', () => {
    expect(LABEL_PRESETS.map((p) => p.cols * p.rows)).toEqual([65, 36, 33])
  })

  it('缩略图仅 50×30 与 70×25 支持（38×21 放不下，旅程 M7）', () => {
    expect(LABEL_PRESETS.map((p) => p.thumbSupported)).toEqual([false, true, true])
  })
})

describe('chunkSheets 切页', () => {
  it('空列表无页', () => {
    expect(chunkSheets([], LABEL_PRESETS[0]!)).toEqual([])
  })

  it('恰好一页容量不多切', () => {
    const sheets = chunkSheets(
      Array.from({ length: 65 }, (_, i) => entry(i + 1)),
      LABEL_PRESETS[0]!,
    )
    expect(sheets).toHaveLength(1)
    expect(sheets[0]).toHaveLength(65)
  })

  it('跨页按录入顺序切（65+1 → 两页，第二页 1 件）', () => {
    const sheets = chunkSheets(
      Array.from({ length: 66 }, (_, i) => entry(i + 1)),
      LABEL_PRESETS[0]!,
    )
    expect(sheets).toHaveLength(2)
    expect(sheets[1]).toHaveLength(1)
    expect(sheets[1]![0]!.item.id).toBe(66)
  })
})

describe('splitItemCode 人读码分段', () => {
  it('会场/日期段带连字符、流水段独立（HTK9-A1X）', () => {
    expect(splitItemCode('HTK9-A1X')).toEqual({ head: 'HTK9-', tail: 'A1X' })
  })

  it('多字母前缀（HTK9-AA12X）只按首个连字符分段', () => {
    expect(splitItemCode('HTK9-AA12X')).toEqual({ head: 'HTK9-', tail: 'AA12X' })
  })

  it('无连字符的异常输入原样返回不抛错', () => {
    expect(splitItemCode('HTK9A1X')).toEqual({ head: 'HTK9A1X', tail: '' })
  })
})
