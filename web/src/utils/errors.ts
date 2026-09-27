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
