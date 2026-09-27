/**
 * 展示格式化唯一出口（docs/01 7.8：日期/货币解析全部经本模块）。
 * 服务端 DATETIME 一律是 naive JST 字符串（全库 Asia/Tokyo），必须
 * dayjs.tz(str, 'Asia/Tokyo') 解析——dayjs(str) 按设备本地时区解析是经典错法：
 * 开发机 +08 显示差 1 小时、到日本生产才「自愈」。eslint 已拦截裸 new Date。
 */

import dayjsLib from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

dayjsLib.extend(utc)
dayjsLib.extend(timezone)

export const JST_TZ = 'Asia/Tokyo'

/** 配置好 utc+timezone 插件的 dayjs（组件库日期组件绑定用；解析 JST 字符串仍须走下方 JST 帮助函数）。 */
export const dayjs = dayjsLib

/** 金额：日元无小数、ja-JP 千分位。null/undefined 渲染为占位符「—」。 */
export function formatYen(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—'
  return new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' }).format(amount)
}

/** 日期（naive JST → 日本業務慣行 YYYY/MM/DD）。空值「—」。 */
export function formatJstDate(value: string | null | undefined): string {
  if (!value) return '—'
  return dayjsLib.tz(value, JST_TZ).format('YYYY/MM/DD')
}

/** 日期时间（naive JST → YYYY/MM/DD HH:mm，秒不展示）。空值「—」。 */
export function formatJstDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return dayjsLib.tz(value, JST_TZ).format('YYYY/MM/DD HH:mm')
}

/** <input type="date"> 取值（ISO YYYY-MM-DD）。空值空串（受控组件契约）。 */
export function toDateInputValue(value: string | null | undefined): string {
  if (!value) return ''
  return dayjsLib.tz(value, JST_TZ).format('YYYY-MM-DD')
}
