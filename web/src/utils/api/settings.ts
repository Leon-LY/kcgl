/**
 * 系统设置与统计：首启 checklist、门店 / 仓库等基础配置、大盘统计。
 */
import { jsonInit, request } from './core'

// ------------------------------------------------------------------ 首启 checklist（M2-8b-3）

export interface Checklist {
  hasStaffUser: boolean
  hasVenue: boolean
  hasPriceBand: boolean
  hasItem: boolean
  printDone: boolean
}

export function fetchChecklist(): Promise<Checklist> {
  return request('/api/checklist', { method: 'GET' })
}

/** 试打标签完成标记（幂等；仅管理员）。 */
export function markChecklistPrintDone(): Promise<Checklist> {
  return request('/api/checklist/print-done', jsonInit('POST', {}))
}


// ------------------------------------------------------------------ 设置与统计（M5-③）

/** 系统设置快照（GET /api/settings 与 PUT 后响应同构；未落库键=服务端默认值）。 */
export interface SettingsData {
  /** 滞销黄色阈值（天） */
  warnDays: number
  /** 滞销红色阈值（天） */
  alarmDays: number
  labelPreset: 'small' | 'medium' | 'large' | 'custom'
  /** 自定义标签幅（mm，仅 labelPreset=custom 时被打印页消费） */
  labelWidthMm: number
  /** 自定义标签高（mm） */
  labelHeightMm: number
}

export function fetchSettings(): Promise<SettingsData> {
  return request('/api/settings', { method: 'GET' })
}

/**
 * 更新单个设置键（管理员）。具体值校验在前端预检，服务端兜底 400017；
 * 黄红跨字段关系（warn<alarm）由调用方按「另一侧现值」排出 PUT 顺序。
 */
export function updateSetting(key: string, value: string): Promise<SettingsData> {
  return request(`/api/settings/${encodeURIComponent(key)}`, jsonInit('PUT', { value }))
}

/** 单仓/全仓运营指标行（warehouse=null=全仓合计；件均库龄无样本为 null）。 */
export interface WarehouseStats {
  warehouse: number | null
  totalItems: number
  inTransit: number
  inStock: number
  shipped: number
  monthInbound: number
  monthOutbound: number
  slowWarn: number
  slowRed: number
  stockValue: number
  avgStockAgeDays: number | null
}

/** 雅虎同步卡：三活视图口径计数 + 最近一次成功受注导入的新鲜度（无成功批次为 null）。 */
export interface YahooStats {
  soldNotShipped: number
  canceledNotRelisted: number
  withdrawNeeded: number
  lastImportFinishedAt: string | null
  lastImportFilename: string | null
}

export interface DashboardStats {
  fleet: WarehouseStats
  yahoo: YahooStats
}

/** 大盘（全仓合计+雅虎同步卡）；页加载拉取，无 SSE（聚合快照非协作面）。 */
export function fetchDashboardStats(): Promise<DashboardStats> {
  return request('/api/stats/dashboard', { method: 'GET' })
}

/** 两仓明细（名古屋/福岡各一行，序固定 1→2）。 */
export function fetchWarehouseStats(): Promise<WarehouseStats[]> {
  return request('/api/stats/warehouses', { method: 'GET' })
}
