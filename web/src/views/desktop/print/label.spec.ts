import { describe, expect, it } from 'vitest'
import { LABEL_PRESETS, chunkSheets, splitItemCode, type LabelEntry } from './label'

/**
 * 标签预置与切页（M2-7）：栅格算术是手算校核值（docs/01 4.3），单测锁死——
 * 单值漂移（改宽度忘改间距）当场暴露而非打印出错一整版。
 */

function entry(id: number): LabelEntry {
  return {
    item: { id, itemCode: `HT9-A${id}X`, buyDate: '2026-09-15', venueCode: 'HT', thumbUrl: null },
    qr: 'data:image/png;base64,QR',
  }
}

describe('label preset grid (A4 = 210×297mm)', () => {
  it.each(LABEL_PRESETS)('$key: horizontal 2·padX+cols·w+(cols−1)·gap=210', (preset) => {
    expect(
      2 * preset.padXMm + preset.cols * preset.widthMm + (preset.cols - 1) * preset.colGapMm,
    ).toBe(210)
  })

  it.each(LABEL_PRESETS)('$key: vertical 2·padY+rows·h+(rows−1)·gap=297', (preset) => {
    expect(
      2 * preset.padYMm + preset.rows * preset.heightMm + (preset.rows - 1) * preset.rowGapMm,
    ).toBe(297)
  })

  it('per-sheet capacity: 65/36/33 (per the preset documentation)', () => {
    expect(LABEL_PRESETS.map((p) => p.cols * p.rows)).toEqual([65, 36, 33])
  })

  it('supports thumbnails on 50×30 and 70×25 only (38×21 has no room, journey M7)', () => {
    expect(LABEL_PRESETS.map((p) => p.thumbSupported)).toEqual([false, true, true])
  })
})

describe('chunkSheets sheet splitting', () => {
  it('returns no sheets for an empty list', () => {
    expect(chunkSheets([], LABEL_PRESETS[0]!)).toEqual([])
  })

  it('does not split when the count exactly fills one sheet', () => {
    const sheets = chunkSheets(
      Array.from({ length: 65 }, (_, i) => entry(i + 1)),
      LABEL_PRESETS[0]!,
    )
    expect(sheets).toHaveLength(1)
    expect(sheets[0]).toHaveLength(65)
  })

  it('splits overflow in entry order (65+1 → two sheets, one entry on the second)', () => {
    const sheets = chunkSheets(
      Array.from({ length: 66 }, (_, i) => entry(i + 1)),
      LABEL_PRESETS[0]!,
    )
    expect(sheets).toHaveLength(2)
    expect(sheets[1]).toHaveLength(1)
    expect(sheets[1]![0]!.item.id).toBe(66)
  })
})

describe('splitItemCode human-readable code segmentation', () => {
  it('keeps the hyphen with the venue/date head and separates the sequence tail (HT9-A1X)', () => {
    expect(splitItemCode('HT9-A1X')).toEqual({ head: 'HT9-', tail: 'A1X' })
  })

  it('splits multi-letter prefixes (HT9-AA12X) at the first hyphen only', () => {
    expect(splitItemCode('HT9-AA12X')).toEqual({ head: 'HT9-', tail: 'AA12X' })
  })

  it('returns hyphen-less malformed input as-is without throwing', () => {
    expect(splitItemCode('HT9A1X')).toEqual({ head: 'HT9A1X', tail: '' })
  })
})
