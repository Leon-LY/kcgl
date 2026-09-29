<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import { formatJstDate, formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { fetchRecycleBin, fetchVenues, restoreItem, searchItems } from '@/utils/api'
import type { ItemSearchRow, RecycleBinRow, Venue } from '@/utils/api'

/**
 * 商品一覧（M5-①，D-061）：kw 搜索（管理号＞日期＞模糊 LIKE 优先级链）+
 * 仓库/库存/销售/会场/落札日区间/滞留六路筛选，行点击进详情；滞留黄红徽标
 * 与筛选共用服务端边界（D-065）。第二标签=削除済み商品（仅管理员，软删恢复）。
 * 作废/软删件已被服务端排除；他人操作经 SSE 失效自动整页重取。
 */

const PAGE_SIZE = 20

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const isAdmin = computed(() => auth.me != null && auth.me.role === 1)

const activeTab = ref('list')

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

async function loadList(): Promise<void> {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = ''
  try {
    const data = await searchItems({
      kw: kw.value.trim() === '' ? undefined : kw.value.trim(),
      warehouse: warehouse.value === ALL ? undefined : warehouse.value,
      stockStatus: stockStatus.value === ALL ? undefined : stockStatus.value,
      saleStatus: saleStatus.value === ALL ? undefined : saleStatus.value,
      venueId: venueId.value === ALL ? undefined : venueId.value,
      buyDateFrom: buyDateFrom.value ?? undefined,
      buyDateTo: buyDateTo.value ?? undefined,
      warnLevel: warnLevel.value === ALL ? undefined : warnLevel.value,
      page: page.value,
      size: PAGE_SIZE,
    })
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
    await restoreItem(row.id, crypto.randomUUID())
    await loadRecycle()
  } catch (error) {
    recycleError.value = toDisplayMessage(error, t)
  } finally {
    restoringIds.value = restoringIds.value.filter((x) => x !== row.id)
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
    <div class="admin-header">
      <div>
        <h1 class="admin-title">
          {{ t('items.title') }}
        </h1>
      </div>
    </div>

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
            <p class="items-count">
              {{ t('items.totalCount', { n: total }) }}
            </p>
            <el-table
              v-loading="listLoading"
              :data="rows"
              row-key="id"
              class="items-table"
              @row-click="(row) => goDetail(row as ItemSearchRow)"
            >
              <el-table-column
                :label="t('items.column.item')"
                min-width="210"
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
                min-width="140"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as ItemSearchRow).itemName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.venue')"
                min-width="120"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as ItemSearchRow).venueName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.buyDate')"
                width="110"
              >
                <template #default="{ row }">
                  {{ formatJstDate((row as ItemSearchRow).buyDate) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.purchasePrice')"
                width="110"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as ItemSearchRow).purchasePrice) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.totalCost')"
                width="110"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as ItemSearchRow).totalCost) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.profit')"
                width="100"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as ItemSearchRow).profit) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.warehouse')"
                width="120"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as ItemSearchRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.shelfNo')"
                width="100"
              >
                <template #default="{ row }">
                  {{ (row as ItemSearchRow).shelfNo ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.column.status')"
                width="170"
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
                width="100"
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
              <template #empty>
                {{ t('items.empty') }}
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
            <p class="items-count">
              {{ t('items.totalCount', { n: recycleTotal }) }}
            </p>
            <el-table
              v-loading="recycleLoading"
              :data="recycleRows"
              row-key="id"
              class="items-table"
            >
              <el-table-column
                :label="t('items.recycle.column.item')"
                min-width="210"
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
                min-width="140"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as RecycleBinRow).itemName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.venue')"
                min-width="120"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as RecycleBinRow).venueName ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.warehouse')"
                width="120"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as RecycleBinRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('items.recycle.column.status')"
                width="170"
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
              <el-table-column
                :label="t('admin.actions')"
                width="110"
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
                {{ t('items.recycle.empty') }}
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
  </section>
</template>

<style scoped>
.items-view {
  display: grid;
  gap: 16px;
}

.items-body {
  padding: 20px 24px;
}

.items-toolbar {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}

.items-search {
  display: flex;
  gap: 8px;
  max-width: 520px;
}

.items-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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

.items-note {
  margin: 0 0 10px;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.items-count {
  margin: 0 0 10px;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.items-table {
  width: 100%;
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
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
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
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
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
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
