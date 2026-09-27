import type { ItemSummary } from '@/utils/api'

/**
 * 标签排版规格（M2-7，docs/01 4.3/7.7）：A4 不干胶三预置。
 * 栅格数学（210×297mm）：2·padX + cols·w + (cols−1)·colGap = 210、
 * 2·padY + rows·h + (rows−1)·rowGap = 297——预置值经手算校核，勿随手改单值。
 * 自定义尺寸/单件重打（n=1）在 M5 详情页与 sys_setting 接入（D-036）。
 */

export type LabelPresetKey = 'small' | 'medium' | 'large'

export interface LabelPreset {
  key: LabelPresetKey
  /** 标签宽高（mm） */
  widthMm: number
  heightMm: number
  cols: number
  rows: number
  colGapMm: number
  rowGapMm: number
  padXMm: number
  padYMm: number
  /** 是否有缩略图位（38×21 放不下，docs/01 旅程 M7） */
  thumbSupported: boolean
  /** QR 码边长（mm，含 quiet zone） */
  qrMm: number
  /** 缩略图宽（mm） */
  thumbMm: number
  /** 人读码字号（pt）：会场日期段 / 流水段 / 落札日 */
  headPt: number
  tailPt: number
  datePt: number
}

export const LABEL_PRESETS: LabelPreset[] = [
  // 38×21：5×13=65 面／枚（2·6+5·38+4·2=210、2·6+13·21+12·1=297）
  {
    key: 'small',
    widthMm: 38,
    heightMm: 21,
    cols: 5,
    rows: 13,
    colGapMm: 2,
    rowGapMm: 1,
    padXMm: 6,
    padYMm: 6,
    thumbSupported: false,
    qrMm: 13,
    thumbMm: 0,
    headPt: 6.5,
    tailPt: 8.5,
    datePt: 5.5,
  },
  // 50×30：4×9=36 面／枚（2·3.5+4·50+3·1=210、2·5.5+9·30+8·2=297）
  {
    key: 'medium',
    widthMm: 50,
    heightMm: 30,
    cols: 4,
    rows: 9,
    colGapMm: 1,
    rowGapMm: 2,
    padXMm: 3.5,
    padYMm: 5.5,
    thumbSupported: true,
    qrMm: 16,
    thumbMm: 18,
    headPt: 8,
    tailPt: 11,
    datePt: 6.5,
  },
  // 70×25：3×11=33 面／枚（3 列恰满 210 宽、2·1+11·25+10·2=297）
  {
    key: 'large',
    widthMm: 70,
    heightMm: 25,
    cols: 3,
    rows: 11,
    colGapMm: 0,
    rowGapMm: 2,
    padXMm: 0,
    padYMm: 1,
    thumbSupported: true,
    qrMm: 15,
    thumbMm: 20,
    headPt: 8,
    tailPt: 11,
    datePt: 6.5,
  },
]

/** 打印页单件条目：摘要 + 前端生成的二维码 dataURL（生成失败为 null，人读码兜底）。 */
export interface LabelEntry {
  item: ItemSummary
  qr: string | null
}

/** 按预置每页容量把条目切成页（录入顺序即贴件顺序，跨页不回填）。 */
export function chunkSheets(entries: LabelEntry[], preset: LabelPreset): LabelEntry[][] {
  const perSheet = preset.cols * preset.rows
  const sheets: LabelEntry[][] = []
  for (let i = 0; i < entries.length; i += perSheet) {
    sheets.push(entries.slice(i, i + perSheet))
  }
  return sheets
}

/** 人读码分段（7.7：会场/日期段与流水段分行，QR 破损手输兜底前提）。 */
export function splitItemCode(code: string): { head: string; tail: string } {
  const idx = code.indexOf('-')
  if (idx === -1) {
    return { head: code, tail: '' }
  }
  return { head: `${code.slice(0, idx)}-`, tail: code.slice(idx + 1) }
}
