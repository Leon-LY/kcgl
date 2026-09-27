import { defineStore } from 'pinia'
import {
  fetchPriceBands,
  fetchVenues,
  type PriceBand,
  type Venue,
} from '@/utils/api'

/**
 * 字典缓存（会场/价格档位）：录入页高频读、低频变。
 * 管理员改字典后调 reload 失效；SSE 字典变更事件接入后由其触发 reload（M3）。
 */

interface DictsState {
  venues: Venue[]
  bands: PriceBand[]
  loaded: boolean
  loading: boolean
  loadError: string | null
}

// 进行中的加载 Promise 提到模块级：并发调用 ensureLoaded 共享同一次请求，
// 后到者不空手而归（loading 布尔守卫存在「B 返回时 A 尚未写回」竞态）。
let inflight: Promise<void> | null = null

export const useDictsStore = defineStore('dicts', {
  state: (): DictsState => ({
    venues: [],
    bands: [],
    loaded: false,
    loading: false,
    loadError: null,
  }),

  getters: {
    /** 录入下拉只给启用会场（D-031：停用会场补录走后端，前端无入口）。 */
    enabledVenues(state): Venue[] {
      return state.venues.filter((venue) => venue.enabled)
    },
  },

  actions: {
    async ensureLoaded(): Promise<void> {
      if (this.loaded) {
        return
      }
      if (!inflight) {
        this.loading = true
        this.loadError = null
        inflight = (async () => {
          try {
            const [venues, bands] = await Promise.all([fetchVenues(true), fetchPriceBands()])
            this.venues = venues
            this.bands = bands
            this.loaded = true
          } catch (error) {
            this.loadError = error instanceof Error ? error.message : String(error)
            throw error
          } finally {
            this.loading = false
            inflight = null
          }
        })()
      }
      return inflight
    },

    /** 强制重取（字典管理改动/SSE 失效广播）。 */
    async reload(): Promise<void> {
      this.loaded = false
      return this.ensureLoaded()
    },

    /**
     * 本地档位匹配（左闭右开，与服务端 PriceBandService 同语义）：
     * 录入页单价输入的即时档位字母预览走本地，免一次网络往返；
     * 最终档位以保存时服务端计算为准（快照落库）。
     */
    matchBand(price: number | null): PriceBand | null {
      if (price == null || !Number.isFinite(price) || price <= 0) {
        return null
      }
      return (
        this.bands.find(
          (band) =>
            band.enabled &&
            (band.lowerBound == null || price >= band.lowerBound) &&
            (band.upperBound == null || price < band.upperBound),
        ) ?? null
      )
    },
  },
})
