<script setup lang="ts">
import { markRaw, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { formatJstDateTime } from '@/utils/format'
import { renderMessageJson, toDisplayMessage } from '@/utils/errors'
import { fetchLedgers } from '@/utils/api'
import type { LedgerRow } from '@/utils/api'

/**
 * 台帳ブラウズ（/ledgers，M5-④，仅管理员）：全库流水治理翻查——筛选
 * （类型/管理号前缀/操作人/仓库双侧命中/时间窗）+ id 倒序分页。只读页：
 * 流水只增不改不删（验收 9），这里没有也不会有写按钮。行点击进商品详情。
 */

const PAGE_SIZE = 20
/** TxnType 枚举全集（与后端一致）：筛选项与 items.ledger.type 文案共用。 */
const TXN_TYPES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14] as const

const { t } = useI18n()
const router = useRouter()

/** 「すべて」选项值：对象哨兵（el-option 不收 null；见 ItemsView 同款注释）。 */
const ALL = markRaw({ all: true } as const)

const txnType = ref<typeof ALL | number>(ALL)
const itemCode = ref('')
const operatorName = ref('')
const warehouse = ref<typeof ALL | number>(ALL)
const dateFrom = ref<string | null>(null)
const dateTo = ref<string | null>(null)

const rows = ref<LedgerRow[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)
const error = ref('')
let loadSeq = 0

async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const data = await fetchLedgers({
      txnType: typeof txnType.value === 'number' ? txnType.value : undefined,
      itemCode: itemCode.value.trim() === '' ? undefined : itemCode.value.trim(),
      operatorName: operatorName.value.trim() === '' ? undefined : operatorName.value.trim(),
      warehouse: typeof warehouse.value === 'number' ? warehouse.value : undefined,
      dateFrom: dateFrom.value ?? undefined,
      dateTo: dateTo.value ?? undefined,
      page: page.value,
      size: PAGE_SIZE,
    })
    if (seq !== loadSeq) {
      return
    }
    rows.value = data.rows
    total.value = data.total
  } catch (e) {
    if (seq !== loadSeq) {
      return
    }
    error.value = toDisplayMessage(e, t)
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

function onSearch(): void {
  page.value = 1
  void load()
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

function onClearFilters(): void {
  txnType.value = ALL
  itemCode.value = ''
  operatorName.value = ''
  warehouse.value = ALL
  dateFrom.value = null
  dateTo.value = null
  onSearch()
}

function goDetail(row: LedgerRow): void {
  void router.push({ name: 'item-detail', params: { id: row.itemId } })
}

onMounted(() => {
  void load()
})

// el-table 空哑行探测兜底（TableColumnRenderer，D-056）：动态 i18n key 空值不出 missing-key
function txnText(type: number | null | undefined): string {
  return type == null ? '' : t(`items.ledger.type.${type}`)
}

function warehouseText(value: number | null | undefined): string {
  return value == null ? '—' : t(`common.warehouse.${value}`)
}

/**
 * 理由列（V7，D-130）：系统生成的理由按当前语言渲染，人工理由（无键）原样显示，
 * 历史行回退日文原文——三态统一在 renderMessageJson 里，这里只兜空。
 */
function reasonText(row: LedgerRow): string {
  return renderMessageJson(row.reasonCode, row.reasonParams, t, row.reason) || '—'
}
</script>

<template>
  <section class="ledgers-view">
    <AppPageHeader :title="t('ledgers.title')" />

    <div class="kcgl-card ledgers-body">
      <div class="ledgers-toolbar">
        <div class="ledgers-filters">
          <el-select
            v-model="txnType"
            class="ledgers-filter-type"
            @change="onSearch"
          >
            <el-option
              :label="t('ledgers.typeAll')"
              :value="ALL"
            />
            <el-option
              v-for="type in TXN_TYPES"
              :key="type"
              :label="t(`items.ledger.type.${type}`)"
              :value="type"
            />
          </el-select>
          <el-select
            v-model="warehouse"
            class="ledgers-filter-wh"
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
          <el-input
            v-model="itemCode"
            :placeholder="t('ledgers.itemCodePlaceholder')"
            clearable
            class="ledgers-filter-code"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-input
            v-model="operatorName"
            :placeholder="t('ledgers.operatorPlaceholder')"
            clearable
            class="ledgers-filter-operator"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-date-picker
            v-model="dateFrom"
            type="date"
            :placeholder="t('ledgers.dateFrom')"
            value-format="YYYY-MM-DD"
            class="ledgers-filter-date"
            @change="onSearch"
          />
          <span class="ledgers-range-sep">〜</span>
          <el-date-picker
            v-model="dateTo"
            type="date"
            :placeholder="t('ledgers.dateTo')"
            value-format="YYYY-MM-DD"
            class="ledgers-filter-date"
            @change="onSearch"
          />
          <el-button
            link
            type="primary"
            @click="onClearFilters"
          >
            {{ t('items.clear') }}
          </el-button>
        </div>
        <p class="ledgers-hint">
          {{ t('ledgers.hint') }}
        </p>
      </div>

      <p
        v-if="error"
        class="kcgl-error-box"
        role="alert"
      >
        {{ error }}
        <el-button
          link
          type="primary"
          @click="load"
        >
          {{ t('common.reload') }}
        </el-button>
      </p>
      <template v-else>
        <p class="ledgers-count">
          {{ t('ledgers.totalCount', { n: total }) }}
        </p>
        <el-table
          v-loading="loading"
          :data="rows"
          row-key="id"
          class="ledgers-table"
          @row-click="(row: LedgerRow) => goDetail(row)"
        >
          <el-table-column
            :label="t('ledgers.column.at')"
            width="150"
          >
            <template #default="{ row }">
              {{ formatJstDateTime((row as LedgerRow).createdAt) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.itemCode')"
            min-width="130"
          >
            <template #default="{ row }">
              <span class="ledgers-code">{{ (row as LedgerRow).itemCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.type')"
            width="110"
          >
            <template #default="{ row }">
              {{ txnText((row as LedgerRow).txnType) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.warehouse')"
            width="150"
          >
            <template #default="{ row }">
              <span class="ledgers-warehouse">
                {{ warehouseText((row as LedgerRow).whFrom) }}
                <template v-if="(row as LedgerRow).whFrom != null || (row as LedgerRow).whTo != null">
                  →
                </template>
                {{ warehouseText((row as LedgerRow).whTo) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.qty')"
            width="80"
            align="right"
          >
            <template #default="{ row }">
              {{ (row as LedgerRow).qtyChange > 0 ? '+' : '' }}{{ (row as LedgerRow).qtyChange }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.stock')"
            width="110"
          >
            <template #default="{ row }">
              <span class="ledgers-transition">
                {{ (row as LedgerRow).stockFrom ?? '—' }} → {{ (row as LedgerRow).stockTo ?? '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.sale')"
            width="110"
          >
            <template #default="{ row }">
              <span class="ledgers-transition">
                {{ (row as LedgerRow).saleFrom ?? '—' }} → {{ (row as LedgerRow).saleTo ?? '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.reason')"
            min-width="140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ reasonText(row as LedgerRow) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.operator')"
            width="110"
          >
            <template #default="{ row }">
              {{ (row as LedgerRow).operatorName ?? '—' }}
            </template>
          </el-table-column>
          <template #empty>
            {{ t('ledgers.empty') }}
          </template>
        </el-table>
        <el-pagination
          v-if="total > PAGE_SIZE"
          layout="prev, pager, next"
          :total="total"
          :page-size="PAGE_SIZE"
          :current-page="page"
          class="ledgers-pagination"
          @current-change="onPageChange"
        />
      </template>
    </div>
  </section>
</template>

<style scoped>
.ledgers-view {
  display: grid;
  gap: 16px;
}

.ledgers-body {
  display: grid;
  gap: 12px;
  padding: 20px;
}

.ledgers-toolbar {
  display: grid;
  gap: 8px;
}

.ledgers-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ledgers-filter-type {
  width: 130px;
}

.ledgers-filter-wh {
  width: 120px;
}

.ledgers-filter-code {
  width: 150px;
}

.ledgers-filter-operator {
  width: 130px;
}

.ledgers-filter-date {
  width: 140px;
}

.ledgers-range-sep {
  color: var(--kcgl-color-text-sub);
}

.ledgers-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.ledgers-count {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.ledgers-table {
  cursor: pointer;
}

.ledgers-code {
  font-weight: 600;
  white-space: nowrap;
}

.ledgers-transition {
  white-space: nowrap;
  color: var(--kcgl-color-text-sub);
}

.ledgers-pagination {
  justify-self: end;
}
</style>
