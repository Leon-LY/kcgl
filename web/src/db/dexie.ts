import Dexie, { type EntityTable } from 'dexie'

/**
 * IndexedDB 持久化（M2-5 起逐步接入；docs/01 7.5）：
 * upload_queue——图片上传队列，杀进程/刷新不丢（弱网补传的根基）。
 * 队列存 ArrayBuffer+mime 而非 Blob（Safari IndexedDB 存 Blob 有历史损坏代案）。
 * status 语义：pending_bind=文字未保存（孤儿态，独立处理）/ pending=待传 / uploading=传输中。
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

export const db = new Dexie('kcgl') as Dexie & {
  uploadQueue: EntityTable<UploadQueueEntry, 'clientUuid'>
}

db.version(1).stores({
  // 主键 clientUuid（幂等键即队列键）；itemId/status/createdAt 供队列扫描
  uploadQueue: 'clientUuid, itemId, status, createdAt',
})
