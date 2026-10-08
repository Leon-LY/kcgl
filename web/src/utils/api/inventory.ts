/**
 * 到货核对，与扫码定位 / 库存动作（入库、出库、改仓、作废）。
 *
 * 这两段同属「货怎么动」的一条链：扫码拿到的就是商品主档，动作回执与到货
 * 共用同一套结果形状，拆开反而要来回跳文件。
 */
import { jsonInit, request } from './core'
import type { ItemResponse } from './item'

// ------------------------------------------------------------------ 到货核对（M2-8a）

/** 在途清单行：卡片=缩略图+管理号+落札日+预计仓库。 */
export interface PendingArrival {
  id: number
  itemCode: string
  buyDate: string
  thumbUrl: string | null
  warehouse: number
}

export interface PendingArrivalList {
  total: number
  page: number
  size: number
  rows: PendingArrival[]
}

/** warehouse=null 全部仓库；服务端已排除已入库/作废/软删件。 */
export function fetchPendingArrivals(warehouse: number | null, page: number, size = 20): Promise<PendingArrivalList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (warehouse != null) {
    query.set('warehouse', String(warehouse))
  }
  return request(`/api/inventory/arrivals/pending?${query}`, { method: 'GET' })
}

/**
 * 确认入库行：clientReqId=行级幂等键（重试复用同键，服务端读回原结果）。
 * warehouse/shelfNo=到仓改仓与上架货架（A7）：不填则该行沿用录入时的预计仓库/棚番号。
 */
export interface ConfirmArrivalLine {
  itemId: number
  clientReqId: string
  warehouse?: number
  shelfNo?: string
}

export interface ConfirmArrivalResult {
  arrivedCount: number
  items: { itemId: number; itemCode: string; stockStatus: number; warehouse: number }[]
}

/** 批量确认入库：同批全成全败；warehouseInDate 缺省=今天 JST（录入预填件服务端不覆盖）。 */
export function confirmArrivals(
  items: ConfirmArrivalLine[],
  warehouseInDate?: string,
): Promise<ConfirmArrivalResult> {
  const payload = warehouseInDate ? { items, warehouseInDate } : { items }
  return request('/api/inventory/arrivals', jsonInit('POST', payload))
}

// ------------------------------------------------------------------ 本日录入会话（M2-8b）

/** 会话行：大字管理号+缩略图+作废标记（对数口径含作废件）。 */
export interface TodaySessionRow {
  id: number
  itemCode: string
  voided: boolean
  voidReason: string | null
  /** 录入时刻 HH:mm（JST） */
  createdAt: string
  thumbUrl: string | null
}

export interface TodaySession {
  date: string
  activeCount: number
  voidedCount: number
  rows: TodaySessionRow[]
}

/** 我的当天录入会话（收工对数）：不分页，个人日清单量级为数十件。 */
export function fetchTodaySession(): Promise<TodaySession> {
  return request('/api/items/today-session', { method: 'GET' })
}


// ------------------------------------------------------------------ 扫码定位与库存动作（M3-④/⑤）

/** 扫码定位响应：item 含作废/软删件（前端按标志呈现禁操作态）；reEntry 未重录为 null。 */
export interface ItemByCode {
  item: ItemResponse
  /** 首图缩略图（确认卡单次往返即得，无图为 null）。 */
  thumbUrl: string | null
  /** 作废重录链终点（扫旧码提示「重录后的新号」）；未重录为 null。 */
  reEntry: { itemId: number; itemCode: string } | null
}

/** 管理号定位（NFKC+大写化由后端兜底，前端手输已先归一）。404=号不存在。 */
export function fetchItemByCode(code: string): Promise<ItemByCode> {
  return request(`/api/items/by-code/${encodeURIComponent(code)}`, { method: 'GET' })
}

/** 动作端点统一结果（动作后现态快照，确认卡据此刷新）。 */
export interface ActionResult {
  itemId: number
  itemCode: string
  stockStatus: number
  saleStatus: number
  warehouse: number
}

/** 五动作幂等键语义（docs/01 7.0）：失败重试复用同键，成功后才生成新键。 */
export function sellItem(itemId: number, clientReqId: string, soldPrice?: number): Promise<ActionResult> {
  const payload = soldPrice == null ? { itemId, clientReqId } : { itemId, clientReqId, soldPrice }
  return request('/api/inventory/sell', jsonInit('POST', payload))
}

export function scrapItem(itemId: number, clientReqId: string, reason: string): Promise<ActionResult> {
  return request('/api/inventory/scrap', jsonInit('POST', { itemId, clientReqId, reason }))
}

export function transferItem(itemId: number, clientReqId: string, toWarehouse: number): Promise<ActionResult> {
  return request('/api/inventory/transfer', jsonInit('POST', { itemId, clientReqId, toWarehouse }))
}

/** direction：1=顾客退回（已出库→在库） 2=退回拍卖场（在途/在库→已出库）。 */
export function returnItem(
  itemId: number,
  clientReqId: string,
  direction: number,
  note?: string,
): Promise<ActionResult> {
  const payload = note == null || note === '' ? { itemId, clientReqId, direction } : { itemId, clientReqId, direction, note }
  return request('/api/inventory/return', jsonInit('POST', payload))
}

/** 手动上架标记（雅虎手工出品后即时登记；流拍后重上=在库已取消→在售）。 */
export function markListedItem(itemId: number, clientReqId: string): Promise<ActionResult> {
  return request('/api/inventory/mark-listed', jsonInit('POST', { itemId, clientReqId }))
}

/** 手动取消标记（流拍/出品取消的登记口，D-069：受注表无取消信息，唯一来源=手动）。 */
export function markCanceledItem(itemId: number, clientReqId: string): Promise<ActionResult> {
  return request('/api/inventory/mark-canceled', jsonInit('POST', { itemId, clientReqId }))
}
