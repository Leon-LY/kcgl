import type { Composer } from 'vue-i18n'
import { ApiError } from './api'

/**
 * ApiError → 用户可读文案的唯一出口：错误码优先（三语本地化），
 * 无对应文案时回退服务端消息（后端 ja 文案）；423 锁定附带剩余分钟。
 * code=0 的本地错误（网络断开/响应异常）用 message 里的符号名查 errors 命名空间。
 */
export function toDisplayMessage(error: unknown, t: Composer['t']): string {
  if (!(error instanceof ApiError)) {
    return t('common.error')
  }
  if (error.code === 423001) {
    const minutes = (error.data as { remainingMinutes?: number } | undefined)?.remainingMinutes
    if (minutes != null) {
      return t('auth.lockedMinutes', { minutes })
    }
  }
  const key = error.code === 0 ? `errors.${error.message}` : `errors.${error.code}`
  const localized = t(key)
  return localized === key ? error.message : localized
}

/**
 * 后端**落库**的结构化消息（code + params）→ 当前语言文案（D-127）。
 *
 * 与 {@link toDisplayMessage} 的区别在数据来源：那条链路是即时请求的 ApiError
 * 信封（错误码即本地化键）；这条是导入报告 note/reason/errorMessage 与系统告警
 * message 这类**持久化**文本——后端历史上只写日文句子，切语言也纹丝不动。
 * V5/V6 起后端同时写 code+params，此处按当前语言渲染。
 *
 * 三级回退：无 code（V5/V6 之前的历史行）→ fallback；键未收录（前端比后端新，
 * 或后端写了笔误键）→ fallback；命中 → t(code, params)。
 * 「缺键」判定沿用 `t(key)===key` 而非 `te()`：与 toDisplayMessage 同口径，
 * 且兼容测试环境的 i18n 桩。
 */
export function renderMessage(
  code: string | null | undefined,
  params: Record<string, unknown> | null | undefined,
  t: Composer['t'],
  fallback: string | null | undefined,
): string {
  const text = fallback ?? ''
  if (!code) {
    return text
  }
  if (t(code) === code) {
    return text
  }
  return t(code, params ?? {})
}

/** note_json 单条形态（与后端 Msg record 同构）。 */
interface StructuredMessage {
  code?: string | null
  params?: Record<string, unknown> | null
  text?: string | null
}

/**
 * 雅虎批次補注渲染：优先用结构化数组（逐条按语言渲染后重连），
 * 解析失败或为空（V5 之前的历史批次）则回退 note 原文。
 * 连接符走 i18n（ja/zh 用「、」，en 用「, 」）——同一条補注在英文里不该夹日文顿号。
 */
export function renderNoteJson(
  noteJson: string | null | undefined,
  note: string | null | undefined,
  t: Composer['t'],
): string | null {
  const messages = parseNoteJson(noteJson)
  if (messages.length === 0) {
    return note ?? null
  }
  return messages
    .map((m) => renderMessage(m.code, m.params, t, m.text))
    .join(t('imports.listSeparator'))
}

/** note_json 容错解析：非数组/坏 JSON 一律当空（历史行与脏数据都退回顾值）。 */
function parseNoteJson(noteJson: string | null | undefined): StructuredMessage[] {
  if (!noteJson) {
    return []
  }
  try {
    const parsed: unknown = JSON.parse(noteJson)
    return Array.isArray(parsed) ? (parsed as StructuredMessage[]) : []
  } catch {
    return []
  }
}

/**
 * 单条结构化消息渲染，但 params 在**线上是字符串**：JSON 列（批次的 errorMessageParams、
 * 告警的 messageParams）与实体字段同口径落为 String，故这里自行 parse。
 */
export function renderMessageJson(
  code: string | null | undefined,
  paramsJson: string | null | undefined,
  t: Composer['t'],
  fallback: string | null | undefined,
): string | null {
  return renderMessage(code, parseParams(paramsJson), t, fallback)
}

function parseParams(paramsJson: string | null | undefined): Record<string, unknown> | null {
  if (!paramsJson) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(paramsJson)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}
