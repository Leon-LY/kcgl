/**
 * 雅虎受注导入：上传、批次列表、明细与行错误。
 */
import { request } from './core'

// ------------------------------------------------------------------ 雅虎受注导入（M5-②b，docs/01 7.4/7.2，D-069）

/**
 * 错误行采样条目（后端前 1000 条采样）。
 * code/params=结构化消息（D-127）：前端按当前语言渲染；V5 之前落库的历史行无此
 * 字段，此时回退 reason 的日文原文（renderMessage 统一处理）。
 * line/raw 是真实数据不是文案，直出。
 */
export interface YahooImportErrorRow {
  line: number
  raw: string
  reason: string
  code?: string | null
  params?: Record<string, unknown> | null
}

/**
 * 导入批次报告：status 0处理中 1完成 2失败；失败批次计数为 null。
 * rowCount=物理数据行；matched/unmatched 按子行（まとめ売り一行拆 N 子行）；
 * note=まとめ売り等批次级補注（単価未分割）；
 * noteJson=同一批注的结构化数组 [{code,params,text}]（D-127），note 为兜底原文。
 */
export interface YahooImportBatch {
  id: number
  originalFilename: string
  status: number
  rowCount: number | null
  matchedCount: number | null
  unmatchedCount: number | null
  updatedCount: number | null
  note: string | null
  noteJson: string | null
  errorMessage: string | null
  errorMessageCode: string | null
  errorMessageParams: string | null
  uploadedBy: number
  createdAt: string | null
  finishedAt: string | null
  errorRows: YahooImportErrorRow[]
}

/** 对账三活视图行（docs/01 7.2）：delayed=滞留红标；recentlySynced=降灰（导入后仍未成交=通过一次校验）。 */
export interface YahooReconcileRow {
  itemId: number
  itemCode: string
  warehouse: number
  shelfNo: string | null
  soldPrice: number | null
  orderId: string | null
  auctionId: string | null
  closedAt: string | null
  lastSyncedAt: string | null
  delayed: boolean
  recentlySynced: boolean
}

export interface YahooReconcile {
  soldNotShipped: YahooReconcileRow[]
  canceledNotRelisted: YahooReconcileRow[]
  withdrawNeeded: YahooReconcileRow[]
}

/** 出荷待ち行：已成交未出库的拣货队列（货架号序+缩略图）；orderId=雅虎受注 ID 留痕。 */
export interface YahooPendingShipment {
  itemId: number
  itemCode: string
  thumbUrl: string | null
  warehouse: number
  shelfNo: string | null
  soldPrice: number | null
  orderId: string | null
  auctionId: string | null
  closedAt: string | null
  delayed: boolean
}

export interface YahooPendingShipmentList {
  count: number
  items: YahooPendingShipment[]
}

/** 上传受注 xlsx（同步段）：毫秒级返回 processing 批次；sha 重复 409011、非 xlsx 400012。 */
export function uploadYahooImport(form: FormData): Promise<YahooImportBatch> {
  return request('/api/yahoo/imports', { method: 'POST', body: form })
}

/** 批次列表（最新 50）。 */
export function fetchYahooBatches(): Promise<YahooImportBatch[]> {
  return request('/api/yahoo/imports', { method: 'GET' })
}

/** 批次详情（含错误行采样；上传后轮询至终态）。 */
export function fetchYahooBatch(id: number): Promise<YahooImportBatch> {
  return request(`/api/yahoo/imports/${id}`, { method: 'GET' })
}

/** 对账三活视图。 */
export function fetchYahooReconcile(): Promise<YahooReconcile> {
  return request('/api/yahoo/reconcile', { method: 'GET' })
}

/**
 * 批次不一致行明细（「受注有而系统无」）：本批次见过且仍未命中的行，原文自码可查。
 * 归属随最近一次见到该拍卖的批次走；rows 后端截断 200 条（total 恒为全量）。
 */
export interface YahooUnmatchedRow {
  /** 展示用自码：原文优先，原文缺失时回退归一码 */
  selfCode: string | null
  orderId: string | null
  auctionId: string
  /** null=まとめ売り（合计价无拆分依据，手填后补） */
  soldPrice: number | null
  closedAt: string | null
}

export interface YahooUnmatchedRows {
  batchId: number
  total: number
  truncated: boolean
  rows: YahooUnmatchedRow[]
}

export function fetchYahooUnmatched(batchId: number): Promise<YahooUnmatchedRows> {
  return request(`/api/yahoo/imports/${batchId}/unmatched`, { method: 'GET' })
}

/** 出荷待ち清单。 */
export function fetchPendingShipments(): Promise<YahooPendingShipmentList> {
  return request('/api/yahoo/pending-shipments', { method: 'GET' })
}
