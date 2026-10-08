/**
 * 认证与本人账号：登录 / 登出 / 当前用户 / 改密 / 改语言 / 客户端报错上报。
 */
import { jsonInit, request } from './core'

// ------------------------------------------------------------------ 认证

export interface MeResponse {
  /** 用户主键：SSE 回声抑制比对基准（自己操作的广播不触发失效，D-070）。 */
  id: number
  username: string
  displayName: string
  role: number
  locale: string
  mustChangePwd: boolean
}

export const api = {
  /** 登录走框架 formLogin 过滤器（表单编码，D-021）。 */
  login(username: string, password: string): Promise<MeResponse> {
    const body = new URLSearchParams({ username, password })
    return request('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
  },
  logout(): Promise<void> {
    return request('/api/auth/logout', { method: 'POST' })
  },
  me(): Promise<MeResponse> {
    return request('/api/auth/me', { method: 'GET' })
  },
  changePassword(oldPassword: string, newPassword: string): Promise<void> {
    return request('/api/auth/me/password', jsonInit('PUT', { oldPassword, newPassword }))
  },
  changeLocale(locale: string): Promise<void> {
    return request('/api/auth/me/locale', jsonInit('PUT', { locale }))
  },
  reportClientError(payload: {
    message: string
    stack?: string
    route?: string
    locale?: string
    appVersion?: string
    errorId?: string
    queuePending?: number
    queueOldestAgeSec?: number
  }): Promise<void> {
    return request('/api/client-errors', jsonInit('POST', payload))
  },
}
