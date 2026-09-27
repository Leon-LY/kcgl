import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { api, type MeResponse } from '@/utils/api'
import { i18n } from '@/i18n'

/**
 * 会话状态：me 快照 + 语言偏好联动（登录后采用账号 locale 并持久化到服务端；
 * 游客态语言选择仅本地记忆，登录后以账号设置为准）。
 */

const LOCALE_STORAGE_KEY = 'kcgl-locale'
const SUPPORTED_LOCALES = ['ja-JP', 'zh-CN', 'en-US'] as const
type AppLocale = (typeof SUPPORTED_LOCALES)[number]

function isSupportedLocale(value: string): value is AppLocale {
  return (SUPPORTED_LOCALES as readonly string[]).includes(value)
}

/** 服务端/本地存储的 locale 均为 string——校验后应用，非法值落回 ja-JP（系统默认）。 */
function applyLocale(locale: string): void {
  i18n.global.locale.value = isSupportedLocale(locale) ? locale : 'ja-JP'
}

export const useAuthStore = defineStore('auth', () => {
  const me = shallowRef<MeResponse | null>(null)
  const initialized = ref(false)

  /** 启动时恢复：本地语言偏好先应用（登录页即刻正确显示），再向服务端确认会话。 */
  async function initialize(): Promise<void> {
    const saved = localStorage.getItem(LOCALE_STORAGE_KEY)
    if (saved) {
      applyLocale(saved)
    }
    try {
      me.value = await api.me()
      applyLocale(me.value.locale)
    } catch {
      me.value = null // 未登录/会话过期——不抛出，路由守卫负责跳登录页
    } finally {
      initialized.value = true
    }
  }

  async function login(username: string, password: string): Promise<void> {
    me.value = await api.login(username, password)
    applyLocale(me.value.locale)
    localStorage.setItem(LOCALE_STORAGE_KEY, me.value.locale)
  }

  async function logout(): Promise<void> {
    try {
      await api.logout()
    } finally {
      me.value = null // 服务端不可达也要本地登出，防止界面残留会话态
    }
  }

  /** 切语言：即时生效 + 持久化（登录态下同步服务端，游客态仅本地）。 */
  async function setLocale(locale: string): Promise<void> {
    applyLocale(locale)
    localStorage.setItem(LOCALE_STORAGE_KEY, locale)
    if (me.value) {
      await api.changeLocale(locale)
      me.value = { ...me.value, locale }
    }
  }

  /** 401 全局处理回调用：仅清本地会话态（不调服务端——会话本身已失效）。 */
  function clearSession(): void {
    me.value = null
  }

  return { me, initialized, initialize, login, logout, setLocale, clearSession }
})
