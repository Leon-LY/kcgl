import { defineStore } from 'pinia'
import { fetchSettings, type SettingsData } from '@/utils/api'

/**
 * 系统设置缓存（M5-③）：打印页（标签规格默认值+自定义尺寸）页级低频读。
 * 与 dicts 同款单例缓存：SSE SETTING 失效（他人改动）由 sync store 调 reload；
 * 设置页自身走 PUT 响应回显（操作者自己的屏幕不靠广播，D-070 回声抑制）。
 * 慢漂移防御： loaded 前消费方按 null 兜底，不臆造默认值。
 */

interface SettingsState {
  data: SettingsData | null
  loaded: boolean
  loading: boolean
}

// 进行中的加载 Promise 提到模块级：并发调用 ensureLoaded 共享同一次请求
let inflight: Promise<void> | null = null

export const useSettingsStore = defineStore('settings', {
  state: (): SettingsState => ({
    data: null,
    loaded: false,
    loading: false,
  }),

  actions: {
    async ensureLoaded(): Promise<void> {
      if (this.loaded) {
        return
      }
      if (!inflight) {
        this.loading = true
        inflight = (async () => {
          try {
            this.data = await fetchSettings()
            this.loaded = true
          } finally {
            this.loading = false
            inflight = null
          }
        })()
      }
      return inflight
    },

    /** 强制重取（SSE SETTING 失效广播）。失败保持旧值——下次失效或重挂载重试。 */
    async reload(): Promise<void> {
      this.loaded = false
      try {
        await this.ensureLoaded()
      } catch {
        // 重取失败不外抛给广播链路（与 dicts.reload 同策略）；ensureLoaded 已复位 loaded
      }
    },
  },
})
