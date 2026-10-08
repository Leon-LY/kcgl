<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppEmptyState from '@/components/AppEmptyState.vue'
import { formatJstDate, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { deleteItemsBatch, fetchVenues, searchItems } from '@/utils/api'
import type { ActionResult, ItemSearchParams, ItemSearchRow, Venue } from '@/utils/api'
import { availableActions } from '@/utils/inventoryActions'
import type { ScanAction } from '@/utils/inventoryActions'
import { ITEM_LIST_PAGE_SIZE, batchEntriesOf, errorText, itemListDisplay } from './itemListShared'
import {
  emptyFilters,
  filtersFromQuery,
  listRequest,
  pageFromQuery,
  queryOf,
  sameQuery,
  type ItemFilterState,
} from './itemFilters'
import ItemActionDialog from './ItemActionDialog.vue'
import ItemAdjustDialog from './ItemAdjustDialog.vue'
import ItemBatchDeleteDialog from './ItemBatchDeleteDialog.vue'
import ItemFilterBar from './ItemFilterBar.vue'

/**
 * 商品一覧（主列表）页签：筛选条、表格、行内状态动作与一括削除都在这里。
 *
 * 从 ItemsView 抽出来的（D-147）。它与回收站页签（ItemRecycleTab）之间只有**一条**
 * 真依赖：件在两边的去留是同一件事——本组件删了件，回收站那份数据就陈旧，用
 * emit('changed') 让父组件去重取；回收站復元件时由父组件回调本组件的 refresh()。
 * 除此之外两边各持一套 ref、各查各的接口。
 *
 * 三个弹层（行动作 / 状態修正 / 一括削除）归本组件：它们的状态与提交全是列表这一侧
 * 的。Element Plus 的 el-dialog 在默认 props 下 teleport 是**关闭**的
 * （dialog.vue：disabled = appendTo !== 'body' ? false : !appendToBody，而
 * appendToBody 默认 false），即就地渲染——所以它们现在落在页签内部。视觉无差别
 * （.el-overlay 是 position: fixed，不参与 .items-view 那个 grid 的布局）。
 *
 * 首载不自来：父组件的 onMounted 调 loadFromRoute()（D-144 起的同一条规矩——首载
 * 时机只由装配处决定，加载次数才不会随组件树顺序变化）。
 */
const emit = defineEmits<{ changed: [] }>()

/** 分页大小：与回收站页签同一档（那份在 itemListShared，两处只此一个来源）。 */
const PAGE_SIZE = ITEM_LIST_PAGE_SIZE

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

/** 一括入出力（D-106）：落到 Excel 页的对应标签页，批次历史与报告仍在原页。 */
function onBulkCommand(command: 'import' | 'export'): void {
  void router.push({ name: 'excel', query: { tab: command } })
}

// ------------------------------------------------------------- 商品一覧

/** 筛选条件：一个对象而不是八个 ref——URL 投影与检索参数两边本来就只按"整套"
 *  读写（见 itemFilters）。列表位相（rows/total/page/加载/错误）不是条件，各自留着。 */
const filters = ref<ItemFilterState>(emptyFilters())

const venues = ref<Venue[]>([])
const rows = ref<ItemSearchRow[]>([])
const total = ref(0)
const page = ref(1)
const listLoading = ref(true)
const listError = ref('')
let listSeq = 0

/** 检索参数 → URL：只投影**非默认**项，空态保持 /items 裸路径（下钻详情后返回即回到同一屏幕）。 */
function projectQuery(params: ItemSearchParams): void {
  const query = queryOf(params)
  if (sameQuery(route.query, query)) {
    return
  }
  // replace：筛选/换页不产生历史项——后退键回到进入列表前的页面，而非逐条回放筛选
  void router.replace({ name: 'items', query })
}

async function loadList(): Promise<void> {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = ''
  const params = listRequest(filters.value, page.value, PAGE_SIZE)
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

function goDetail(row: ItemSearchRow): void {
  void router.push({ name: 'item-detail', params: { id: row.id } })
}

const { warehouseOf, stockText, saleText, slowBadge, stockTagClass, saleTagClass } = itemListDisplay(t)

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
const batchBusy = ref(false)
const batchDeleteVisible = ref(false)

/**
 * 批量结果只存**数据**不存文案：全站切语言是运行时行为（D-029），此刻把
 * 「N 件を削除しました」渲染成字符串存下，切到中文后这条提示会留在日文。
 *
 * 这里只装削除。回收站的復元回执自持在 ItemRecycleTab 里——拆分前两者共用一个
 * batchResult 再各自按 kind 过滤，拆开后那份"共享再过滤"就没了。
 */
const batchResult = ref<{
  ok: number
  failures: { itemId: number; code: number }[]
} | null>(null)

const batchMessage = computed(() => {
  const result = batchResult.value
  if (result == null) {
    return ''
  }
  return result.failures.length === 0
    ? t('items.batch.deleteDone', { n: result.ok })
    : t('items.batch.deletePartial', { ok: result.ok, ng: result.failures.length })
})

const batchFailLines = computed(() =>
  (batchResult.value?.failures ?? []).map((failure) =>
    t('items.batch.failLine', {
      code: itemCodeOf(failure.itemId) ?? `#${failure.itemId}`,
      message: errorText(t, te, failure.code),
    }),
  ),
)

/** 失败行要报管理番号而非内部 id——管理番号才是现场认得出的东西。 */
function itemCodeOf(id: number): string | undefined {
  return rows.value.find((row) => row.id === id)?.itemCode
}

function onSelectionChange(selection: ItemSearchRow[]): void {
  selectedRows.value = selection
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
      batchEntriesOf(deleteTargets.value),
      reason === '' ? undefined : reason,
    )
    batchDeleteVisible.value = false
    batchResult.value = { ok: result.succeeded, failures: result.failures }
    // 删掉的件进了回收站那一侧，只刷列表会让"切过去看刚删的件"落空（E2E 实测踩到：
    // 回收站仍停在进页时的旧数据，新删的件根本不出现）。那一侧由父组件重取——本组件
    // 不认识回收站页签（同 D-141 拆它时的规矩：跨页签的重取只经由父组件）。
    await loadList()
    emit('changed')
  } catch (error) {
    batchDeleteVisible.value = false
    listError.value = toDisplayMessage(error, t)
  } finally {
    batchBusy.value = false
  }
}

// ------------------------------------------------------------- 对外接口

/**
 * 挂载首载：先消费 URL（从详情返回/深链直达时带回落札筛选与页码，C1），再取会场
 * 下拉与列表。由父组件的 onMounted 调用——本组件不自带 onMounted。
 */
function loadFromRoute(): Promise<void> {
  filters.value = filtersFromQuery(route.query)
  page.value = pageFromQuery(route.query)
  // 会场下拉加载失败不阻断列表（仅筛选项暂缺）
  void fetchVenues(false)
    .then((data) => {
      venues.value = data
    })
    .catch(() => undefined)
  return loadList()
}

/**
 * 页内重取，**保留当前页**：回收站復元了件时用它——件回到列表里该重取，但用户正翻
 * 在第几页不该被这次重取踢回第一页（拆分前就是 `void loadList()`，不能顺手改成
 * reload 的语义）。
 */
function refresh(): Promise<void> {
  return loadList()
}

/** 回首页并重取：数据侧的整页刷新（他人操作经 SSE 失效）走这个。 */
function reload(): Promise<void> {
  page.value = 1
  return loadList()
}

defineExpose({ loadFromRoute, refresh, reload })
</script>

<template>
  <div class="items-list">
    <ItemFilterBar
      v-model="filters"
      :venues="venues"
      @search="onSearch"
    >
      <template #aside>
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
      </template>
    </ItemFilterBar>

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
      <div class="kcgl-list-count-row">
        <p class="kcgl-list-count">
          {{ t('items.totalCount', { n: total }) }}
        </p>
        <p
          v-if="selectedRows.length > 0"
          class="kcgl-list-count is-selected"
        >
          {{ t('items.batch.selected', { n: selectedRows.length }) }}
        </p>
      </div>
      <!-- 批量结果就地留在列表上：逐件语义意味着「成功 N 件、失败 M 件」，
           失败的那几件还等着用户重试，这条信息不能跟着弹层一起消失 -->
      <div
        v-if="batchResult"
        class="kcgl-batch-result"
        :class="batchResult.failures.length === 0 ? 'is-ok' : 'is-warn'"
        role="status"
      >
        <p class="kcgl-batch-result-line">
          {{ batchMessage }}
        </p>
        <ul
          v-if="batchFailLines.length > 0"
          class="kcgl-batch-result-list"
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
        class="kcgl-batch-result is-ok"
        role="status"
      >
        <p class="kcgl-batch-result-line">
          <span class="kcgl-batch-result-code">{{ statusNotice.itemCode }}</span>
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
        class="kcgl-list-table"
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
            <div class="kcgl-list-item">
              <span class="kcgl-thumb">
                <img
                  v-if="(row as ItemSearchRow).thumbUrl"
                  :src="(row as ItemSearchRow).thumbUrl ?? undefined"
                  alt=""
                  loading="lazy"
                >
              </span>
              <span class="kcgl-list-code">{{ (row as ItemSearchRow).itemCode }}</span>
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
            <div class="kcgl-tags">
              <span
                class="kcgl-tag"
                :class="stockTagClass((row as ItemSearchRow).stockStatus)"
              >{{ stockText((row as ItemSearchRow).stockStatus) }}</span>
              <span
                class="kcgl-tag"
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
              class="kcgl-tag is-danger"
            >{{ slowBadge((row as ItemSearchRow).slowMoveLevel) }}</span>
            <span
              v-else-if="(row as ItemSearchRow).slowMoveLevel === 1"
              class="kcgl-tag is-warning"
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
        class="kcgl-list-pagination"
        @current-change="onPageChange"
      />
    </template>

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
  </div>
</template>

<style scoped>
/* 只有一个透明包裹层，纯粹是给这个片段一个单根（同 ItemRecycleTab） */
.items-list {
  display: block;
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
</style>
