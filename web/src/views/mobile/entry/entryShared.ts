import { JST_TZ, dayjs } from '@/utils/format'

/**
 * 录入表单里日期选择器共用的常量与换算（D-148）。
 *
 * 抽出来的理由：照片字段（EntryPhotoField）与表单主体各持一到两个 van-date-picker，
 * 共用同一个可选日期下限与同一套「日期串 ↔ 三段选择值」换算。这些符号原是 EntryForm
 * 的私有定义，子组件要用就只能隔空依赖一个非导出符号、或者各抄一份，于是按本仓库既有的
 * 按域收拢做法（desktop/item 下那两个 shared 模块同款）收到这里。
 */

// 落札日历下界 2016（业务起点防误选）；Vant 日历边界取本地语义的日历日（列渲染读本地 Y/M/D）
export const MIN_DATE = dayjs('2016-01-01').toDate()

/** JST 今日（YYYY-MM-DD）。 */
export function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

/** JST 今日 23:59（Date）：日期选择器上限；+08 深夜开发时默认值与上限保持一致。 */
export function jstTodayEnd(): Date {
  return dayjs().tz(JST_TZ).endOf('day').toDate()
}

/** 日期串 → van-date-picker 的三段值（年/月/日）。 */
export function toPickerValues(date: string): string[] {
  return date ? [date.slice(0, 4), pad2(date.slice(5, 7)), pad2(date.slice(8, 10))] : []
}

/** van-date-picker 的三段值 → 日期串。 */
export function fromPickerValues(values: Array<string | number>): string {
  return `${values[0]}-${pad2(String(values[1]))}-${pad2(String(values[2]))}`
}

function pad2(value: string): string {
  return String(Number(value)).padStart(2, '0')
}
