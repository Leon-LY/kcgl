/**
 * API 客户端唯一出口：统一信封 {code,message,data} 解析、错误标准化、401 全局处理。
 * 契约见 docs/01（code=0 成功，非 0 为业务/系统错误，500 带 errorId=traceId）。
 * 同源请求 SameSite=Strict，跨端 fetch 不需要显式带 Cookie 之外的东西。
 *
 * 本文件只做转出：实现按域拆在 ./<domain>.ts。「全仓都 import 这一个入口」的纪律
 * 不变——拆分是为了单文件别继续长，不是要调用点改路径。
 */

// 地基：ApiError 与 401 处理器是各域共用的值；ApiEnvelope / BlobDownload 是共用类型
export { ApiError, setUnauthorizedHandler } from './core'
export type { ApiEnvelope, BlobDownload } from './core'

export * from './auth'
export * from './dict'
export * from './user'
export * from './item'
export * from './inventory'
export * from './settings'
export * from './stocktake'
export * from './yahoo'
export * from './excel'
export * from './governance'
