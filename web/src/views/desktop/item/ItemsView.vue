<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import AppEmptyState from '@/components/AppEmptyState.vue'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { formatJstDate, formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import {
  deleteItemsBatch,
  fetchRecycleBin,
  fetchVenues,
  restoreItem,
  restoreItemsBatch,
  searchItems,
} from '@/utils/api'
import type {
  ActionResult,
  ItemSearchParams,
  ItemSearchRow,
  RecycleBatchEntry,
  RecycleBinRow,
  Venue,
} from '@/utils/api'
import { newClientId } from '@/utils/id'
import { availableActions } from '@/utils/inventoryActions'
import type { ScanAction } from '@/utils/inventoryActions'
import ItemActionDialog from './ItemActionDialog.vue'
import ItemAdjustDialog from './ItemAdjustDialog.vue'
import ItemBatchDeleteDialog from './ItemBatchDeleteDialog.vue'

/**
 * 商品一覧（M5-①，D-061）：kw 搜索（管理号＞日期＞模糊 LIKE 优先级链）+
 * 仓库/库存/销售/会场/落札日区间/滞留六路筛选，行点击进详情；滞留黄红徽标
 * 与筛选共用服务端边界（D-065）。第二标签=削除済み商品（仅管理员，软删恢复）。
 * 作废/软删件已被服务端排除；他人操作经 SSE 失效自动整页重取。
 */

const PAGE_SIZE = 20

const { t, te } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const isAdmin = computed(() => auth.me != null && auth.me.role === 1)

/**
 * 列宽与「钉列横滑」（D-126，取代 D-120 的视口分档）。
 *
 * 单列宽度都不是估的——用真实 Chromium 量每种单元格「一行放下」的自然宽（含 12px×2
 * 单元格内边距）后取上界，如「状態」最宽组合=出庫済み+キャンセル 需 159、「仕入単価」
 * 8 位数 ￥99,999,999 需 108、「商品」11 位管理号（正则上限 AA12-AAA99Z）需 168。
 * 唯一的例外是「操作」列（96）：格子里只有两个字宽的触发器按钮，宽度与内容无关，
 * 按标签「操作」两字 + 单元格内边距取值；行内增删列宽度改一次，这条与列宽合计的
 * 用例（ItemsView.spec.ts）都要跟着改，别只改一处。
 *
 * D-120 的做法是**按视口收列**（窄屏藏起落札日/棚番号/滞留/会場/倉庫），阈值与合计
 * 都靠实测钉死。D-126 加了勾选列（44）与「操作」列后，那套分档被整体换掉
 * （「操作」列 D-129 从只有「削除」一个链接按钮的 64 宽改成动作菜单的 96 宽：
 * 触发器是两个字，菜单项≥6 个并含管理员的「状態修正/削除」，64 会把「操作」折行）：
 * 收列等于**把信息藏起来**，而列宽预算里最先被收掉的那几列（会場/倉庫/落札日/滞留）
 * 恰好都有对应筛选器——用户在表上看见的与筛选器能问的不一致，是"看不全"而不是"放不下"。
 *
 * 改为：**全列常显 + 左右钉列 + 中间横滑**。el-table 的 fixed 列用 sticky 实现，
 * 宽屏列放得下时本就不出滚动条，所以这不是"窄屏降级"，而是一套自适应布局：
 *   左钉 勾选列 + 商品（管理番号是全表唯一的身份，横滑时必须一直看得见）
 *   右钉 状態 + 操作（主列表）/ 操作（回收站）
 * 中间列自行横滑，任何视口下都不丢列。
 *
 * 实测（真实 Chromium，视口 1000-1920，高度 700 保证出竖滚动条）：页面级横滑**全为 0**，
 * 表格内部横滑随视口收窄单调增大（1200/1280/1400/1600 → 552/472/352/152，1920 放得下
 * 则 0），左钉与右钉列在把中间列滑到底后位移 0px。**能这样成立的前提是下面
 * `.items-view` 的 `minmax(0, 1fr)`**——不加它，卡片会被列的 min-content 撑开，
 * el-table 内部永远不出横滑条，钉列就是摆设（详见该处注释）。
 *
 * 回收站的「状態」不钉：它与「操作」列之间还隔着削除日時与削除理由，钉在中间会被
 * 截断成孤立的一条。主列表的「状態」紧邻「操作」，两列一起钉才成立。
 */

const activeTab = ref('list')

/** 一括入出力（D-106）：落到 Excel 页的对应标签页，批次历史与报告仍在原页。 */
function onBulkCommand(command: 'import' | 'export'): void {
  void router.push({ name: 'excel', query: { tab: command } })
}

// ------------------------------------------------------------- 商品一覧

/** 「すべて」选项值：共享对象哨兵——EP el-option 的 value prop 不收 null（每次
 *  挂载刷 prop 类型警告），空串又落 EP「空值=显示 placeholder」的歧义；对象值
 *  类型合法且与业务值永不碰撞，提交前统一映射回 undefined。markRaw 必须有：
 *  否则 ref 深层 reactive 代理化会破坏 `=== ALL` 身份比较（映射失效+选项匹配
 *  不上「すべて」）。 */
const ALL = markRaw({ all: true } as const)

const kw = ref('')
const warehouse = ref<typeof ALL | number>(ALL)
const stockStatus = ref<typeof ALL | number>(ALL)
const saleStatus = ref<typeof ALL | number>(ALL)
const venueId = ref<typeof ALL | number>(ALL)
const buyDateFrom = ref<string | null>(null)
const buyDateTo = ref<string | null>(null)
const warnLevel = ref<typeof ALL | number>(ALL)

const venues = ref<Venue[]>([])
const rows = ref<ItemSearchRow[]>([])
const total = ref(0)
const page = ref(1)
const listLoading = ref(true)
const listError = ref('')
let listSeq = 0

/** 可投影到 URL 的键（与检索参数同名；size 恒定，不投影）。 */
const QUERY_KEYS = [
  'kw', 'warehouse', 'stockStatus', 'saleStatus', 'venueId',
  'buyDateFrom', 'buyDateTo', 'warnLevel', 'page',
] as const

/** 各下拉的合法取值（与模板选项同域）。URL 是用户可手改的输入：域外值落回「すべて」，
 *  否则 el-select 匹配不到任何选项（筛选条显空白）且检索会发出非法参数。 */
const FILTER_DOMAINS: Record<string, readonly number[]> = {
  warehouse: [1, 2],
  stockStatus: [0, 1, 2],
  saleStatus: [0, 1, 2, 3],
  warnLevel: [1, 2],
}

function domainFilter(raw: unknown, domain: readonly number[]): typeof ALL | number {
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

/** URL 查询串 → 列表态（首载前调用一次，C1）。 */
function hydrateFromQuery(): void {
  const query = route.query
  kw.value = typeof query.kw === 'string' ? query.kw : ''
  warehouse.value = domainFilter(query.warehouse, FILTER_DOMAINS.warehouse)
  stockStatus.value = domainFilter(query.stockStatus, FILTER_DOMAINS.stockStatus)
  saleStatus.value = domainFilter(query.saleStatus, FILTER_DOMAINS.saleStatus)
  warnLevel.value = domainFilter(query.warnLevel, FILTER_DOMAINS.warnLevel)
  venueId.value = positiveInt(query.venueId) ?? ALL
  buyDateFrom.value = dateFilter(query.buyDateFrom)
  buyDateTo.value = dateFilter(query.buyDateTo)
  page.value = positiveInt(query.page) ?? 1
}

/** 列表态 → URL：只投影**非默认**项，空态保持 /items 裸路径（下钻详情后返回即回到同一屏幕）。 */
function projectQuery(params: ItemSearchParams): void {
  const query: Record<string, string> = {}
  for (const key of QUERY_KEYS) {
    const value = params[key]
    if (value == null || value === '' || (key === 'page' && value === 1)) {
      continue
    }
    query[key] = String(value)
  }
  if (sameQuery(route.query, query)) {
    return
  }
  // replace：筛选/换页不产生历史项——后退键回到进入列表前的页面，而非逐条回放筛选
  void router.replace({ name: 'items', query })
}

function sameQuery(current: LocationQuery, next: Record<string, string>): boolean {
  const keys = Object.keys(next)
  return Object.keys(current).length === keys.length && keys.every((key) => current[key] === next[key])
}

async function loadList(): Promise<void> {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = ''
  const params: ItemSearchParams = {
    kw: kw.value.trim() === '' ? undefined : kw.value.trim(),
    // 对象哨兵不可被 === 收窄（对象类型无名义恒等）：以 typeof 判别业务值分支
    warehouse: typeof warehouse.value === 'number' ? warehouse.value : undefined,
    stockStatus: typeof stockStatus.value === 'number' ? stockStatus.value : undefined,
    saleStatus: typeof saleStatus.value === 'number' ? saleStatus.value : undefined,
    venueId: typeof venueId.value === 'number' ? venueId.value : undefined,
    buyDateFrom: buyDateFrom.value ?? undefined,
    buyDateTo: buyDateTo.value ?? undefined,
    warnLevel: typeof warnLevel.value === 'number' ? warnLevel.value : undefined,
    page: page.value,
    size: PAGE_SIZE,
  }
  // URL 投影与真正发出的检索参数**同源**：不会出现「URL 带筛选而列表没有」的漂移
  projectQuery(params)
  try {
    const data = await searchItems(params)
    if (seq !== listSeq) {
      return
    }
    rows.value = data.rows
    total.value = data.total
  } catch (error) {
    if (seq !== listSeq) {
      return
    }
    listError.value = toDisplayMessage(error, t)
  } finally {
    if (seq === listSeq) {
      listLoading.value = false
    }
  }
}

function onSearch(): void {
  page.value = 1
  void loadList()
}

function onPageChange(next: number): void {
  page.value = next
  void loadList()
}

function onClearFilters(): void {
  kw.value = ''
  warehouse.value = ALL
  stockStatus.value = ALL
  saleStatus.value = ALL
  venueId.value = ALL
  buyDateFrom.value = null
  buyDateTo.value = null
  warnLevel.value = ALL
  onSearch()
}

function goDetail(row: ItemSearchRow): void {
  void router.push({ name: 'item-detail', params: { id: row.id } })
}

// el-table-column 渲染列时以 {row:{}} 探测嵌套列（TableColumnRenderer），
// 动态 i18n key 必须空值兜底——否则空数据页也刷 missing-key 告警（D-056）
function warehouseOf(target: number | null | undefined): string {
  return target == null ? '—' : t(`common.warehouse.${target}`)
}

function stockText(status: number | null | undefined): string {
  return status == null ? '' : t(`scan.stock.${status}`)
}

function saleText(status: number | null | undefined): string {
  return status == null ? '' : t(`scan.sale.${status}`)
}

function slowBadge(level: number | null | undefined): string {
  return level === 2 ? t('items.slowRedBadge') : level === 1 ? t('items.slowYellowBadge') : ''
}

function stockTagClass(status: number | null | undefined): string {
  return status === 1 ? 'is-success' : 'is-neutral'
}

function saleTagClass(status: number | null | undefined): string {
  return status === 1 ? 'is-warning' : status === 2 ? 'is-success' : 'is-neutral'
}

// ------------------------------------------------------------- 削除済み商品（仅管理员）

const recycleRows = ref<RecycleBinRow[]>([])
const recycleTotal = ref(0)
const recyclePage = ref(1)
const recycleLoading = ref(false)
const recycleError = ref('')
const restoringIds = ref<number[]>([])
let recycleSeq = 0

async function loadRecycle(): Promise<void> {
  const seq = ++recycleSeq
  recycleLoading.value = true
  recycleError.value = ''
  try {
    const data = await fetchRecycleBin(recyclePage.value, PAGE_SIZE)
    if (seq !== recycleSeq) {
      return
    }
    recycleRows.value = data.rows
    recycleTotal.value = data.total
  } catch (error) {
    if (seq !== recycleSeq) {
      return
    }
    recycleError.value = toDisplayMessage(error, t)
  } finally {
    if (seq === recycleSeq) {
      recycleLoading.value = false
    }
  }
}

function onRecyclePageChange(next: number): void {
  recyclePage.value = next
  void loadRecycle()
}

function isRestoring(id: number): boolean {
  return restoringIds.value.includes(id)
}

async function onRestore(row: RecycleBinRow): Promise<void> {
  if (isRestoring(row.id)) {
    return
  }
  restoringIds.value = [...restoringIds.value, row.id]
  recycleError.value = ''
  try {
    // 同 ItemDeleteDialog：crypto.randomUUID 仅安全上下文可用，http 部署下必抛 TypeError
    await restoreItem(row.id, newClientId())
    // 与批删同理：復元把件送回列表那一侧，列表不刷就会一直显示"这件已经不在了"
    await Promise.all([loadRecycle(), loadList()])
  } catch (error) {
    recycleError.value = toDisplayMessage(error, t)
  } finally {
    restoringIds.value = restoringIds.value.filter((x) => x !== row.id)
  }
}

// ------------------------------------------------------------- 行内状态动作（D-129）

/**
 * 列表页直接改状态：合法动作由 availableActions 按现行两轴派生（与扫码页同一张
 * 边表，前端镜像后端 InventoryStateMachine，故这里不新增任何端点）。管理员另有
 * 「状態修正」= 任意态覆盖（POST /api/items/{id}/adjust，D4，须理由）与「削除」，
 * 两者都是 A-only，故与合法动作同列但分开分组——前者受状态机约束、后者不受，
 * 混在一组会让「为什么刚才那个动作不见了」变成状态机在背锅。
 *
 * 服务端只排除作废/软删件（死件走回收站端点），故列表结果里不必再判 voided/deleted。
 */
const canAct = computed(() => auth.me != null && auth.me.role <= 2)

const actionRow = ref<ItemSearchRow | null>(null)
const actionName = ref<ScanAction | null>(null)
const adjustRow = ref<ItemSearchRow | null>(null)
const adjustVisible = ref(false)

/**
 * 单行动作回执只存**数据**不存文案（同 batchResult 的 D-029 理由：切语言是运行时
 * 行为，此刻渲染成字符串存下，切到中文后这条提示会留在日文）。
 */
const statusNotice = ref<{ itemCode: string; action: ScanAction } | null>(null)

function rowActions(row: ItemSearchRow): ScanAction[] {
  return availableActions(row.stockStatus, row.saleStatus)
}

/** 一个菜单项：命令 + 文案键（+ 分组分隔，管理员的第一个动作前断开）。 */
interface RowCommand {
  command: string
  labelKey: string
  divided?: boolean
}

/**
 * 菜单构造放在这里而不是模板里 v-for/v-if 两趟：菜单内容是本功能的可见契约
 * （哪些态能做什么、管理员多出什么），单一函数才好整体断言，也免得「有分组线但
 * 只有一项」这类只有渲染出来才看得见的错。
 */
function rowCommands(row: ItemSearchRow): RowCommand[] {
  const legal = rowActions(row).map((action) => ({
    command: action as string,
    labelKey: `scan.action.${action}`,
  }))
  if (!isAdmin.value) {
    return legal
  }
  return [
    ...legal,
    { command: 'adjust', labelKey: 'items.detail.adjustTitle', divided: legal.length > 0 },
    { command: 'delete', labelKey: 'items.rowDelete' },
  ]
}

function onRowCommand(row: ItemSearchRow, command: string): void {
  if (command === 'delete') {
    openRowDelete(row)
    return
  }
  if (command === 'adjust') {
    adjustRow.value = row
    adjustVisible.value = true
    return
  }
  actionRow.value = row
  actionName.value = command as ScanAction
}

/** 弹层关闭：两个 prop 一起清，否则再次点同一行的同一动作时 prop 不变、弹层打不开。 */
function onActionClosed(): void {
  actionRow.value = null
  actionName.value = null
}

async function onActionDone(result: ActionResult, action: ScanAction): Promise<void> {
  onActionClosed()
  statusNotice.value = { itemCode: result.itemCode, action }
  // 状态变了，当前筛选下这一行可能已不属于本页（如「在庫」筛选里的売却），重取是唯一
  // 可靠的答案；本地改行会在筛选命不中时留下一个不该在这儿的幽灵行
  await loadList()
}

async function onAdjusted(): Promise<void> {
  adjustRow.value = null
  await loadList()
}

// ------------------------------------------------------------- 選択と一括操作（D-126）

const selectedRows = ref<ItemSearchRow[]>([])
const recycleSelectedRows = ref<RecycleBinRow[]>([])
const batchBusy = ref(false)
const batchDeleteVisible = ref(false)

type BatchKind = 'delete' | 'restore'

/**
 * 批量结果只存**数据**不存文案：全站切语言是运行时行为（D-029），此刻把
 * 「N 件を削除しました」渲染成字符串存下，切到中文后这条提示会留在日文。
 */
const batchResult = ref<{
  kind: BatchKind
  ok: number
  failures: { itemId: number; code: number }[]
} | null>(null)

/** 结果归属标签页：削除的结果只在主列表显示、復元的只在回收站显示，跨页张冠李戴最误导。 */
const deleteResult = computed(() =>
  batchResult.value?.kind === 'delete' ? batchResult.value : null,
)
const restoreResult = computed(() =>
  batchResult.value?.kind === 'restore' ? batchResult.value : null,
)

const batchMessage = computed(() => {
  const result = batchResult.value
  if (result == null) {
    return ''
  }
  return result.failures.length === 0
    ? t(`items.batch.${result.kind}Done`, { n: result.ok })
    : t(`items.batch.${result.kind}Partial`, { ok: result.ok, ng: result.failures.length })
})

const batchFailLines = computed(() =>
  (batchResult.value?.failures ?? []).map((failure) =>
    t('items.batch.failLine', {
      code: itemCodeOf(failure.itemId) ?? `#${failure.itemId}`,
      message: errorText(failure.code),
    }),
  ),
)

/** 失败行要报管理番号而非内部 id——管理番号才是现场认得出的东西。两个标签页都可能是来源。 */
function itemCodeOf(id: number): string | undefined {
  return (
    rows.value.find((row) => row.id === id)?.itemCode ??
    recycleRows.value.find((row) => row.id === id)?.itemCode
  )
}

/** 服务端逐件回的是错误码；先探键再取，避免 missing-key 告警刷屏（D-056）。 */
function errorText(code: number): string {
  const key = `errors.${code}`
  return te(key) ? t(key) : String(code)
}

function onSelectionChange(selection: ItemSearchRow[]): void {
  selectedRows.value = selection
}

function onRecycleSelectionChange(selection: RecycleBinRow[]): void {
  recycleSelectedRows.value = selection
}

/**
 * 行点击进详情，但**勾选列与操作列的点击各有其职**，不能被行点击抢走：Element 的
 * row-click 对任意单元格都派发，不拦就会出现「想勾一行，页面却跳走了」。
 * 两处分别按列类型（selection）与单元格内元素（.items-actions）判定——后者不依赖
 * 列顺序或列 prop，按钮日后挪位置也不会失效。
 */
function onRowClick(row: ItemSearchRow, column: unknown, event?: Event): void {
  const type = (column as { type?: string } | null)?.type
  const target = event?.target as HTMLElement | null
  if (type === 'selection' || target?.closest('.items-actions') != null) {
    return
  }
  goDetail(row)
}

/**
 * 逐件各自成键：`stock_ledger.client_req_id` 是 CHAR(36) 且带唯一索引，批次级单键
 * 无法派生出各件子键（UUID 已占满 36 位），故由前端按件生成（D-126）。
 * 必须走 newClientId——crypto.randomUUID 仅安全上下文存在，http 部署下必抛 TypeError。
 */
function entriesOf(targets: readonly { id: number }[]): RecycleBatchEntry[] {
  return targets.map((target) => ({ id: target.id, clientReqId: newClientId() }))
}

/**
 * 行内削除与勾选批删**共用一套确认与提交**（删 1 件就是删 1 件的批）：多一条独立
 * 路径就多一处幂等键与结果提示要维护，而单件路径没有任何批量路径不具备的语义。
 */
const deleteTargets = ref<ItemSearchRow[]>([])

function openRowDelete(row: ItemSearchRow): void {
  deleteTargets.value = [row]
  batchResult.value = null
  batchDeleteVisible.value = true
}

function openBatchDelete(): void {
  if (selectedRows.value.length === 0) {
    return
  }
  deleteTargets.value = selectedRows.value
  batchResult.value = null
  batchDeleteVisible.value = true
}

async function onBatchDeleteSubmit(reason: string): Promise<void> {
  if (batchBusy.value || deleteTargets.value.length === 0) {
    return
  }
  batchBusy.value = true
  try {
    const result = await deleteItemsBatch(
      entriesOf(deleteTargets.value),
      reason === '' ? undefined : reason,
    )
    batchDeleteVisible.value = false
    batchResult.value = { kind: 'delete', ok: result.succeeded, failures: result.failures }
    // 两个页签都刷：删掉的件进了回收站那一侧，只刷列表会让"切过去看刚删的件"落空
    // （E2E 实测踩到：回收站仍停在进页时的旧数据，新删的件根本不出现）
    await Promise.all([loadList(), loadRecycle()])
  } catch (error) {
    batchDeleteVisible.value = false
    listError.value = toDisplayMessage(error, t)
  } finally {
    batchBusy.value = false
  }
}

async function onBatchRestore(): Promise<void> {
  if (batchBusy.value || recycleSelectedRows.value.length === 0) {
    return
  }
  batchBusy.value = true
  try {
    const result = await restoreItemsBatch(entriesOf(recycleSelectedRows.value))
    batchResult.value = { kind: 'restore', ok: result.succeeded, failures: result.failures }
    await Promise.all([loadRecycle(), loadList()])
  } catch (error) {
    recycleError.value = toDisplayMessage(error, t)
  } finally {
    batchBusy.value = false
  }
}

// ------------------------------------------------------------- 装配

function reload(): void {
  page.value = 1
  void loadList()
  if (isAdmin.value) {
    recyclePage.value = 1
    void loadRecycle()
  }
}

// 录入/编辑/作废（ITEM）、库存动作（INVENTORY）、CSV 标记回写（YAHOO_IMPORT）都会改变列表
useSyncInvalidation(['ITEM', 'INVENTORY', 'YAHOO_IMPORT'], reload)

onMounted(() => {
  // 首载先消费 URL（从详情返回/深链直达时带回落札筛选与页码，C1）
  hydrateFromQuery()
  void fetchVenues(false)
    .then((data) => {
      venues.value = data
    })
    .catch(() => undefined) // 会场下拉加载失败不阻断列表（仅筛选项暂缺）
  void loadList()
  if (isAdmin.value) {
    void loadRecycle()
  }
})
</script>

<template>
  <section class="items-view">
    <AppPageHeader :title="t('items.title')" />

    <div class="kcgl-card items-body">
      <el-tabs
        v-model="activeTab"
        class="items-tabs"
      >
        <el-tab-pane
          :label="t('items.title')"
          name="list"
        >
          <div class="items-toolbar">
            <div class="items-search">
              <el-input
                v-model="kw"
                :placeholder="t('items.searchPlaceholder')"
                clearable
                @keyup.enter="onSearch"
                @clear="onSearch"
              />
              <el-button
                type="primary"
                @click="onSearch"
              >
                {{ t('items.search') }}
              </el-button>
            </div>
            <div class="items-filters">
              <el-select
                v-model="warehouse"
                class="items-filter-wh"
                @change="onSearch"
              >
                <el-option
                  :label="t('items.warehouseAll')"
                  :value="ALL"
                />
                <el-option
                  :label="t('common.warehouse.1')"
                  :value="1"
                />
                <el-option
                  :label="t('common.warehouse.2')"
                  :value="2"
                />
              </el-select>
              <el-select
                v-model="stockStatus"
                class="items-filter"
                @change="onSearch"
              >
                <el-option
                  :label="t('items.filterAll')"
                  :value="ALL"
                />
                <el-option
                  :label="t('scan.stock.0')"
                  :value="0"
                />
                <el-option
                  :label="t('scan.stock.1')"
                  :value="1"
                />
                <el-option
                  :label="t('scan.stock.2')"
                  :value="2"
                />
              </el-select>
              <el-select
                v-model="saleStatus"
                class="items-filter"
                @change="onSearch"
              >
                <el-option
                  :label="t('items.filterAll')"
                  :value="ALL"
                />
                <el-option
                  :label="t('scan.sale.0')"
                  :value="0"
                />
                <el-option
                  :label="t('scan.sale.1')"
                  :value="1"
                />
                <el-option
                  :label="t('scan.sale.2')"
                  :value="2"
                />
                <el-option
                  :label="t('scan.sale.3')"
                  :value="3"
                />
              </el-select>
              <el-select
                v-model="venueId"
                class="items-filter-venue"
                filterable
                @change="onSearch"
              >
                <el-option
                  :label="t('items.venueAll')"
                  :value="ALL"
                />
                <el-option
                  v-for="venue in venues"
                  :key="venue.id"
                  :label="venue.name"
                  :value="venue.id"
                />
              </el-select>
              <!-- 区间两端与分隔符同一组：换行时整组换行，不让「〜」被折到下一行孤悬 -->
              <span class="items-filter-range">
                <el-date-picker
                  v-model="buyDateFrom"
                  type="date"
                  :placeholder="t('items.buyDateFrom')"
                  value-format="YYYY-MM-DD"
                  class="items-filter-date"
                  @change="onSearch"
                />
                <span class="items-range-sep">〜</span>
                <el-date-picker
                  v-model="buyDateTo"
                  type="date"
                  :placeholder="t('items.buyDateTo')"
                  value-format="YYYY-MM-DD"
                  class="items-filter-date"
                  @change="onSearch"
                />
              </span>
              <el-select
                v-model="warnLevel"
                class="items-filter-slow"
                @change="onSearch"
              >
                <el-option
                  :label="t('items.slowAll')"
                  :value="ALL"
                />
                <el-option
                  :label="t('items.slowYellow')"
                  :value="1"
                />
                <el-option
                  :label="t('items.slowRed')"
                  :value="2"
                />
              </el-select>
              <el-button
                link
                type="primary"
                @click="onClearFilters"
              >
                {{ t('items.clear') }}
              </el-button>
            </div>
            <p class="items-hint">
              {{ t('items.searchHint') }}
            </p>
            <div class="items-toolbar-aside">
              <!--
                一括削除（D-126）：常驻而非选中才出现——入口本身要能被看见（原先
                列表页连删除都没有，用户是找不到才来问的）。未选中时置灰，旁边的
                件数徽标写明选了几件。
              -->
              <el-button
                v-if="isAdmin"
                class="items-batch-delete"
                :disabled="selectedRows.length === 0"
                @click="openBatchDelete"
              >
                {{ t('items.batch.delete') }}
              </el-button>
              <!--
                一括入出力（D-106）：批量导入/导出本该在「商品」这里被找到，但它们的
                交互是异步批处理（上传→判重→批次→报告），不适合塞进列表页，因此只放
                入口、落到 /excel 对应标签页；批次历史与报告仍留原页。
              -->
              <el-dropdown
                class="items-bulk"
                @command="onBulkCommand"
              >
                <el-button class="items-bulk-trigger">
                  {{ t('items.bulkEntry') }}
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="import">
                      {{ t('excel.import.title') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="export">
                      {{ t('excel.export.title') }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <p
            v-if="listError"
            class="kcgl-error-box"
            role="alert"
          >
            {{ listError }}
            <el-button
              link
              type="primary"
              @click="loadList"
            >
              {{ t('common.reload') }}
            </el-button>
          </p>
          <template v-else>
            <div class="items-count-row">
              <p class="items-count">
                {{ t('items.totalCount', { n: total }) }}
              </p>
              <p
                v-if="selectedRows.length > 0"
                class="items-count is-selected"
              >
                {{ t('items.batch.selected', { n: selectedRows.length }) }}
              </p>
            </div>
            <!-- 批量结果就地留在列表上：逐件语义意味着「成功 N 件、失败 M 件」，
                 失败的那几件还等着用户重试，这条信息不能跟着弹层一起消失 -->
            <div
              v-if="deleteResult"
              class="items-batch-result"
              :class="deleteResult.failures.length === 0 ? 'is-ok' : 'is-warn'"
              role="status"
            >
              <p class="items-batch-result-line">
                {{ batchMessage }}
              </p>
              <ul
                v-if="batchFailLines.length > 0"
                class="items-batch-result-list"
              >
                <li
                  v-for="line in batchFailLines"
                  :key="line"
                >
                  {{ line }}
                </li>
              </ul>
              <el-button
                link
                type="primary"
                @click="batchResult = null"
              >
                {{ t('common.close') }}
              </el-button>
            </div>
            <!-- 单行动作回执：动作后这一行可能因不满足当前筛选而整行消失（「在庫」
                 筛选里做売却），没有这条回执就等于点了按钮什么都没发生 -->
            <div
              v-if="statusNotice"
              class="items-batch-result is-ok"
              role="status"
            >
              <p class="items-batch-result-line">
                <span class="items-batch-result-code">{{ statusNotice.itemCode }}</span>
                {{ t(`scan.done.${statusNotice.action}`) }}
              </p>
              <el-button
                link
                type="primary"
                @click="statusNotice = null"
              >
                {{ t('common.close') }}
              </el-button>
            </div>
            <el-table
              v-loading="listLoading"
              :data="rows"
              row-key="id"
              class="items-table"
              @row-click="onRowClick"
              @selection-change="onSelectionChange"
            >
              <!-- 勾选列只在管理员出现：削除/復元都是 A-only，编辑者与浏览者拿到勾选框
                   也没有可做的事，白占列宽 -->
              <el-table-column
                v-if="isAdmin"
                type="selection"
                width="44"
                fixed="left"
              />
              <!-- 左钉「商品」：入札番号是全表唯一的身份，中间横滑时必须一直看得见 -->
              <el-table-column
                :label="t('items.column.item')"
                min-width="168"
                fixed="left"
              >
                <template #default="{ row }">
                  <div class="items-item">
                    <span class="items-thumb">
                      <img
                        v-if="(row as ItemSearchRow).thumbUrl"
                        :src="(row as ItemSearchRow).thumbUrl ?? undefined"
                        alt=""
                        loading="lazy"
                      >
                    </span>
                    <span class="items-code">{{ (row as ItemSearchRow).itemCode }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.itemName')"
                min-width="130"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as ItemSearchRow).itemName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.venue')"
                min-width="116"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as ItemSearchRow).venueName ?? '—' }}
                </template>
              </el-table-column>
              <!-- 以下三列只在宽屏（≥1680）出现，中窄屏收起以保证不出横向滚动条（D-120） -->
              <el-table-column
                :label="t('items.column.buyDate')"
                width="100"
              >
                <template #default="{ row }">
                  {{ formatJstDate((row as ItemSearchRow).buyDate) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.purchasePrice')"
                width="112"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as ItemSearchRow).purchasePrice) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.totalCost')"
                width="112"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as ItemSearchRow).totalCost) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.profit')"
                width="112"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as ItemSearchRow).profit) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.warehouse')"
                width="100"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as ItemSearchRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.shelfNo')"
                width="88"
              >
                <template #default="{ row }">
                  {{ (row as ItemSearchRow).shelfNo ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.status')"
                width="164"
                fixed="right"
              >
                <template #default="{ row }">
                  <div class="items-tags">
                    <span
                      class="items-tag"
                      :class="stockTagClass((row as ItemSearchRow).stockStatus)"
                    >{{ stockText((row as ItemSearchRow).stockStatus) }}</span>
                    <span
                      class="items-tag"
                      :class="saleTagClass((row as ItemSearchRow).saleStatus)"
                    >{{ saleText((row as ItemSearchRow).saleStatus) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.slowMove')"
                width="96"
              >
                <template #default="{ row }">
                  <span
                    v-if="(row as ItemSearchRow).slowMoveLevel === 2"
                    class="items-tag is-danger"
                  >{{ slowBadge((row as ItemSearchRow).slowMoveLevel) }}</span>
                  <span
                    v-else-if="(row as ItemSearchRow).slowMoveLevel === 1"
                    class="items-tag is-warning"
                  >{{ slowBadge((row as ItemSearchRow).slowMoveLevel) }}</span>
                </template>
              </el-table-column>
              <!-- 行内动作：合法状态动作（A/E）+ 管理员的状態修正・削除（A-only）。
                   右钉「操作」紧邻「状態」：横滑时状态与它对状态的可用动作必须同屏，
                   只钉其中一个等于把「现在是什么态」和「能改成什么态」拆开看。
                   @click.stop 在触发器上是必需的——不拦则冒泡到 row-click，点「操作」
                   会先跳详情页；菜单本身渲染在 body 的浮层里，不经过行，无需再拦。
                   行内削除与勾选批删共用同一套确认与提交，见 openRowDelete -->
              <el-table-column
                v-if="canAct"
                :label="t('items.column.action')"
                width="96"
                fixed="right"
              >
                <template #default="{ row }">
                  <!-- 无可做的事（如已达终态的件对编辑者）不摆一个点开是空的菜单：
                       空菜单比没有入口更让人怀疑是不是坏了。占位符放在 .items-actions
                       之外——onRowClick 见它就拦下，一个「—」不该比半个表格更难点进详情 -->
                  <span
                    v-if="rowCommands(row as ItemSearchRow).length > 0"
                    class="items-actions"
                  >
                    <el-dropdown
                      trigger="click"
                      @command="(command: string) => onRowCommand(row as ItemSearchRow, command)"
                    >
                      <el-button
                        link
                        type="primary"
                        @click.stop
                      >
                        {{ t('items.rowAction') }}
                      </el-button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item
                            v-for="entry in rowCommands(row as ItemSearchRow)"
                            :key="entry.command"
                            :command="entry.command"
                            :divided="entry.divided === true"
                          >
                            {{ t(entry.labelKey) }}
                          </el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('items.empty')"
                />
              </template>
            </el-table>
            <el-pagination
              v-if="total > PAGE_SIZE"
              layout="prev, pager, next"
              :total="total"
              :page-size="PAGE_SIZE"
              :current-page="page"
              class="items-pagination"
              @current-change="onPageChange"
            />
          </template>
        </el-tab-pane>

        <el-tab-pane
          v-if="isAdmin"
          :label="t('items.recycle.tabTitle')"
          name="recycle"
        >
          <p class="items-note">
            {{ t('items.recycle.note') }}
          </p>
          <p
            v-if="recycleError"
            class="kcgl-error-box"
            role="alert"
          >
            {{ recycleError }}
            <el-button
              link
              type="primary"
              @click="loadRecycle"
            >
              {{ t('common.reload') }}
            </el-button>
          </p>
          <template v-else>
            <div
              v-if="recycleRows.length > 0"
              class="items-recycle-actions"
            >
              <el-button
                :disabled="recycleSelectedRows.length === 0"
                :loading="batchBusy"
                @click="onBatchRestore"
              >
                {{ t('items.batch.restore') }}
              </el-button>
            </div>
            <div class="items-count-row">
              <p class="items-count">
                {{ t('items.totalCount', { n: recycleTotal }) }}
              </p>
              <p
                v-if="recycleSelectedRows.length > 0"
                class="items-count is-selected"
              >
                {{ t('items.batch.selected', { n: recycleSelectedRows.length }) }}
              </p>
            </div>
            <div
              v-if="restoreResult"
              class="items-batch-result"
              :class="restoreResult.failures.length === 0 ? 'is-ok' : 'is-warn'"
              role="status"
            >
              <p class="items-batch-result-line">
                {{ batchMessage }}
              </p>
              <ul
                v-if="batchFailLines.length > 0"
                class="items-batch-result-list"
              >
                <li
                  v-for="line in batchFailLines"
                  :key="line"
                >
                  {{ line }}
                </li>
              </ul>
              <el-button
                link
                type="primary"
                @click="batchResult = null"
              >
                {{ t('common.close') }}
              </el-button>
            </div>
            <el-table
              v-loading="recycleLoading"
              :data="recycleRows"
              row-key="id"
              class="items-table"
              @selection-change="onRecycleSelectionChange"
            >
              <el-table-column
                type="selection"
                width="44"
                fixed="left"
              />
              <el-table-column
                :label="t('items.recycle.column.item')"
                min-width="168"
                fixed="left"
              >
                <template #default="{ row }">
                  <div class="items-item">
                    <span class="items-thumb">
                      <img
                        v-if="(row as RecycleBinRow).thumbUrl"
                        :src="(row as RecycleBinRow).thumbUrl ?? undefined"
                        alt=""
                        loading="lazy"
                      >
                    </span>
                    <span class="items-code">{{ (row as RecycleBinRow).itemCode }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.itemName')"
                min-width="130"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as RecycleBinRow).itemName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.venue')"
                min-width="116"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as RecycleBinRow).venueName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.warehouse')"
                width="100"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as RecycleBinRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.status')"
                width="164"
              >
                <template #default="{ row }">
                  <div class="items-tags">
                    <span
                      class="items-tag"
                      :class="stockTagClass((row as RecycleBinRow).stockStatus)"
                    >{{ stockText((row as RecycleBinRow).stockStatus) }}</span>
                    <span
                      class="items-tag"
                      :class="saleTagClass((row as RecycleBinRow).saleStatus)"
                    >{{ saleText((row as RecycleBinRow).saleStatus) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.deletedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as RecycleBinRow).deletedAt) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.reason')"
                min-width="140"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as RecycleBinRow).reason ?? '—' }}
                </template>
              </el-table-column>
              <!-- 右钉「操作」；回收站的「状態」不钉——它与本列之间还隔着削除日時与
                   削除理由，钉在中间会被截成孤立的一条（详见文件头列宽注释） -->
              <el-table-column
                :label="t('admin.actions')"
                width="110"
                fixed="right"
              >
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    :loading="isRestoring((row as RecycleBinRow).id)"
                    @click="onRestore(row as RecycleBinRow)"
                  >
                    {{ t('items.recycle.restore') }}
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('items.recycle.empty')"
                />
              </template>
            </el-table>
            <el-pagination
              v-if="recycleTotal > PAGE_SIZE"
              layout="prev, pager, next"
              :total="recycleTotal"
              :page-size="PAGE_SIZE"
              :current-page="recyclePage"
              class="items-pagination"
              @current-change="onRecyclePageChange"
            />
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>

    <ItemBatchDeleteDialog
      v-model="batchDeleteVisible"
      :count="deleteTargets.length"
      :busy="batchBusy"
      @confirm="onBatchDeleteSubmit"
    />

    <ItemActionDialog
      :item="actionRow"
      :action="actionName"
      @close="onActionClosed"
      @done="onActionDone"
    />

    <ItemAdjustDialog
      v-if="adjustRow"
      v-model="adjustVisible"
      :item="adjustRow"
      @adjusted="onAdjusted"
    />
  </section>
</template>

<style scoped>
.items-view {
  display: grid;
  /* D-126：这一行是「中间列横滑」能不能成立的总开关。grid 隐式列是 auto，其下限是
     min-content——表内 13 列各有固定列宽时，min-content 就是列宽之和（1406），于是
     卡片被撑到 1472 宽、越过 .shell-main 溢出到页面，而 el-table 的容器宽等于它自己
     的表格宽（scrollWidth == clientWidth），**表格内部永远不出横滑条**，钉列是 sticky
     实现、没有内部滚动就粘不住，等于白钉。minmax(0, 1fr) 把列的下限压到 0，卡片才
     回到"视口给多宽就多宽"，横向溢出归 el-table 自己管内（探针实测：改前 1280 视口
     页面横滑 440px、表内横滑 0px；改后页面 0、表内 ~400px，左钉商品列与右钉操作列
     在滑到底后位移 0px）。 */
  grid-template-columns: minmax(0, 1fr);
  gap: var(--kcgl-space-4);
}

.items-body {
  padding: var(--kcgl-space-5) var(--kcgl-space-6);
}

.items-toolbar {
  display: grid;
  /* 左=搜索/筛选/提示各占一行，右=一括入出力入口（见 .items-bulk） */
  grid-template-columns: 1fr auto;
  align-items: start;
  gap: var(--kcgl-space-3);
  margin-bottom: var(--kcgl-space-3);
}

.items-search {
  display: flex;
  gap: var(--kcgl-space-2);
  max-width: 520px;
}

.items-filters {
  display: flex;
  align-items: center;
  gap: var(--kcgl-space-2);
  /* 行间距必须显式给：只有 column-gap 时换行后的控件会贴着上一行，看着像挤成一团 */
  row-gap: var(--kcgl-space-2);
  flex-wrap: wrap;
}

/* 日期区间整组换行（两端 + 分隔符是一个语义单位） */
.items-filter-range {
  display: inline-flex;
  align-items: center;
  gap: var(--kcgl-space-2);
}

.items-filter-wh {
  width: 150px;
}

.items-filter {
  width: 130px;
}

.items-filter-venue {
  width: 170px;
}

.items-filter-date {
  width: 150px;
}

.items-filter-slow {
  width: 150px;
}

.items-range-sep {
  color: var(--kcgl-color-text-faint);
}

.items-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

/* 右侧动作列：钉在第 2 列、跨筛选区两行（它们是"对结果做事"或"去别处"，都不是筛选
   条件——挤进筛选行就等于暗示它们会影响当前查询结果）。列内再竖排：一括削除在
   一括入出力上方，两者都是整块动作，拉等宽以免出现长短不齐的按钮叠罗汉。 */
.items-toolbar-aside {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: start;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--kcgl-space-2);
}

.items-note {
  margin: 0 0 var(--kcgl-space-3);
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

/* 件数是筛选结果的回执：做成标签而非一行灰字——灰字落在表格上方，与"加载失败"
   的留白长得一样（实测里用户正是把空结果读成"内容没加载出来"）。
   两个徽标（总件数 / 选中件数）同行排；下边距挪到行容器上，免得双份留白 */
.items-count-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--kcgl-space-2);
  margin-bottom: var(--kcgl-space-3);
}

.items-count {
  display: inline-flex;
  align-items: center;
  margin: 0;
  padding: 0 var(--kcgl-space-3);
  border: 1px solid var(--kcgl-color-border);
  border-radius: 999px;
  background: var(--kcgl-color-fill);
  color: var(--kcgl-color-text-sub);
  font-size: 0.85rem;
  line-height: 22px;
}

/* 选中件数换主色：它是"接下来那一击会打到谁"的凭据，不能与总件数同灰 */
.items-count.is-selected {
  border-color: var(--kcgl-color-info-border);
  background: var(--kcgl-color-info-bg);
  color: var(--kcgl-color-info-text);
}

/* 批量结果条：成功=绿、部分失败=琥珀。用底色而不是左侧色条——左侧粗边在本项目
   是"这里出错了"的通用语汇，成功回执也用会误读 */
.items-batch-result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--kcgl-space-2);
  margin-bottom: var(--kcgl-space-3);
  padding: var(--kcgl-space-2) var(--kcgl-space-3);
  border: 1px solid;
  border-radius: var(--kcgl-radius-m);
  font-size: 0.85rem;
}

.items-batch-result.is-ok {
  border-color: var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.items-batch-result.is-warn {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.items-batch-result-line {
  margin: 0;
}

/* 回执里的管理号：单行回执是「哪一件 + 做了什么」两句，不把管理号拎出来，
   多行回执并排时读者得自己从长句里找号 */
.items-batch-result-code {
  font-weight: 600;
  letter-spacing: 0.02em;
}

/* 失败清单整条占一行：管理番号与理由是逐件读的，挤在回执句后面会连成一片 */
.items-batch-result-list {
  flex-basis: 100%;
  margin: 0;
  padding-left: 1.2em;
}

/* 回收站的一括復元：与主列表的一括削除同位（表格上方右对齐前的动作区） */
.items-recycle-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--kcgl-space-2);
}

.items-table {
  width: 100%;
}

/* 金额列（align=right）数字加粗一档：tabular-nums 保证位对齐，字重让"数字成块"，
   长列表纵向扫读时价格/成本/利润能一眼连成列 */
.items-table :deep(.el-table__cell.is-right) .cell {
  font-weight: 600;
}

/* 行整体即详情入口（Zaico 式整行点击） */
.items-table :deep(tbody tr) {
  cursor: pointer;
}

.items-item {
  display: flex;
  align-items: center;
  gap: 10px;
}

.items-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border: 1px solid var(--kcgl-color-divider);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-fill);
  overflow: hidden;
}

.items-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.items-code {
  font-weight: 600;
  letter-spacing: 0.02em;
}

.items-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.items-tag {
  display: inline-block;
  padding: 0 var(--kcgl-space-2);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  line-height: 20px;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.items-tag.is-success {
  border-color: var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.items-tag.is-warning {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.items-tag.is-danger {
  border-color: var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
}

.items-tag.is-neutral {
  border-color: var(--kcgl-color-border);
  background: var(--kcgl-color-bg);
  color: var(--kcgl-color-text-faint);
}

.items-pagination {
  margin-top: var(--kcgl-space-4);
  justify-content: flex-end;
}
</style>
