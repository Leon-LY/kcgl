/**
 * API 客户端唯一出口：统一信封 {code,message,data} 解析、错误标准化、401 全局处理。
 * 后端契约（docs/01 六节）：code=0 成功；非 0 为业务/系统错误；500 带 errorId（=traceId）。
 * 同源部署（SameSite=Strict 依赖），fetch 不需要显式带 Cookie 之外的配置。
 */

import { dayjs } from '@/utils/format'

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
  /** 用户主键：SSE 回声抑制比对基准（自己操作的广播不触发失效，D-070）。 */
  id: number
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

/** 手动上架标记（雅虎手工出品后即时登记；流拍后重上=在库已取消→在售）。 */
export function markListedItem(itemId: number, clientReqId: string): Promise<ActionResult> {
  return request('/api/inventory/mark-listed', jsonInit('POST', { itemId, clientReqId }))
}

/** 手动取消标记（流拍/出品取消的登记口，D-069：受注表无取消信息，唯一来源=手动）。 */
export function markCanceledItem(itemId: number, clientReqId: string): Promise<ActionResult> {
  return request('/api/inventory/mark-canceled', jsonInit('POST', { itemId, clientReqId }))
}

// ------------------------------------------------------------------ 盘点（M3-⑥，docs/01 7.3）

/** 盘点单摘要（发起/列表/详情/close 共用）。 */
export interface StocktakeSummary {
  id: number
  stocktakeNo: string
  warehouse: number
  /** 0进行中 1待确认（已 close） 2已确认 3作废。 */
  status: number
  /** close 时冻结的期望数（该仓在库未删未废），未 close 为 null。 */
  expectedCount: number | null
  scannedCount: number
  diffCount: number | null
  /** 待确认差异数（全部处理完单据自动转已确认），未 close 为 null。 */
  pendingDiffCount: number | null
  createdAt: string
  closedAt: string | null
  createdByName: string
  closedByName: string | null
  /** 当前登录用户是否发起人（撤销按钮渲染依据；服务端仍强校验发起人身份）。 */
  mine: boolean
}

/** 发起盘点：同仓已有进行中单 → 409009（前端引导跳转既有单）。 */
export function createStocktake(warehouse: number): Promise<StocktakeSummary> {
  return request('/api/stocktakes', jsonInit('POST', { warehouse }))
}

export interface StocktakeList {
  total: number
  page: number
  size: number
  rows: StocktakeSummary[]
}

/** 盘点单列表（创建时间倒序）；status 省略=全部。 */
export function fetchStocktakes(page: number, size = 20, status?: number): Promise<StocktakeList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (status != null) {
    query.set('status', String(status))
  }
  return request(`/api/stocktakes?${query}`, { method: 'GET' })
}

export function fetchStocktake(id: number): Promise<StocktakeSummary> {
  return request(`/api/stocktakes/${id}`, { method: 'GET' })
}

/** 盘点扫码：repeated=同单同件重复扫（服务端照记一次，200 不报错）。 */
export interface StocktakeScanResult {
  repeated: boolean
  item: ItemResponse
  thumbUrl: string | null
}

/** 盘点扫码（卡内警示——他仓货/冻结品/系统非在库——由前端按 item 状态派生，照记不拦）。 */
export function scanStocktakeItem(stocktakeId: number, code: string): Promise<StocktakeScanResult> {
  return request(`/api/stocktakes/${stocktakeId}/scans`, jsonInit('POST', { code }))
}

/** close：冻结期望集合并生成差异表（盘点期间的自然变动落入差异，由人工裁决）。 */
export function closeStocktake(stocktakeId: number): Promise<StocktakeSummary> {
  return request(`/api/stocktakes/${stocktakeId}/close`, jsonInit('POST', {}))
}

/** 发起人撤自己的单（仅进行中可撤；非发起人 403001）。 */
export function cancelStocktake(stocktakeId: number): Promise<StocktakeSummary> {
  return request(`/api/stocktakes/${stocktakeId}/cancel`, jsonInit('POST', {}))
}

/** 差异行：diffType 1盘亏 2盘盈 3仓库不符 4冻结品（冻结品禁止 CONFIRM，仅可 IGNORE/线下处理）。 */
export interface StocktakeDiffRow {
  id: number
  itemId: number
  itemCode: string
  diffType: number
  expectedWarehouse: number | null
  actualWarehouse: number | null
  /** 含「盘点期间发生过变动流水」标注（辅助裁决）。 */
  note: string | null
  /** 0待确认 1确认调整 2忽略。 */
  confirmStatus: number
  thumbUrl: string | null
}

export interface StocktakeDiffList {
  total: number
  page: number
  size: number
  rows: StocktakeDiffRow[]
}

/** 差异表（待确认优先）；confirmStatus 省略=全部。 */
export function fetchStocktakeDiffs(
  stocktakeId: number,
  page: number,
  size = 20,
  confirmStatus?: number,
): Promise<StocktakeDiffList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (confirmStatus != null) {
    query.set('confirmStatus', String(confirmStatus))
  }
  return request(`/api/stocktakes/${stocktakeId}/diffs?${query}`, { method: 'GET' })
}

/** 差异处理结果：result 仅 CONFIRM 返回（STOCKTAKE_ADJUST 后的商品现态）。 */
export interface StocktakeDiffActionResponse {
  diff: StocktakeDiffRow
  result: ActionResult | null
}

/** CONFIRM 幂等键契约同库存动作（7.0）：失败重试复用同键；IGNORE 状态置位即原结果，天然幂等。 */
export function resolveStocktakeDiff(
  stocktakeId: number,
  diffId: number,
  action: 'CONFIRM' | 'IGNORE',
  clientReqId: string,
): Promise<StocktakeDiffActionResponse> {
  return request(
    `/api/stocktakes/${stocktakeId}/diffs/${diffId}`,
    jsonInit('POST', { action, clientReqId }),
  )
}

// ------------------------------------------------------------------ 雅虎受注导入（M5-②b，docs/01 7.4/7.2，D-069）

/** 错误行采样条目（后端前 1000 条采样）。 */
export interface YahooImportErrorRow {
  line: number
  raw: string
  reason: string
}

/**
 * 导入批次报告：status 0处理中 1完成 2失败；失败批次计数为 null。
 * rowCount=物理数据行；matched/unmatched 按子行（まとめ売り一行拆 N 子行）；
 * note=まとめ売り等批次级補注（単価未分割）。
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
  errorMessage: string | null
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

/** 出荷待ち清单。 */
export function fetchPendingShipments(): Promise<YahooPendingShipmentList> {
  return request('/api/yahoo/pending-shipments', { method: 'GET' })
}

// ------------------------------------------------------------------ Excel 导入导出（M4-⑤，D-058）

/** 错误行采样条目（后端前 1000 条采样）。 */
export interface ExcelImportErrorRow {
  line: number
  raw: string
  reason: string
}

/** 导入批次报告：status 0处理中 1完成 2失败；note=计数器跳变说明（A0→A5 等）。 */
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

/** 二进制下载结果：blob + 从 Content-Disposition 解析的文件名。 */
export interface BlobDownload {
  blob: Blob
  filename: string
}

/**
 * 二进制下载（模板/导出=原始 xlsx 流，无 JSON 信封）；失败时后端仍回 JSON
 * 信封（如倒挂区间 400）→ 标准化为 ApiError。成功路径不走 response.json()。
 */
async function requestBlob(path: string, fallbackFilename: string): Promise<BlobDownload> {
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
function filenameFrom(headers: Headers, fallback: string): string {
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

// ------------------------------------------------------------------ 设置与统计（M5-③）

/** 系统设置快照（GET /api/settings 与 PUT 后响应同构；未落库键=服务端默认值）。 */
export interface SettingsData {
  /** 滞销黄色阈值（天） */
  warnDays: number
  /** 滞销红色阈值（天） */
  alarmDays: number
  labelPreset: 'small' | 'medium' | 'large' | 'custom'
  /** 自定义标签幅（mm，仅 labelPreset=custom 时被打印页消费） */
  labelWidthMm: number
  /** 自定义标签高（mm） */
  labelHeightMm: number
}

export function fetchSettings(): Promise<SettingsData> {
  return request('/api/settings', { method: 'GET' })
}

/**
 * 更新单个设置键（管理员）。具体值校验在前端预检，服务端兜底 400017；
 * 黄红跨字段关系（warn<alarm）由调用方按「另一侧现值」排出 PUT 顺序。
 */
export function updateSetting(key: string, value: string): Promise<SettingsData> {
  return request(`/api/settings/${encodeURIComponent(key)}`, jsonInit('PUT', { value }))
}

/** 单仓/全仓运营指标行（warehouse=null=全仓合计；件均库龄无样本为 null）。 */
export interface WarehouseStats {
  warehouse: number | null
  totalItems: number
  inTransit: number
  inStock: number
  shipped: number
  monthInbound: number
  monthOutbound: number
  slowWarn: number
  slowRed: number
  stockValue: number
  avgStockAgeDays: number | null
}

/** 雅虎同步卡：三活视图口径计数 + 最近一次成功受注导入的新鲜度（无成功批次为 null）。 */
export interface YahooStats {
  soldNotShipped: number
  canceledNotRelisted: number
  withdrawNeeded: number
  lastImportFinishedAt: string | null
  lastImportFilename: string | null
}

export interface DashboardStats {
  fleet: WarehouseStats
  yahoo: YahooStats
}

/** 大盘（全仓合计+雅虎同步卡）；页加载拉取，无 SSE（聚合快照非协作面）。 */
export function fetchDashboardStats(): Promise<DashboardStats> {
  return request('/api/stats/dashboard', { method: 'GET' })
}

/** 两仓明细（名古屋/福岡各一行，序固定 1→2）。 */
export function fetchWarehouseStats(): Promise<WarehouseStats[]> {
  return request('/api/stats/warehouses', { method: 'GET' })
}

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
}

export function fetchSystemStatus(): Promise<SystemStatus> {
  return request('/api/stats/system', { method: 'GET' })
}

/** 诊断包下载（管理员）：システム状況快照+告警近 30+错误样本+环形缓冲，JSON 附件。 */
export function downloadDiagnosticsExport(): Promise<BlobDownload> {
  return requestBlob('/api/diagnostics/export', 'kcgl-diagnostics.json')
}

/** 统一告警行：level 1提示 2警告 3错误；status 0开启 1已读。 */
export interface SysAlert {
  id: number
  type: string
  dedupKey: string
  level: number
  message: string
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

