import { defineStore } from 'pinia'
import { JST_TZ, dayjs } from '@/utils/format'
import type { ItemResponse } from '@/utils/api'

/**
 * 连续录入会话状态（docs/01 4.3 连续录入页）：
 * - 沿用上一件：会场/仓库/落札日期/单价（A13）；首件或跨日重置时落札日期默认当天 JST
 * - 「本日第 N 件」计数（JST 日界滚动清零）
 * - 落札日期沿用值 ≠ 今天时表单显示「前日」角标（多日拍卖会防第二日进错月桶）
 * 持久化 localStorage：杀进程/刷新后沿用与计数不丢（图片草稿走 Dexie，M2-5 接入）。
 */

const STORAGE_KEY = 'kcgl-entry-session'

interface CarryState {
  venueId: number | null
  warehouse: number
  buyDate: string
  purchasePrice: number | null
  todayCount: number
  /** 计数与沿用所属 JST 日期（YYYY-MM-DD）；跨日时重置。 */
  today: string
}

function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

function defaultCarry(today: string): CarryState {
  return { venueId: null, warehouse: 1, buyDate: today, purchasePrice: null, todayCount: 0, today }
}

function loadSaved(): CarryState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as CarryState
    if (typeof parsed.today !== 'string' || typeof parsed.todayCount !== 'number') {
      return null
    }
    return parsed
  } catch {
    return null
  }
}

function persist(state: CarryState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // 存储被禁用/满：沿用降级为仅本次会话内存态，不阻断录入
  }
}

export const useEntrySessionStore = defineStore('entrySession', {
  state: (): CarryState => loadSaved() ?? defaultCarry(todayJst()),

  getters: {
    /** 沿用落札日期不是今天（今天=当前 JST 日，而非 state.today——挂机跨日不重算的兜底）。 */
    isCarryDateStale(state): boolean {
      return state.buyDate !== todayJst() && state.todayCount > 0
    },
  },

  actions: {
    /** 挂载时调用：JST 日界滚动→计数清零、落札日期回默认今天（venue/仓/价仍沿用）。 */
    ensureToday(): void {
      const today = todayJst()
      if (this.today !== today) {
        const carried: CarryState = {
          ...defaultCarry(today),
          venueId: this.venueId,
          warehouse: this.warehouse,
          purchasePrice: this.purchasePrice,
        }
        this.$patch(carried)
        persist(carried)
      }
    },

    /** 保存成功后记录：沿用字段取自落库结果（以服务端为准），计数 +1。 */
    recordSaved(item: Pick<ItemResponse, 'venueId' | 'warehouse' | 'buyDate' | 'purchasePrice'>): void {
      const next: CarryState = {
        venueId: item.venueId,
        warehouse: item.warehouse,
        buyDate: item.buyDate,
        purchasePrice: item.purchasePrice,
        todayCount: this.todayCount + 1,
        today: todayJst(),
      }
      this.$patch(next)
      persist(next)
    },
  },
})
