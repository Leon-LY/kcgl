/**
 * 账号管理（管理员）。
 */
import { jsonInit, request } from './core'

// ------------------------------------------------------------------ 账号管理（M2-8b-3）

/** locked 为即时计算值（lockedUntil 晚于当前时刻），非列直传。 */
export interface AdminUser {
  id: number
  username: string
  displayName: string
  role: number
  locale: string
  enabled: boolean
  mustChangePwd: boolean
  locked: boolean
  lockedUntil: string | null
  failedAttempts: number
  lastLoginAt: string | null
}

export interface AdminUserPage {
  list: AdminUser[]
  total: number
  page: number
  size: number
}

export function fetchUsers(page = 1, size = 50): Promise<AdminUserPage> {
  return request(`/api/users?page=${page}&size=${size}`, { method: 'GET' })
}

/** 创建：username 不可改；密码为管理员设定的初始密码（首登强制改密）。 */
export function createUser(payload: {
  username: string
  displayName: string
  role: number
  locale: string
  password: string
}): Promise<AdminUser> {
  return request('/api/users', jsonInit('POST', payload))
}

export function updateUser(
  id: number,
  payload: { displayName: string; role: number; locale: string },
): Promise<AdminUser> {
  return request(`/api/users/${id}`, jsonInit('PUT', payload))
}

export function setUserStatus(id: number, enabled: boolean): Promise<AdminUser> {
  return request(`/api/users/${id}/status`, jsonInit('PATCH', { enabled: enabled ? 1 : 0 }))
}

/** 手动解锁（清锁定与失败计数；对应登录防爆破 15 分钟锁）。 */
export function unlockUser(id: number): Promise<AdminUser> {
  return request(`/api/users/${id}/unlock`, jsonInit('PATCH', {}))
}

/** 重置密码：初始密码仅本响应出现一次，管理员转交用户后首登强制改密。 */
export function resetUserPassword(id: number): Promise<{ initialPassword: string }> {
  return request(`/api/users/${id}/password-reset`, jsonInit('POST', {}))
}
