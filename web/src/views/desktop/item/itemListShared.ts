import type { Composer } from 'vue-i18n'
import type { RecycleBatchEntry } from '@/utils/api'
import { newClientId } from '@/utils/id'

/**
 * 商品一覧的**跨页签**共用件：主列表页签与回收站页签（ItemRecycleTab）各持一份
 * 绑定，语义与拆分前逐字一致。
 *
 * 为什么放这里而不是 composable：这些是纯函数（给 t 就能算），没有响应式状态、
 * 没有生命周期，做成 composable 只是给纯函数套一层壳。同目录的兄弟 view
 * （print/label.ts）也是这个办法。
 */

/** 分页大小：主列表与回收站同档（20 件/页）。 */
export const ITEM_LIST_PAGE_SIZE = 20

/**
 * 行内文案与标签色的取法。返回绑定好 t 的一组函数，模板里的调用名与拆分前
 * 完全一致（warehouseOf / stockText / …），所以两处模板都不用改写法。
 *
 * 空值一律兜底：el-table-column 渲染列时会以 {row:{}} 探测嵌套列
 * （TableColumnRenderer），动态 i18n key 不兜底的话，空数据页也会刷
 * missing-key 告警（D-056）。
 */
export function itemListDisplay(t: Composer['t']) {
  return {
    warehouseOf: (target: number | null | undefined): string =>
      target == null ? '—' : t(`common.warehouse.${target}`),
    stockText: (status: number | null | undefined): string =>
      status == null ? '' : t(`scan.stock.${status}`),
    saleText: (status: number | null | undefined): string =>
      status == null ? '' : t(`scan.sale.${status}`),
    slowBadge: (level: number | null | undefined): string =>
      level === 2 ? t('items.slowRedBadge') : level === 1 ? t('items.slowYellowBadge') : '',
    stockTagClass: (status: number | null | undefined): string =>
      status === 1 ? 'is-success' : 'is-neutral',
    saleTagClass: (status: number | null | undefined): string =>
      status === 1 ? 'is-warning' : status === 2 ? 'is-success' : 'is-neutral',
  }
}

/**
 * 服务端逐件回的是错误码；先探键再取，避免 missing-key 告警刷屏（D-056）。
 * 用 te 而非 `t(key) === key`：后者在 dev/e2e 下会把告警刷出来，而 e2e 有
 * 「intlify 缺 key 告警」兜底断言，一刷就红。
 */
export function errorText(t: Composer['t'], te: Composer['te'], code: number): string {
  const key = `errors.${code}`
  return te(key) ? t(key) : String(code)
}

/**
 * 逐件各自成键：`stock_ledger.client_req_id` 是 CHAR(36) 且带唯一索引，批次级单键
 * 无法派生出各件子键（UUID 已占满 36 位），故由前端按件生成（D-126）。
 * 必须走 newClientId——crypto.randomUUID 仅安全上下文存在，http 部署下必抛 TypeError。
 */
export function batchEntriesOf(targets: readonly { id: number }[]): RecycleBatchEntry[] {
  return targets.map((target) => ({ id: target.id, clientReqId: newClientId() }))
}
