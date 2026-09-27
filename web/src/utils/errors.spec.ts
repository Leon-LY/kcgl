import { describe, expect, it } from 'vitest'
import { toDisplayMessage } from './errors'
import { ApiError } from './api'
import { i18n } from '@/i18n'

const t = i18n.global.t

describe('toDisplayMessage', () => {
  it('maps known error codes to localized messages', () => {
    const message = toDisplayMessage(new ApiError(401002, 'サーバー側メッセージ'), t)
    expect(message).toBe(t('errors.401002'))
  })

  it('renders the minutes-included message when a 423 lock carries remaining minutes', () => {
    const error = new ApiError(423001, 'locked', undefined, { remainingMinutes: 14 })
    expect(toDisplayMessage(error, t)).toBe(t('auth.lockedMinutes', { minutes: 14 }))
  })

  it('falls back to the error-code message when a 423 lock lacks remaining minutes', () => {
    const error = new ApiError(423001, 'locked')
    expect(toDisplayMessage(error, t)).toBe(t('errors.423001'))
  })

  it('falls back to the server message for unknown error codes (backend ja copy)', () => {
    const error = new ApiError(418001, '未知的服务端消息')
    expect(toDisplayMessage(error, t)).toBe('未知的服务端消息')
  })

  it('resolves code=0 local errors by the message symbol name', () => {
    expect(toDisplayMessage(new ApiError(0, 'NETWORK_ERROR'), t)).toBe(t('errors.NETWORK_ERROR'))
    expect(toDisplayMessage(new ApiError(0, 'INVALID_RESPONSE'), t)).toBe(t('errors.INVALID_RESPONSE'))
  })

  it('returns the generic error message for non-ApiError exceptions', () => {
    expect(toDisplayMessage(new Error('boom'), t)).toBe(t('common.error'))
  })
})
