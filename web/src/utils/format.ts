/**
 * 展示格式化唯一出口（docs/01 7.8：日付/货币解析全部经本模块，
 * JST 日期工具与 eslint 拦截规则 M1 接入）。
 */

/** 金额：日元无小数、ja-JP 千分位。null/undefined 渲染为占位符「—」。 */
export function formatYen(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—'
  return new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' }).format(amount)
}
