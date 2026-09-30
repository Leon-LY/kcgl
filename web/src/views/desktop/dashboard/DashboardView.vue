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

/** 尚无成功导入批次：把这一行从"灰字一行"升级成带下一步引导的说明块
    （用户的实测反馈：三个 0 + 一行灰字被读成"内容加载不出来"）。 */
const noImportYet = computed(
  () => yahoo.value === null || yahoo.value.lastImportFilename === null,
)

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
          <!-- 无导入批次时这一块整体转为说明块：说清"为什么是 0"与"下一步点哪"
               （文案与计数断言口径不变：.dashboard-last-import 仍是那行原句） -->
          <div
            class="dashboard-note"
            :class="{ 'is-empty': noImportYet }"
          >
            <p class="dashboard-last-import">
              {{ lastImportLine }}
            </p>
            <p
              v-if="noImportYet"
              class="dashboard-note-next"
            >
              {{ t('dashboard.importHint') }}
            </p>
          </div>
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
                :key="row.warehouse ?? 'none'"
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
  gap: var(--kcgl-space-5);
}

.dashboard-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.dashboard-card {
  display: grid;
  gap: var(--kcgl-space-4);
  padding: var(--kcgl-space-5);
}

.dashboard-card-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text);
}

.dashboard-loading {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

/* 指标面板＝**键线网格**（1px 细线分格）：v1 是"外卡片里再套 9 个带边框灰底小卡"
   ——卡片套卡片，且九个等大框并列无主次，正是演示品观感。键线网格把九个数字
   收成一块可扫读的面板：标签一行、数字一行，线只是分隔不是装饰。 */
.dashboard-grid {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  background: var(--kcgl-color-divider);
  border: 1px solid var(--kcgl-color-divider);
  border-radius: var(--kcgl-radius-m);
  overflow: hidden;
}

.dashboard-stat {
  display: grid;
  gap: 2px;
  padding: var(--kcgl-space-4);
  background: var(--kcgl-color-card);
}

.dashboard-stat dt {
  font-size: 0.78rem;
  color: var(--kcgl-color-text-sub);
}

.dashboard-stat dd {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1.3;
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
  grid-template-columns: minmax(0, 1fr);
}

/* 无导入批次：整块转为信息说明（浅底+描边），把"三个 0"从疑似故障改读成待办 */
.dashboard-note {
  display: grid;
  gap: var(--kcgl-space-1);
}

.dashboard-note.is-empty {
  padding: var(--kcgl-space-3) var(--kcgl-space-4);
  border: 1px solid var(--kcgl-color-info-border);
  background: var(--kcgl-color-info-bg);
  border-radius: var(--kcgl-radius-s);
}

.dashboard-last-import {
  margin: 0;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
  word-break: break-all;
}

.dashboard-note.is-empty .dashboard-last-import,
.dashboard-note-next {
  color: var(--kcgl-color-info-text);
}

.dashboard-note-next {
  margin: 0;
  font-size: 0.85rem;
}

.dashboard-actions {
  display: flex;
  gap: var(--kcgl-space-2);
}

.dashboard-link-btn {
  height: 36px;
  padding: 0 var(--kcgl-space-4);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    background-color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.dashboard-link-btn:hover {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
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
  padding: 0 var(--kcgl-space-4);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
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
  padding: var(--kcgl-space-2) var(--kcgl-space-3);
  border-bottom: 1px solid var(--kcgl-color-divider);
  text-align: right;
  white-space: nowrap;
}

.dashboard-table thead th {
  background: var(--kcgl-color-fill);
  color: var(--kcgl-color-text-sub);
  font-weight: 600;
  font-size: 0.8rem;
}

.dashboard-table tbody tr:hover {
  background: var(--kcgl-color-primary-bg);
}

.dashboard-table tbody th {
  text-align: left;
  font-weight: 600;
}

/* 窄屏：三列键线网格退到两列/一列（3×3 是九枚指标的整除布局，不留下空格子） */
@media (max-width: 900px) {
  .dashboard-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .dashboard-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
