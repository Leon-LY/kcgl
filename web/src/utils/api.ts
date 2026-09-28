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

export interface YearCode {
  id: number
  year: number
  code: string
}

/** 录入页只用启用会场（停用会场仍可补录属后端语义，前端下拉不给入口）。 */
export function fetchVenues(enabledOnly: boolean): Promise<Venue[]> {
  const query = enabledOnly ? '?enabled=true' : ''
  return request(`/api/venues${query}`, { method: 'GET' })
}

export function fetchPriceBands(): Promise<PriceBand[]> {
  return request('/api/price-bands', { method: 'GET' })
}

export function fetchYearCodes(): Promise<YearCode[]> {
  return request('/api/year-codes', { method: 'GET' })
}

// ------------------------------------------------------------------ 字典管理（M2-8b-2）

/** 会场创建（E+，现场自救）/改名（code 是管理号快照来源，锁定不可改）。 */
export function createVenue(payload: { code: string; name: string }): Promise<Venue> {
  return request('/api/venues', jsonInit('POST', payload))
}

export function renameVenue(id: number, name: string): Promise<Venue> {
  return request(`/api/venues/${id}`, jsonInit('PUT', { name }))
}

/** 会场停用/启用（仅管理员；只停用不物理删——历史引用仍在）。 */
export function setVenueStatus(id: number, enabled: boolean): Promise<Venue> {
  return request(`/api/venues/${id}/status`, jsonInit('PATCH', { enabled: enabled ? 1 : 0 }))
}

/** 档位增/改：左闭右开 [lower, upper)；NULL=无界端；重叠由服务层行锁内校验。 */
export interface PriceBandUpsertPayload {
  code: string
  lowerBound: number | null
  upperBound: number | null
}

export function createPriceBand(payload: PriceBandUpsertPayload): Promise<PriceBand> {
  return request('/api/price-bands', jsonInit('POST', payload))
}

export function updatePriceBand(id: number, payload: PriceBandUpsertPayload): Promise<PriceBand> {
  return request(`/api/price-bands/${id}`, jsonInit('PUT', payload))
}

export function setPriceBandStatus(id: number, enabled: boolean): Promise<PriceBand> {
  return request(`/api/price-bands/${id}/status`, jsonInit('PATCH', { enabled: enabled ? 1 : 0 }))
}

/** 年代号增/改（年份↔代号双向唯一；Z 用尽后扩展双字母前的运维口）。 */
export function createYearCode(payload: { year: number; code: string }): Promise<YearCode> {
  return request('/api/year-codes', jsonInit('POST', payload))
}

export function updateYearCode(id: number, payload: { year: number; code: string }): Promise<YearCode> {
  return request(`/api/year-codes/${id}`, jsonInit('PUT', payload))
}

// ------------------------------------------------------------------ 账号管理（M2-8b-3）

/** locked 为即时计算值（lockedUntil 晚于当前时刻），非列直传。 */
export interface AdminUser {
  id: number
  username: string
  displayName: string
  role: number
  locale: string
  enabled: boolean
  mustChangePwd: boolean
  locked: boolean
  lockedUntil: string | null
  failedAttempts: number
  lastLoginAt: string | null
}

export interface AdminUserPage {
  list: AdminUser[]
  total: number
  page: number
  size: number
}

export function fetchUsers(page = 1, size = 50): Promise<AdminUserPage> {
  return request(`/api/users?page=${page}&size=${size}`, { method: 'GET' })
}

/** 创建：username 不可改；密码为管理员设定的初始密码（首登强制改密）。 */
export function createUser(payload: {
  username: string
  displayName: string
  role: number
  locale: string
  password: string
}): Promise<AdminUser> {
  return request('/api/users', jsonInit('POST', payload))
}

export function updateUser(
  id: number,
  payload: { displayName: string; role: number; locale: string },
): Promise<AdminUser> {
  return request(`/api/users/${id}`, jsonInit('PUT', payload))
}

export function setUserStatus(id: number, enabled: boolean): Promise<AdminUser> {
  return request(`/api/users/${id}/status`, jsonInit('PATCH', { enabled: enabled ? 1 : 0 }))
}

/** 手动解锁（清锁定与失败计数；对应登录防爆破 15 分钟锁）。 */
export function unlockUser(id: number): Promise<AdminUser> {
  return request(`/api/users/${id}/unlock`, jsonInit('PATCH', {}))
}

/** 重置密码：初始密码仅本响应出现一次，管理员转交用户后首登强制改密。 */
export function resetUserPassword(id: number): Promise<{ initialPassword: string }> {
  return request(`/api/users/${id}/password-reset`, jsonInit('POST', {}))
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
  /** 取り消し理由（作废件详情/扫旧码提示用）。 */
  voidReason: string | null
  /** 作废重录互链：本件为 {reEntryOf} 的再登録件。 */
  reEntryOf: number | null
  deleted: boolean
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

/** 确认入库行：clientReqId=行级幂等键（重试复用同键，服务端读回原结果）。 */
export interface ConfirmArrivalLine {
  itemId: number
  clientReqId: string
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

// ------------------------------------------------------------------ 首启 checklist（M2-8b-3）

export interface Checklist {
  hasStaffUser: boolean
  hasVenue: boolean
  hasPriceBand: boolean
  hasItem: boolean
  printDone: boolean
}

export function fetchChecklist(): Promise<Checklist> {
  return request('/api/checklist', { method: 'GET' })
}

/** 试打标签完成标记（幂等；仅管理员）。 */
export function markChecklistPrintDone(): Promise<Checklist> {
  return request('/api/checklist/print-done', jsonInit('POST', {}))
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

/** 手动上架标记（雅虎手工出品后即时登记，消除 CSV 回传窗口的滞销误报）。 */
export function markListedItem(itemId: number, clientReqId: string): Promise<ActionResult> {
  return request('/api/inventory/mark-listed', jsonInit('POST', { itemId, clientReqId }))
}
