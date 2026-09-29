import Dexie, { type EntityTable } from 'dexie'

/**
 * IndexedDB 持久化（M2-5 起逐步接入；docs/01 7.5）：
 * upload_queue——图片上传队列，杀进程/刷新不丢（弱网补传的根基）。
 * 队列存 ArrayBuffer+mime 而非 Blob（Safari IndexedDB 存 Blob 有历史损坏代案）。
 * status 语义：pending_bind=文字未保存（孤儿态，独立处理）/ pending=待传 / uploading=传输中。
 *
 * v2（M6-②）scan_actions——盘点扫码离线小队列：仓库深处信号差，盘点是
 * 「人走着扫几百件」的场景；网络失败的扫码照记本地，恢复后按序回放。
 * 服务端同单同件幂等（repeated=true 200）保证重复回放安全。
 */
export interface UploadQueueEntry {
  clientUuid: string
  /** null = pending_bind（商品 id 未知，保存成功后 bindItem 回填）。 */
  itemId: number | null
  data: ArrayBuffer
  mimeType: string
  status: 'pending' | 'uploading' | 'pending_bind'
  attempts: number
  /** 退避重试的下次可传时间（epoch ms，0=立即可传）。 */
  nextRetryAt: number
  createdAt: number
}

/** 离线扫码（盘点会话）：仅存「哪一单扫了什么码」，详情卡离线拿不到——恢复回放后由服务端口径补齐。 */
export interface ScanActionEntry {
  /** 自增主键（回放按 createdAt 先后，删除按主键）。 */
  id?: number
  stocktakeId: number
  code: string
  createdAt: number
}

export const db = new Dexie('kcgl') as Dexie & {
  uploadQueue: EntityTable<UploadQueueEntry, 'clientUuid'>
  scanActions: EntityTable<ScanActionEntry, 'id'>
}

db.version(1).stores({
  // 主键 clientUuid（幂等键即队列键）；itemId/status/createdAt 供队列扫描
  uploadQueue: 'clientUuid, itemId, status, createdAt',
})

db.version(2).stores({
  uploadQueue: 'clientUuid, itemId, status, createdAt',
  // ++id 自增主键；stocktakeId 供按单计数，createdAt 供 FIFO 回放序
  scanActions: '++id, stocktakeId, createdAt',
})
