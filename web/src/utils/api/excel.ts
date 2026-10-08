/**
 * Excel 导入导出：模板下载、上传、批次与行错误、报表导出。
 */
import { request, requestBlob } from './core'
import type { BlobDownload } from './core'

// ------------------------------------------------------------------ Excel 导入导出（M4-⑤，D-058）

/**
 * 错误行采样条目（后端前 1000 条采样）。
 * code/params=结构化消息（D-127），历史行缺失时回退 reason 原文。
 */
export interface ExcelImportErrorRow {
  line: number
  raw: string
  reason: string
  code?: string | null
  params?: Record<string, unknown> | null
}

/**
 * 导入批次报告：status 0处理中 1完成 2失败；note=计数器跳变说明（A0→A5 等）。
 * note 是纯数据无日文短语，不做结构化（无 noteJson）——与雅虎批次刻意不对称。
 */
export interface ExcelImportBatch {
  id: number
  originalFilename: string
  status: number
  rowCount: number
  generatedCount: number
  importedCount: number
  errorCount: number
  note: string | null
  errorMessage: string | null
  errorMessageCode: string | null
  errorMessageParams: string | null
  uploadedBy: number
  createdAt: string | null
  finishedAt: string | null
  errorRows: ExcelImportErrorRow[]
}

/** 导出筛选（与打印列表同口径）：日期区间必填、会场可选、管理番号可选（単票抽出优先）。 */
export interface ExcelExportParams {
  createdFrom: string
  createdTo: string
  venueId?: number | null
  code?: string
}


/** 模板下载（双 Sheet：商品表头+記入方法；流式生成不落盘）。 */
export function downloadExcelTemplate(): Promise<BlobDownload> {
  return requestBlob('/api/excel/items/template', '商品登録テンプレート.xlsx')
}

/** 上传（同步段）：毫秒级返回 processing 批次；sha 重复 409012、队列满 429001。 */
export function uploadExcelWorkbook(form: FormData): Promise<ExcelImportBatch> {
  return request('/api/excel/items/import', { method: 'POST', body: form })
}

/** 批次列表（最新 50）。 */
export function fetchExcelBatches(): Promise<ExcelImportBatch[]> {
  return request('/api/excel/items/imports', { method: 'GET' })
}

/** 批次详情（含错误行采样与跳变说明；上传后轮询至终态）。 */
export function fetchExcelBatch(id: number): Promise<ExcelImportBatch> {
  return request(`/api/excel/items/imports/${id}`, { method: 'GET' })
}

/** 流式导出（报告口径 25 列）；无码条件时倒挂区间由后端 400 拦截。 */
export function downloadExcelExport(params: ExcelExportParams): Promise<BlobDownload> {
  const search = new URLSearchParams({
    createdFrom: params.createdFrom,
    createdTo: params.createdTo,
  })
  if (params.venueId != null) {
    search.set('venueId', String(params.venueId))
  }
  const code = params.code?.trim()
  if (code) {
    search.set('code', code)
  }
  return requestBlob(`/api/excel/items/export?${search.toString()}`, '商品一覧.xlsx')
}
