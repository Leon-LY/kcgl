/**
 * 输入归一化（docs/01 7.8 IME 唯一出口）：
 * - 代码/金额字段仅在 blur/submit 归一化——输入中即时转换会打断日文 IME 组合输入
 *   （断裂/光标跳动/吞字符，日文 Web 经典事故）
 * - 自由文本（备注等）仅 trim，绝不 NFKC（㈱/ⅩⅢ 等合法字符会被变形）
 */

/** 金额/编号类字段：NFKC 全角→半角，去货币记号/千分位/空白。 */
export function normalizeNumericText(input: string): string {
  return input
    .normalize('NFKC')
    .replace(/[¥￥]/g, '')
    .replace(/,/g, '')
    .replace(/円/g, '')
    .replace(/\s+/g, '')
}

/** 自由文本：仅去首尾空白。 */
export function trimText(input: string): string {
  return input.trim()
}

/**
 * 管理号（扫码页手输兜底）：NFKC 全角→半角 + 去空白 + 大写化。
 * 与后端 by-code 同一规则（docs/01 7.8）；不去数字以外字符——管理号格式由服务端正则兜底。
 */
export function normalizeItemCode(input: string): string {
  return input.normalize('NFKC').replace(/\s+/g, '').toUpperCase()
}

/** 解析金额输入（先归一化）；空/非法 → null（不静默吞成 0）；0 为合法值（费用可 0）。 */
export function parseAmount(input: string): number | null {
  const normalized = normalizeNumericText(input)
  if (normalized === '') {
    return null
  }
  if (!/^\d+$/.test(normalized)) {
    return null
  }
  const value = Number(normalized)
  return Number.isSafeInteger(value) && value >= 0 ? value : null
}
