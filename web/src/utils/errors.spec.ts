import { describe, expect, it } from 'vitest'
import { toDisplayMessage } from './errors'
import { ApiError } from './api'
import { i18n } from '@/i18n'

const t = i18n.global.t

describe('toDisplayMessage', () => {
  it('已知错误码 → 三语本地化文案', () => {
    const message = toDisplayMessage(new ApiError(401002, 'サーバー側メッセージ'), t)
    expect(message).toBe(t('errors.401002'))
  })

  it('423 锁定携带剩余分钟 → 带分钟的文案', () => {
    const error = new ApiError(423001, 'locked', undefined, { remainingMinutes: 14 })
    expect(toDisplayMessage(error, t)).toBe(t('auth.lockedMinutes', { minutes: 14 }))
  })

  it('423 无剩余分钟数据 → 回退错误码文案', () => {
    const error = new ApiError(423001, 'locked')
    expect(toDisplayMessage(error, t)).toBe(t('errors.423001'))
  })

  it('未知错误码 → 回退服务端消息（后端 ja 文案）', () => {
    const error = new ApiError(418001, '未知的服务端消息')
    expect(toDisplayMessage(error, t)).toBe('未知的服务端消息')
  })

  it('code=0 本地错误 → 按 message 符号名查文案', () => {
    expect(toDisplayMessage(new ApiError(0, 'NETWORK_ERROR'), t)).toBe(t('errors.NETWORK_ERROR'))
    expect(toDisplayMessage(new ApiError(0, 'INVALID_RESPONSE'), t)).toBe(t('errors.INVALID_RESPONSE'))
  })

  it('非 ApiError 异常 → 通用错误文案', () => {
    expect(toDisplayMessage(new Error('boom'), t)).toBe(t('common.error'))
  })
})
