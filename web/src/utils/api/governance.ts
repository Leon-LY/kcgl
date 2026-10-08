/**
 * 治理浏览与监控：操作日志、告警、诊断导出。
 */
import { jsonInit, request, requestBlob } from './core'
import type { BlobDownload } from './core'
import { dayjs } from '@/utils/format'

// ------------------------------------------------------------------ 治理浏览与监控（M5-④）

/**
 * 「到日」→ 次日零点（与列表页「日期含两端」同一 UI 语义在 DATETIME 列上的实现）：
 * 服务端 [from, to) 半开不猜「日含尾」，边界组装归本层。dayjs 做日算术（裸 Date 被 eslint 拦）。
 */
function nextDayStart(date: string): string {
  return `${dayjs(date).add(1, 'day').format('YYYY-MM-DD')}T00:00:00`
}

/** 全库流水行（管理员治理视角）：txnType 数值=TxnType 枚举（1 录入…14 回收站恢复）。 */
export interface LedgerRow {
  id: number
  itemId: number
  itemCode: string
  txnType: number
  whFrom: number | null
  whTo: number | null
  qtyChange: number
  stockFrom: number | null
  stockTo: number | null
  saleFrom: number | null
  saleTo: number | null
  returnDirection: number | null
  refType: string | null
  refId: number | null
  reason: string | null
  /** 系统生成理由的结构化形态（V7，D-130），同 ItemLedgerRow。 */
  reasonCode?: string | null
  reasonParams?: string | null
  clientReqId: string | null
  operatorName: string | null
  createdAt: string
}

export interface LedgerBrowseResult {
  total: number
  page: number
  size: number
  rows: LedgerRow[]
}

export interface LedgerBrowseParams {
  txnType?: number
  /** 管理号快照前缀（会场/年代号起头翻查）。 */
  itemCode?: string
  operatorName?: string
  /** 仓库命中=流入或流出任一侧。 */
  warehouse?: number
  itemId?: number
  /** [from, to) 闭开区间；「到日」由本层组装次日零点。 */
  dateFrom?: string
  dateTo?: string
  page?: number
  size?: number
}

/** 全库流水浏览（管理员）：id 倒序稳定翻页。 */
export function fetchLedgers(params: LedgerBrowseParams): Promise<LedgerBrowseResult> {
  const query = new URLSearchParams()
  if (params.txnType != null) {
    query.set('txnType', String(params.txnType))
  }
  const itemCode = params.itemCode?.trim()
  if (itemCode) {
    query.set('itemCode', itemCode)
  }
  const operatorName = params.operatorName?.trim()
  if (operatorName) {
    query.set('operatorName', operatorName)
  }
  if (params.warehouse != null) {
    query.set('warehouse', String(params.warehouse))
  }
  if (params.itemId != null) {
    query.set('itemId', String(params.itemId))
  }
  if (params.dateFrom) {
    query.set('from', `${params.dateFrom}T00:00:00`)
  }
  if (params.dateTo) {
    query.set('to', nextDayStart(params.dateTo))
  }
  if (params.page != null) {
    query.set('page', String(params.page))
  }
  if (params.size != null) {
    query.set('size', String(params.size))
  }
  const qs = query.toString()
  return request(`/api/inventory/ledgers${qs ? `?${qs}` : ''}`, { method: 'GET' })
}

/** 操作日志行：detail 为原始 JSON 文本（格式化展示，不改写留痕）。 */
export interface OperationLogRow {
  id: number
  action: string
  entityType: string
  entityId: number | null
  detail: string | null
  operatorName: string
  ip: string | null
  ua: string | null
  createdAt: string
}

export interface OperationLogResult {
  total: number
  page: number
  size: number
  rows: OperationLogRow[]
}

export interface OperationLogParams {
  action?: string
  entityType?: string
  operatorName?: string
  dateFrom?: string
  dateTo?: string
  page?: number
  size?: number
}

/** 操作日志查询（管理员）：日志不可删改，查询是唯一 API 面。 */
export function fetchOperationLogs(params: OperationLogParams): Promise<OperationLogResult> {
  const query = new URLSearchParams()
  const action = params.action?.trim()
  if (action) {
    query.set('action', action)
  }
  const entityType = params.entityType?.trim()
  if (entityType) {
    query.set('entityType', entityType)
  }
  const operatorName = params.operatorName?.trim()
  if (operatorName) {
    query.set('operatorName', operatorName)
  }
  if (params.dateFrom) {
    query.set('from', `${params.dateFrom}T00:00:00`)
  }
  if (params.dateTo) {
    query.set('to', nextDayStart(params.dateTo))
  }
  if (params.page != null) {
    query.set('page', String(params.page))
  }
  if (params.size != null) {
    query.set('size', String(params.size))
  }
  const qs = query.toString()
  return request(`/api/operation-logs${qs ? `?${qs}` : ''}`, { method: 'GET' })
}

/** システム状況（管理员）：白名单字段；pool 各 -1=池未就绪；disk 全零=目录不可用。 */
export interface SystemStatus {
  appVersion: string
  flywayVersion: string | null
  startedAt: string
  uptimeSeconds: number
  heap: { usedBytes: number; maxBytes: number }
  pool: { active: number; idle: number; total: number; waiting: number }
  sseConnections: number
  disk: { path: string; totalBytes: number; usableBytes: number; usedPercent: number }
  volumes: { items: number; ledgers: number; operationLogs: number; clientErrors: number }
  codeEngine: { issued: number; skipped: number }
  excelBatches: {
    total: number
    done: number
    failed: number
    processing: number
    recent: {
      id: number
      originalFilename: string
      status: number
      rowCount: number
      errorCount: number
      createdAt: string | null
      finishedAt: string | null
    }[]
  }
  /** 近 7 日按日计数（旧在前，含今天，JST 日界）。 */
  clientErrors7d: { date: string; count: number }[]
  openAlerts: number
  /** 直近バックアップ（M7）：null=不可知（未配置/无状态文件/坏 JSON），展示占位。 */
  backup: {
    /** 原文（date -Is 带宿主时区 offset，诊断导出用）。 */
    lastSuccessAt: string
    /** 展示用 naive JST 墙钟。 */
    lastSuccessAtJst: string
    /** 距今秒数（25h 陈旧判定与 kcgl-doctor 同阈值）。 */
    staleSeconds: number
    detail: string
  } | null
}

export function fetchSystemStatus(): Promise<SystemStatus> {
  return request('/api/stats/system', { method: 'GET' })
}

/** 诊断包下载（管理员）：システム状況快照+告警近 30+错误样本+环形缓冲，JSON 附件。 */
export function downloadDiagnosticsExport(): Promise<BlobDownload> {
  return requestBlob('/api/diagnostics/export', 'kcgl-diagnostics.json')
}

/**
 * 统一告警行：level 1提示 2警告 3错误；status 0开启 1已读。
 *
 * messageKey/messageParams（V6，D-128）：告警文案的 i18n 键与插值参数（JSON 文本，
 * 与批次表同口径需前端 parse）。message 是 V6 之前的日文原文，两者都留——
 * messageKey 为空即历史行，渲染时回退 message（renderMessageJson）。
 */
export interface SysAlert {
  id: number
  type: string
  dedupKey: string
  level: number
  message: string
  messageKey?: string | null
  messageParams?: string | null
  payload: string | null
  status: number
  readBy: number | null
  readAt: string | null
  createdAt: string
}

/** 告警列表（开启态在前，其余创建时间倒序）。 */
export function fetchAlerts(page = 1, size = 50): Promise<{ list: SysAlert[]; total: number; page: number; size: number }> {
  return request(`/api/alerts?page=${page}&size=${size}`, { method: 'GET' })
}

/** 告警已读（管理员；重复已读幂等）。 */
export function markAlertRead(id: number): Promise<void> {
  return request(`/api/alerts/${id}/read`, jsonInit('PATCH', {}))
}

/** 自检报告（POST /api/self-check 与每日 job 同口径）：ok=五节全过（孤儿文件仅告警）。 */
export interface SelfCheckReport {
  ranAt: string
  ok: boolean
  ledger: {
    ok: boolean
    balances: { warehouse: number; ledgerSum: number; itemCount: number }[]
    driftCount: number
    drifts: {
      itemId: number
      itemCode: string | null
      itemWarehouse: number | null
      itemStockStatus: number | null
    }[]
  }
  counters: {
    ok: boolean
    mismatches: { venueId: number; month: number; curPrefix: string | null; curSeq: number; maxSeq: number }[]
  }
  volumes: {
    ok: boolean
    itemCount: number
    ledgerCount: number
    logCount: number
    itemThreshold: number
    ledgerThreshold: number
    logThreshold: number
  }
  disk: { ok: boolean; path: string; totalBytes: number; usableBytes: number; usedPercent: number }
  imageAudit: {
    ok: boolean
    orphanCount: number
    orphanSamples: string[]
    missingCount: number
    missingSamples: string[]
  }
}

/** 手动帳実自検（管理员）：即时全量检查，发现即落告警留痕。 */
export function runSelfCheck(): Promise<SelfCheckReport> {
  return request('/api/self-check', jsonInit('POST', {}))
}
