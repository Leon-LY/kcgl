import { markRaw } from 'vue'
import type { LocationQuery } from 'vue-router'
import type { ItemSearchParams } from '@/utils/api'

/**
 * 商品一覧の絞り込み条件を**ひとつの値**として扱う（D-142）。
 *
 * 拆分前它们是八个各自独立的 ref，但代码里早就有两处把它们当一整套用：URL 投影
 * （QUERY_KEYS 逐键遍历）与检索参数构造（逐键读值）。既然读写两侧都是"整套"，
 * 就把它们收成一个对象——筛选条组件因此只需一个 v-model，URL 编解码也成了纯函数，
 * 可以直接对拍而不必挂载组件。
 *
 * 放 .ts 而不是 composable：全是纯函数（给条件就出参数/查询串），没有响应式状态、
 * 没有生命周期。同目录的 itemListShared.ts 与兄弟 view 的 print/label.ts 同此。
 */

/**
 * 「すべて」选项值：共享对象哨兵——EP el-option 的 value prop 不收 null（每次挂载
 * 刷 prop 类型警告），空串又落 EP「空值=显示 placeholder」的歧义；对象值类型合法
 * 且与业务值永不碰撞，提交前统一映射回 undefined。markRaw 必须有：否则 ref 深层
 * reactive 代理化会破坏 `=== ALL` 身份比较（映射失效+选项匹配不上「すべて」）。
 */
export const ALL = markRaw({ all: true } as const)

/** 下拉字段的类型：要么是业务数，要么是「すべて」哨兵。 */
export type FilterValue = typeof ALL | number

export interface ItemFilterState {
  kw: string
  warehouse: FilterValue
  stockStatus: FilterValue
  saleStatus: FilterValue
  venueId: FilterValue
  buyDateFrom: string | null
  buyDateTo: string | null
  warnLevel: FilterValue
}

/** 空条件，也是「条件をクリア」的目标态。每次返回新对象（不可变约定）。 */
export function emptyFilters(): ItemFilterState {
  return {
    kw: '',
    warehouse: ALL,
    stockStatus: ALL,
    saleStatus: ALL,
    venueId: ALL,
    buyDateFrom: null,
    buyDateTo: null,
    warnLevel: ALL,
  }
}

/** 各下拉的合法取值（与模板选项同域）。URL 是用户可手改的输入：域外值落回「すべて」，
 *  否则 el-select 匹配不到任何选项（筛选条显空白）且检索会发出非法参数。 */
const FILTER_DOMAINS: Record<string, readonly number[]> = {
  warehouse: [1, 2],
  stockStatus: [0, 1, 2],
  saleStatus: [0, 1, 2, 3],
  warnLevel: [1, 2],
}

function domainFilter(raw: unknown, domain: readonly number[]): FilterValue {
  const value = typeof raw === 'string' ? Number(raw) : Number.NaN
  return Number.isInteger(value) && domain.includes(value) ? value : ALL
}

function positiveInt(raw: unknown): number | null {
  const value = typeof raw === 'string' ? Number(raw) : Number.NaN
  return Number.isInteger(value) && value >= 1 ? value : null
}

/** 日付筛选只认 el-date-picker 的 value-format 口径（YYYY-MM-DD），其余视为未选。 */
function dateFilter(raw: unknown): string | null {
  return typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(raw) ? raw : null
}

/** 哨兵不可被 === 收窄（对象类型无名义恒等），故以 typeof 判别业务值分支。 */
function businessOf(value: FilterValue): number | undefined {
  return typeof value === 'number' ? value : undefined
}

/** URL 查询串 → 条件（首载前调用一次，C1）。 */
export function filtersFromQuery(query: LocationQuery): ItemFilterState {
  return {
    kw: typeof query.kw === 'string' ? query.kw : '',
    warehouse: domainFilter(query.warehouse, FILTER_DOMAINS.warehouse),
    stockStatus: domainFilter(query.stockStatus, FILTER_DOMAINS.stockStatus),
    saleStatus: domainFilter(query.saleStatus, FILTER_DOMAINS.saleStatus),
    warnLevel: domainFilter(query.warnLevel, FILTER_DOMAINS.warnLevel),
    venueId: positiveInt(query.venueId) ?? ALL,
    buyDateFrom: dateFilter(query.buyDateFrom),
    buyDateTo: dateFilter(query.buyDateTo),
  }
}

/** 页码也走 URL，但它不是筛选条件（清条件不清"我现在在第几页"），故单独取。 */
export function pageFromQuery(query: LocationQuery): number {
  return positiveInt(query.page) ?? 1
}

/** 条件 + 页码 → 检索参数。trim 与哨兵映射都只在这里发生，单一口径。 */
export function listRequest(filters: ItemFilterState, page: number, size: number): ItemSearchParams {
  return {
    kw: filters.kw.trim() === '' ? undefined : filters.kw.trim(),
    warehouse: businessOf(filters.warehouse),
    stockStatus: businessOf(filters.stockStatus),
    saleStatus: businessOf(filters.saleStatus),
    venueId: businessOf(filters.venueId),
    buyDateFrom: filters.buyDateFrom ?? undefined,
    buyDateTo: filters.buyDateTo ?? undefined,
    warnLevel: businessOf(filters.warnLevel),
    page,
    size,
  }
}

/** 可投影到 URL 的键（与检索参数同名；size 恒定，不投影）。 */
const QUERY_KEYS = [
  'kw', 'warehouse', 'stockStatus', 'saleStatus', 'venueId',
  'buyDateFrom', 'buyDateTo', 'warnLevel', 'page',
] as const

/**
 * 检索参数 → URL 查询串：只投影**非默认**项，空态保持 /items 裸路径（下钻详情后
 * 返回即回到同一屏幕）。入参就是真正发给服务端的那一份，故不会出现「URL 带筛选
 * 而列表没有」的漂移。
 */
export function queryOf(params: ItemSearchParams): Record<string, string> {
  const query: Record<string, string> = {}
  for (const key of QUERY_KEYS) {
    const value = params[key]
    if (value == null || value === '' || (key === 'page' && value === 1)) {
      continue
    }
    query[key] = String(value)
  }
  return query
}

/** 查询串等价判定：等价就不 replace，免得产生一次无谓的导航。 */
export function sameQuery(current: LocationQuery, next: Record<string, string>): boolean {
  const keys = Object.keys(next)
  return Object.keys(current).length === keys.length && keys.every((key) => current[key] === next[key])
}
