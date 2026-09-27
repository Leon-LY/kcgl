/**
 * API 客户端唯一出口：统一信封 {code,message,data} 解析、错误标准化、401 全局处理。
 * 后端契约（docs/01 六节）：code=0 成功；非 0 为业务/系统错误；500 带 errorId（=traceId）。
 * 同源部署（SameSite=Strict 依赖），fetch 不需要显式带 Cookie 之外的配置。
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

async function request<T>(path: string, init: RequestInit): Promise<T> {
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

function jsonInit(method: string, payload: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }
}

// ------------------------------------------------------------------ 认证

export interface MeResponse {
  username: string
  displayName: string
  role: number
  locale: string
  mustChangePwd: boolean
}

export const api = {
  /** 登录走框架 formLogin 过滤器（表单编码，D-021）。 */
  login(username: string, password: string): Promise<MeResponse> {
    const body = new URLSearchParams({ username, password })
    return request('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
  },
  logout(): Promise<void> {
    return request('/api/auth/logout', { method: 'POST' })
  },
  me(): Promise<MeResponse> {
    return request('/api/auth/me', { method: 'GET' })
  },
  changePassword(oldPassword: string, newPassword: string): Promise<void> {
    return request('/api/auth/me/password', jsonInit('PUT', { oldPassword, newPassword }))
  },
  changeLocale(locale: string): Promise<void> {
    return request('/api/auth/me/locale', jsonInit('PUT', { locale }))
  },
  reportClientError(payload: {
    message: string
    stack?: string
    route?: string
    locale?: string
    appVersion?: string
    errorId?: string
    queuePending?: number
    queueOldestAgeSec?: number
  }): Promise<void> {
    return request('/api/client-errors', jsonInit('POST', payload))
  },
}

// ------------------------------------------------------------------ 字典

export interface Venue {
  id: number
  code: string
  name: string
  enabled: boolean
}

export interface PriceBand {
  id: number
  code: string
  lowerBound: number | null
  upperBound: number | null
  enabled: boolean
}

/** 录入页只用启用会场（停用会场仍可补录属后端语义，前端下拉不给入口）。 */
export function fetchVenues(enabledOnly: boolean): Promise<Venue[]> {
  const query = enabledOnly ? '?enabled=true' : ''
  return request(`/api/venues${query}`, { method: 'GET' })
}

export function fetchPriceBands(): Promise<PriceBand[]> {
  return request('/api/price-bands', { method: 'GET' })
}

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
  venueId: number
  buyDate: string
  purchasePrice: number
  warehouse: number
  fee?: number
  shippingFee?: number
  tax?: number
  shelfNo?: string
  warehouseInDate?: string
  groupNo?: string
  remark?: string
}

/** 录入成功响应（生成列 totalCost 已回填；未售时 profit 为 null 不出现）。 */
export interface ItemResponse {
  id: number
  itemCode: string
  venueId: number
  venueCode: string
  year: number
  yearCode: string
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
  priceBandCode: string
  warehouse: number
  shelfNo: string | null
  warehouseInDate: string | null
  groupNo: string | null
  remark: string | null
  stockStatus: number
  saleStatus: number
  voided: boolean
  deleted: boolean
  createdAt: string
}

export function createItem(payload: CreateItemPayload): Promise<ItemResponse> {
  return request('/api/items', jsonInit('POST', payload))
}
