<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import SetupChecklistCard from '@/components/SetupChecklistCard.vue'
import {
  fetchDashboardStats,
  fetchWarehouseStats,
  type DashboardStats,
  type WarehouseStats,
} from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { formatJstDateTime, formatYen } from '@/utils/format'

/**
 * 桌面大盘（M5-③，docs/01 4.2 dashboard）：首启引导 + 全队/两仓指标 + 雅虎待办
 * 一屏速览。指标为服务端聚合快照（GET /api/stats/**），SSE 粗粒度失效驱动整页
 * 重取（自己的操作走就地回显，D-070 回声抑制）。口径见 D-072：今月入庫按
 * warehouse_in_date 业务日、今月出庫=卖出/废弃/退会场（wh_from 侧）。
 */

const { t } = useI18n()
const router = useRouter()

const fleet = ref<DashboardStats['fleet'] | null>(null)
const yahoo = ref<DashboardStats['yahoo'] | null>(null)
const warehouses = ref<WarehouseStats[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

/** 两仓表列（与指标卡共用 dashboard.* 文案 key）。 */
const WAREHOUSE_COLUMNS = [
  'inStock',
  'inTransit',
  'shipped',
  'monthInbound',
  'monthOutbound',
  'slowWarn',
  'slowRed',
  'stockValue',
  'avgStockAgeDays',
] as const

function formatDays(days: number | null): string {
  return days === null ? '—' : t('dashboard.ageDays', { n: Math.round(days * 10) / 10 })
}

const fleetCards = computed(() => {
  const f = fleet.value
  if (f === null) {
    return []
  }
  return [
    { key: 'inStock', value: String(f.inStock) },
    { key: 'inTransit', value: String(f.inTransit) },
    { key: 'shipped', value: String(f.shipped) },
    { key: 'monthInbound', value: String(f.monthInbound) },
    { key: 'monthOutbound', value: String(f.monthOutbound) },
    { key: 'slowWarn', value: String(f.slowWarn) },
    { key: 'slowRed', value: String(f.slowRed) },
    { key: 'stockValue', value: formatYen(f.stockValue) },
    { key: 'avgStockAgeDays', value: formatDays(f.avgStockAgeDays) },
  ]
})

const yahooCards = computed(() => {
  const y = yahoo.value
  if (y === null) {
    return []
  }
  return [
    { key: 'soldNotShipped', value: String(y.soldNotShipped) },
    { key: 'canceledNotRelisted', value: String(y.canceledNotRelisted) },
    { key: 'withdrawNeeded', value: String(y.withdrawNeeded) },
  ]
})

const lastImportLine = computed(() => {
  const y = yahoo.value
  if (y === null || y.lastImportFilename === null || y.lastImportFinishedAt === null) {
    return t('dashboard.lastImportNone')
  }
  return t('dashboard.lastImport', {
    file: y.lastImportFilename,
    at: formatJstDateTime(y.lastImportFinishedAt),
  })
})

function warehouseCell(row: WarehouseStats, column: (typeof WAREHOUSE_COLUMNS)[number]): string {
  if (column === 'stockValue') {
    return formatYen(row.stockValue)
  }
  if (column === 'avgStockAgeDays') {
    return formatDays(row.avgStockAgeDays)
  }
  return String(row[column])
}

function warehouseName(row: WarehouseStats): string {
  return row.warehouse === null ? '—' : t(`common.warehouse.${row.warehouse}`)
}

async function load(): Promise<void> {
  if (loading.value) {
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const [dash, rows] = await Promise.all([fetchDashboardStats(), fetchWarehouseStats()])
    fleet.value = dash.fleet
    yahoo.value = dash.yahoo
    warehouses.value = rows
  } catch (error) {
    loadError.value = toDisplayMessage(error, t)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

useSyncInvalidation(['ITEM', 'INVENTORY', 'YAHOO_IMPORT', 'SETTING'], () => void load())
</script>

<template>
  <section class="dashboard-view">
    <h1 class="dashboard-title">
      {{ t('dashboard.title') }}
    </h1>

    <SetupChecklistCard />

    <div
      v-if="loadError"
      class="kcgl-card dashboard-card dashboard-error"
    >
      <p class="dashboard-error-message">
        {{ loadError }}
      </p>
      <button
        type="button"
        class="dashboard-retry"
        :disabled="loading"
        @click="load"
      >
        {{ t('common.reload') }}
      </button>
    </div>

    <template v-else>
      <div class="kcgl-card dashboard-card">
        <h2 class="dashboard-card-title">
          {{ t('dashboard.fleetTitle') }}
        </h2>
        <p
          v-if="loading && fleet === null"
          class="dashboard-loading"
        >
          {{ t('common.loading') }}
        </p>
        <dl
          v-else
          class="dashboard-grid"
        >
          <div
            v-for="card in fleetCards"
            :key="card.key"
            class="dashboard-stat"
            :class="`is-${card.key}`"
          >
            <dt>{{ t(`dashboard.${card.key}`) }}</dt>
            <dd>{{ card.value }}</dd>
          </div>
        </dl>
      </div>

      <div class="dashboard-columns">
        <div class="kcgl-card dashboard-card">
          <h2 class="dashboard-card-title">
            {{ t('dashboard.yahooTitle') }}
          </h2>
          <dl
            v-if="yahoo !== null"
            class="dashboard-grid"
          >
            <div
              v-for="card in yahooCards"
              :key="card.key"
              class="dashboard-stat"
              :class="`is-${card.key}`"
            >
              <dt>{{ t(`dashboard.${card.key}`) }}</dt>
              <dd>{{ card.value }}</dd>
            </div>
          </dl>
          <p class="dashboard-last-import">
            {{ lastImportLine }}
          </p>
          <div class="dashboard-actions">
            <button
              type="button"
              class="dashboard-link-btn"
              @click="router.push({ name: 'pending-shipments' })"
            >
              {{ t('dashboard.goPendingShipments') }}
            </button>
            <button
              type="button"
              class="dashboard-link-btn"
              @click="router.push({ name: 'yahoo' })"
            >
              {{ t('dashboard.goYahoo') }}
            </button>
          </div>
        </div>
      </div>

      <div class="kcgl-card dashboard-card">
        <h2 class="dashboard-card-title">
          {{ t('dashboard.warehouseTitle') }}
        </h2>
        <div
          v-if="warehouses.length > 0"
          class="dashboard-table-wrap"
        >
          <table class="dashboard-table">
            <thead>
              <tr>
                <th scope="col">
                  {{ t('dashboard.warehouse') }}
                </th>
                <th
                  v-for="column in WAREHOUSE_COLUMNS"
                  :key="column"
                  scope="col"
                >
                  {{ t(`dashboard.${column}`) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in warehouses"
                :key="row.warehouse"
              >
                <th scope="row">
                  {{ warehouseName(row) }}
                </th>
                <td
                  v-for="column in WAREHOUSE_COLUMNS"
                  :key="column"
                >
                  {{ warehouseCell(row, column) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.dashboard-view {
  display: grid;
  gap: 16px;
}

.dashboard-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.dashboard-card {
  display: grid;
  gap: 12px;
  padding: 20px;
}

.dashboard-card-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.dashboard-loading {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.dashboard-grid {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}

.dashboard-stat {
  display: grid;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: var(--kcgl-color-bg);
}

.dashboard-stat dt {
  font-size: 0.78rem;
  color: var(--kcgl-color-text-sub);
}

.dashboard-stat dd {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* 滞留计数用业务色着色（黄=注意、红=警报），0 时保持中性 */
.dashboard-stat.is-slowWarn dd {
  color: var(--kcgl-color-warning);
}

.dashboard-stat.is-slowRed dd {
  color: var(--kcgl-color-danger);
}

.dashboard-columns {
  display: grid;
  grid-template-columns: minmax(0, 520px);
}

.dashboard-last-import {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
  word-break: break-all;
}

.dashboard-actions {
  display: flex;
  gap: 8px;
}

.dashboard-link-btn {
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
}

.dashboard-link-btn:hover {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

.dashboard-error {
  justify-items: start;
}

.dashboard-error-message {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

.dashboard-retry {
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
}

.dashboard-table-wrap {
  overflow-x: auto;
}

.dashboard-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
  font-variant-numeric: tabular-nums;
}

.dashboard-table th,
.dashboard-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--kcgl-color-border);
  text-align: right;
  white-space: nowrap;
}

.dashboard-table thead th {
  color: var(--kcgl-color-text-sub);
  font-weight: 500;
  font-size: 0.8rem;
}

.dashboard-table tbody th {
  text-align: left;
  font-weight: 600;
}
</style>
