/**
 * 盘点：会话开启 / 扫码计数 / 差异表 / 确定。
 */
import { jsonInit, request } from './core'
import type { ActionResult } from './inventory'
import type { ItemResponse } from './item'

// ------------------------------------------------------------------ 盘点（M3-⑥，docs/01 7.3）

/** 盘点单摘要（发起/列表/详情/close 共用）。 */
export interface StocktakeSummary {
  id: number
  stocktakeNo: string
  warehouse: number
  /** 0进行中 1待确认（已 close） 2已确认 3作废。 */
  status: number
  /** close 时冻结的期望数（该仓在库未删未废），未 close 为 null。 */
  expectedCount: number | null
  scannedCount: number
  diffCount: number | null
  /** 待确认差异数（全部处理完单据自动转已确认），未 close 为 null。 */
  pendingDiffCount: number | null
  createdAt: string
  closedAt: string | null
  createdByName: string
  closedByName: string | null
  /** 当前登录用户是否发起人（撤销按钮渲染依据；服务端仍强校验发起人身份）。 */
  mine: boolean
}

/** 发起盘点：同仓已有进行中单 → 409009（前端引导跳转既有单）。 */
export function createStocktake(warehouse: number): Promise<StocktakeSummary> {
  return request('/api/stocktakes', jsonInit('POST', { warehouse }))
}

export interface StocktakeList {
  total: number
  page: number
  size: number
  rows: StocktakeSummary[]
}

/** 盘点单列表（创建时间倒序）；status 省略=全部。 */
export function fetchStocktakes(page: number, size = 20, status?: number): Promise<StocktakeList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (status != null) {
    query.set('status', String(status))
  }
  return request(`/api/stocktakes?${query}`, { method: 'GET' })
}

export function fetchStocktake(id: number): Promise<StocktakeSummary> {
  return request(`/api/stocktakes/${id}`, { method: 'GET' })
}

/** 盘点扫码：repeated=同单同件重复扫（服务端照记一次，200 不报错）。 */
export interface StocktakeScanResult {
  repeated: boolean
  item: ItemResponse
  thumbUrl: string | null
}

/** 盘点扫码（卡内警示——他仓货/冻结品/系统非在库——由前端按 item 状态派生，照记不拦）。 */
export function scanStocktakeItem(stocktakeId: number, code: string): Promise<StocktakeScanResult> {
  return request(`/api/stocktakes/${stocktakeId}/scans`, jsonInit('POST', { code }))
}

/** close：冻结期望集合并生成差异表（盘点期间的自然变动落入差异，由人工裁决）。 */
export function closeStocktake(stocktakeId: number): Promise<StocktakeSummary> {
  return request(`/api/stocktakes/${stocktakeId}/close`, jsonInit('POST', {}))
}

/** 发起人撤自己的单（仅进行中可撤；非发起人 403001）。 */
export function cancelStocktake(stocktakeId: number): Promise<StocktakeSummary> {
  return request(`/api/stocktakes/${stocktakeId}/cancel`, jsonInit('POST', {}))
}

/** 差异行：diffType 1盘亏 2盘盈 3仓库不符 4冻结品（冻结品禁止 CONFIRM，仅可 IGNORE/线下处理）。 */
export interface StocktakeDiffRow {
  id: number
  itemId: number
  itemCode: string
  diffType: number
  expectedWarehouse: number | null
  actualWarehouse: number | null
  /** 含「盘点期间发生过变动流水」标注（辅助裁决）。 */
  note: string | null
  /** 0待确认 1确认调整 2忽略。 */
  confirmStatus: number
  thumbUrl: string | null
}

export interface StocktakeDiffList {
  total: number
  page: number
  size: number
  rows: StocktakeDiffRow[]
}

/** 差异表（待确认优先）；confirmStatus 省略=全部。 */
export function fetchStocktakeDiffs(
  stocktakeId: number,
  page: number,
  size = 20,
  confirmStatus?: number,
): Promise<StocktakeDiffList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (confirmStatus != null) {
    query.set('confirmStatus', String(confirmStatus))
  }
  return request(`/api/stocktakes/${stocktakeId}/diffs?${query}`, { method: 'GET' })
}

/** 差异处理结果：result 仅 CONFIRM 返回（STOCKTAKE_ADJUST 后的商品现态）。 */
export interface StocktakeDiffActionResponse {
  diff: StocktakeDiffRow
  result: ActionResult | null
}

/** CONFIRM 幂等键契约同库存动作（7.0）：失败重试复用同键；IGNORE 状态置位即原结果，天然幂等。 */
export function resolveStocktakeDiff(
  stocktakeId: number,
  diffId: number,
  action: 'CONFIRM' | 'IGNORE',
  clientReqId: string,
): Promise<StocktakeDiffActionResponse> {
  return request(
    `/api/stocktakes/${stocktakeId}/diffs/${diffId}`,
    jsonInit('POST', { action, clientReqId }),
  )
}
