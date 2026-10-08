/**
 * API 客户端地基：统一信封解析、错误标准化、401 全局处理入口。
 *
 * 其余域模块一律从这里取 request / jsonInit，不各自再造一份。
 *
 * 注意 request / jsonInit / requestBlob / filenameFrom 是**包内**共享——它们带 export
 * 只为给同目录的域模块用，index.ts 不转出它们，对外可见面与拆分前完全一致。
 */

export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T | null
  errorId?: string
}

/** 业务/系统错误统一形态：code + 可选 errorId（用户可报此 ID）+ 可选 data（如锁定剩余分钟）。 */
export class ApiError extends Error {
  readonly code: number
  readonly errorId?: string
  readonly data?: unknown

  constructor(code: number, message: string, errorId?: string, data?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.errorId = errorId
    this.data = data
  }
}

/** 401 时由应用层注册跳转（避免 api 模块循环依赖 router）；传 null 注销（测试隔离用）。 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export async function request<T>(path: string, init: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, init)
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR')
  }
  let body: ApiEnvelope<T>
  try {
    body = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new ApiError(0, 'INVALID_RESPONSE', undefined, { status: response.status })
  }
  if (body.code !== 0) {
    // 401（非登录端点自身）触发全局登出跳转；登录失败也返回 401 但由调用方就地处理
    if (response.status === 401 && unauthorizedHandler && !path.includes('/api/auth/login')) {
      unauthorizedHandler()
    }
    throw new ApiError(body.code, body.message, body.errorId, body.data)
  }
  return body.data as T
}

export function jsonInit(method: string, payload: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }
}

/** 二进制下载结果：blob + 从 Content-Disposition 解析的文件名。 */
export interface BlobDownload {
  blob: Blob
  filename: string
}

/**
 * 二进制下载（模板/导出=原始 xlsx 流，无 JSON 信封）；失败时后端仍回 JSON
 * 信封（如倒挂区间 400）→ 标准化为 ApiError。成功路径不走 response.json()。
 */
export async function requestBlob(path: string, fallbackFilename: string): Promise<BlobDownload> {
  let response: Response
  try {
    response = await fetch(path)
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR')
  }
  if (!response.ok) {
    if (response.status === 401 && unauthorizedHandler) {
      unauthorizedHandler()
    }
    let envelope: ApiEnvelope<unknown> | null = null
    try {
      envelope = (await response.json()) as ApiEnvelope<unknown>
    } catch {
      envelope = null
    }
    if (envelope && envelope.code !== 0) {
      throw new ApiError(envelope.code, envelope.message, envelope.errorId, envelope.data)
    }
    throw new ApiError(0, 'INVALID_RESPONSE', undefined, { status: response.status })
  }
  return { blob: await response.blob(), filename: filenameFrom(response.headers, fallbackFilename) }
}

/** Content-Disposition 解析：RFC 5987 filename*=UTF-8''… 优先，回退 filename=…。 */
export function filenameFrom(headers: Headers, fallback: string): string {
  const disposition = headers.get('Content-Disposition')
  if (disposition) {
    const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
    if (utf8) {
      try {
        return decodeURIComponent(utf8[1])
      } catch {
        // 非法百分号序列：落到 filename= 回退
      }
    }
    const plain = /filename="?([^";]+)"?/i.exec(disposition)
    if (plain) {
      return plain[1]
    }
  }
  return fallback
}
