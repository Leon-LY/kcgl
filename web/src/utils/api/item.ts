/**
 * 商品录入与本表查询：建档、明细、编辑、回收站、变更历史。
 */
import { jsonInit, request } from './core'

// ------------------------------------------------------------------ 商品录入

/** 管理号预览（无锁推算，≠保留；文案必须明示以保存时为准）。 */
export interface ItemCodePreview {
  code: string
  bandCode: string
  seqPrefix: string
  seqNo: number
}

export function previewItemCode(venueId: number, buyDate: string, price: number): Promise<ItemCodePreview> {
  const query = new URLSearchParams({
    venueId: String(venueId),
    buyDate,
    price: String(price),
  })
  return request(`/api/item-codes/preview?${query}`, { method: 'GET' })
}

export interface CreateItemPayload {
  /** 客户端幂等键：一次逻辑保存从生成到成功共用；失败重试复用同键（7.0）。 */
  clientReqId: string
  /** 作废重录（M2-6）：指向已作废原件；请求未携带字段由服务端继承，图片行服务端复制。 */
  reEntryOf?: number
  venueId: number
  buyDate: string
  purchasePrice: number
  warehouse: number
  /** 拍摄日期：有照片时随保存提交（拍照=当天；相册=手动选填，docs/01 7.5）。 */
  photoDate?: string
  fee?: number
  shippingFee?: number
  tax?: number
  shelfNo?: string
  warehouseInDate?: string
  groupNo?: string
  remark?: string
}

/** 录入成功响应（生成列 totalCost/profit 已回填；未售时 profit 为 null 不出现）。 */
export interface ItemResponse {
  id: number
  itemCode: string
  venueId: number
  venueCode: string
  buyMonth: number
  seqPrefix: string
  seqNo: number
  buyDate: string
  photoDate: string | null
  purchasePrice: number
  fee: number | null
  shippingFee: number | null
  tax: number | null
  soldPrice: number | null
  totalCost: number
  profit: number | null
  priceBandCode: string
  warehouse: number
  shelfNo: string | null
  warehouseInDate: string | null
  groupNo: string | null
  remark: string | null
  itemName: string | null
  category: string | null
  authorKiln: string | null
  sizeText: string | null
  weightG: number | null
  salesChannel: string | null
  stockStatus: number
  saleStatus: number
  voided: boolean
  /** 取り消し理由（作废件详情/扫旧码提示用）。 */
  voidReason: string | null
  /** 作废重录互链：本件为 {reEntryOf} 的再登録件。 */
  reEntryOf: number | null
  /**
   * 作废重录互链（D-131）：本件的再登録件 id 与两侧对端管理号。
   * 三个字段**仅详情端点装配**（列表/批量端点不查，避免 N+1），故可选：
   * undefined/null 即不渲染互链行。
   */
  voidReEntry?: number | null
  reEntryOfCode?: string | null
  voidReEntryCode?: string | null
  deleted: boolean
  /** 乐观锁版本号（编辑弹层 PUT 时原样携带；409000 后重读取新值）。 */
  version: number
  createdAt: string
}

export function createItem(payload: CreateItemPayload): Promise<ItemResponse> {
  return request('/api/items', jsonInit('POST', payload))
}

/** 详情（M2-6）：作废件可见（重录预填/扫旧码提示）；软删件 404。 */
export function fetchItem(id: number): Promise<ItemResponse> {
  return request(`/api/items/${id}`, { method: 'GET' })
}

/**
 * 作废（M2-6，docs/01 7.1）：冻结商品并记 VOID 流水；幂等键 clientReqId。
 * 响应为作废后的商品（voided=true），字段完整——重录预填直接可用。
 */
export function voidItem(id: number, clientReqId: string, reason: string): Promise<ItemResponse> {
  return request(`/api/items/${id}/void`, jsonInit('POST', { clientReqId, reason }))
}

/**
 * 手工修正（D4，A-only）：任意态覆盖库存/销售两轴，用于纠正状态机走不到的错误现态。
 * 两轴可选，**省略即保持不变**（不是传 null）；服务端要求至少给一轴且与现态不同。
 * 不改仓库——改仓仍走 transferItem（保住「改仓必走台账」的对账不变量）。
 */
export interface AdjustItemPayload {
  clientReqId: string
  reason: string
  stockStatus?: number
  saleStatus?: number
}

export function adjustItem(id: number, payload: AdjustItemPayload): Promise<ItemResponse> {
  return request(`/api/items/${id}/adjust`, jsonInit('POST', payload))
}

// ------------------------------------------------- 搜索/编辑/回收站/历史（M5-①）

/** 搜索行（D-061）：作废/软删件已被服务端排除；slowMoveLevel 0 无/1 黄/2 红（D-065）。 */
export interface ItemSearchRow {
  id: number
  itemCode: string
  thumbUrl: string | null
  itemName: string | null
  venueName: string | null
  buyDate: string
  purchasePrice: number
  totalCost: number
  profit: number | null
  warehouse: number
  stockStatus: number
  saleStatus: number
  soldPrice: number | null
  shelfNo: string | null
  warehouseInDate: string | null
  slowMoveLevel: number
}

export interface ItemSearchResult {
  total: number
  page: number
  size: number
  rows: ItemSearchRow[]
}

export interface ItemSearchParams {
  /** kw 优先级链（D-062）：管理号整串 ＞ 日期 ＞ 模糊 LIKE；假名宽松匹配（ア/ぁ 命中 あ）。 */
  kw?: string
  warehouse?: number
  stockStatus?: number
  saleStatus?: number
  venueId?: number
  buyDateFrom?: string
  buyDateTo?: string
  warnLevel?: number
  page?: number
  size?: number
}

export function searchItems(params: ItemSearchParams): Promise<ItemSearchResult> {
  const query = new URLSearchParams()
  if (params.kw != null && params.kw !== '') {
    query.set('kw', params.kw)
  }
  for (const key of ['warehouse', 'stockStatus', 'saleStatus', 'venueId', 'warnLevel'] as const) {
    const value = params[key]
    if (value != null) {
      query.set(key, String(value))
    }
  }
  for (const key of ['buyDateFrom', 'buyDateTo'] as const) {
    const value = params[key]
    if (value != null && value !== '') {
      query.set(key, value)
    }
  }
  if (params.page != null) {
    query.set('page', String(params.page))
  }
  if (params.size != null) {
    query.set('size', String(params.size))
  }
  return request(`/api/items/search?${query}`, { method: 'GET' })
}

/**
 * 编辑（D-063/D-066 snapshot 单模式）：全量语义——可选字段显式 null=清空
 * （缺省与 null 服务端等价，这里全量携带让意图显式）；
 * 仓值仅在途可改（非在途同值放行=契约 W）；version 乐观锁（409 时重读即可）。
 */
export interface UpdateItemPayload {
  version: number
  venueId: number
  buyDate: string
  purchasePrice: number
  warehouse: number
  photoDate?: string | null
  fee?: number | null
  shippingFee?: number | null
  tax?: number | null
  shelfNo?: string | null
  warehouseInDate?: string | null
  groupNo?: string | null
  remark?: string | null
  itemName?: string | null
  category?: string | null
  authorKiln?: string | null
  sizeText?: string | null
  weightG?: number | null
  salesChannel?: string | null
}

export function updateItem(id: number, payload: UpdateItemPayload): Promise<ItemResponse> {
  return request(`/api/items/${id}`, jsonInit('PUT', payload))
}

/** 回收站软删（A-only，D-064）：幂等键 clientReqId；reason 可选（数据治理动作）。 */
export function deleteItem(id: number, clientReqId: string, reason?: string): Promise<ItemResponse> {
  return request(`/api/items/${id}`, jsonInit('DELETE', { clientReqId, reason: reason ?? null }))
}

/** 回收站恢复（A-only）：stock_status 保序回软删前原值，作废标志不动。 */
export function restoreItem(id: number, clientReqId: string): Promise<ItemResponse> {
  return request(`/api/items/${id}/restore`, jsonInit('POST', { clientReqId }))
}

/**
 * 批量软删/恢复的单件载荷：**逐件自带幂等键**。
 * 不能由批次键拼接派生——stock_ledger.client_req_id 是 CHAR(36) 且带唯一约束，
 * UUID 已占满 36 字符；逐件带键反而让每件仍各自幂等读回（见后端 RecycleBatchRequest）。
 */
export interface RecycleBatchEntry {
  id: number
  clientReqId: string
}

/** 批量结果：逐件成败。failures 的 code 按 errors.<code> 三语渲染。 */
export interface RecycleBatchResult {
  succeeded: number
  failures: { itemId: number; code: number }[]
}

/** 批量软删（A-only，D-126）：批内某件失败（已被他人删/不存在）不影响其余件。 */
export function deleteItemsBatch(
  entries: RecycleBatchEntry[],
  reason?: string,
): Promise<RecycleBatchResult> {
  return request(
    '/api/items/recycle-delete',
    jsonInit('POST', { items: entries, reason: reason ?? null }),
  )
}

/** 批量恢复（A-only，D-126）。 */
export function restoreItemsBatch(
  entries: RecycleBatchEntry[],
  reason?: string,
): Promise<RecycleBatchResult> {
  return request(
    '/api/items/recycle-restore',
    jsonInit('POST', { items: entries, reason: reason ?? null }),
  )
}

export interface RecycleBinRow {
  id: number
  itemCode: string
  thumbUrl: string | null
  itemName: string | null
  venueName: string | null
  warehouse: number
  stockStatus: number
  saleStatus: number
  voided: boolean
  deletedAt: string
  reason: string | null
}

export interface RecycleBinResult {
  total: number
  page: number
  size: number
  rows: RecycleBinRow[]
}

export function fetchRecycleBin(page = 1, size = 20): Promise<RecycleBinResult> {
  return request(`/api/items/recycle-bin?page=${page}&size=${size}`, { method: 'GET' })
}

/** 单件流水行（id 倒序）：txnType 数值=TxnType 枚举（1 录入…13/14 回收站）。 */
export interface ItemLedgerRow {
  id: number
  txnType: number
  whFrom: number | null
  whTo: number | null
  qtyChange: number
  stockFrom: number | null
  stockTo: number | null
  saleFrom: number | null
  saleTo: number | null
  reason: string | null
  /**
   * 系统生成理由的结构化形态（V7，D-130）：reasonCode 非空即按当前语言渲染
   * （reasonParams 是 JSON 文本，走 renderMessageJson），为空＝人工理由，直接显示 reason。
   */
  reasonCode?: string | null
  reasonParams?: string | null
  operatorName: string | null
  createdAt: string
}

export function fetchItemLedgers(id: number): Promise<{ rows: ItemLedgerRow[] }> {
  return request(`/api/items/${id}/ledgers`, { method: 'GET' })
}

/** 商品受注行（closed_at 倒序）：受注导入的行 listed_at/list_price 恒 NULL，status 恒 2。 */
export interface YahooListingRow {
  id: number
  orderId: string | null
  yahooAuctionId: string | null
  listPrice: number | null
  soldPrice: number | null
  status: number
  listedAt: string | null
  closedAt: string | null
}

export function fetchItemYahooListings(id: number): Promise<{ rows: YahooListingRow[] }> {
  return request(`/api/items/${id}/yahoo-listings`, { method: 'GET' })
}

// ------------------------------------------------------------------ 打印列表（M2-7）

/** 打印页行摘要：标签只需管理号/落札日/会场码 + 可选首图缩略图。 */
export interface ItemSummary {
  id: number
  itemCode: string
  buyDate: string
  venueCode: string
  thumbUrl: string | null
}

export interface ItemListResponse {
  total: number
  page: number
  size: number
  rows: ItemSummary[]
}

export interface PrintItemsParams {
  /** 创建日区间（JST 日界，含两端）；作废/软删件不出标签（服务端过滤）。 */
  createdFrom: string
  createdTo: string
  venueId?: number
  /** 管理号单票重打（M2-9）：精确匹配，优先于日期/会场条件；空=按日期模式。 */
  code?: string
  page?: number
  size?: number
}

export function fetchItemsForPrint(params: PrintItemsParams): Promise<ItemListResponse> {
  const query = new URLSearchParams({
    createdFrom: params.createdFrom,
    createdTo: params.createdTo,
  })
  if (params.venueId != null) {
    query.set('venueId', String(params.venueId))
  }
  if (params.code != null && params.code !== '') {
    query.set('code', params.code)
  }
  if (params.page != null) {
    query.set('page', String(params.page))
  }
  if (params.size != null) {
    query.set('size', String(params.size))
  }
  return request(`/api/items?${query}`, { method: 'GET' })
}

// ------------------------------------------------------------------ 图片上传

export interface ImageUploadResult {
  id: number
  clientUuid: string
  itemId: number
  url: string
  thumbUrl: string
  imageType: number
  sortOrder: number
}

/**
 * multipart 图片上传（M2-5）。不设 Content-Type——浏览器自动带 boundary。
 * 幂等契约（docs/01 7.0）：同 clientUuid 重放服务端 200 读回原记录，队列据此出清。
 */
export function uploadImage(form: FormData): Promise<ImageUploadResult> {
  return request('/api/images', { method: 'POST', body: form })
}

export function fetchItemImages(itemId: number): Promise<ImageUploadResult[]> {
  return request(`/api/items/${itemId}/images`, { method: 'GET' })
}

/** 解绑一张照片（D5）：删库行不删文件，不可撤销（docs/01 7.5）。 */
export function deleteItemImage(imageId: number): Promise<void> {
  return request(`/api/images/${imageId}`, { method: 'DELETE' })
}

/**
 * 重排照片（D5）：ids 必须与该商品**全部**图片一一对应（缺失/多余/重复服务端 400）。
 * 故调用方须传当前完整顺序，不能只传被移动的那一张。
 */
export function reorderItemImages(itemId: number, ids: number[]): Promise<void> {
  return request(`/api/items/${itemId}/images/order`, jsonInit('PUT', { ids }))
}
