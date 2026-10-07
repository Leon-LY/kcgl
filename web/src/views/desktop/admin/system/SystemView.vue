<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { formatBytes, formatJstDateTime } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import {
  downloadDiagnosticsExport,
  fetchAlerts,
  fetchSystemStatus,
  markAlertRead,
  runSelfCheck,
} from '@/utils/api'
import type { SelfCheckReport, SysAlert, SystemStatus } from '@/utils/api'

/**
 * システム状況（/admin/system，M5-④，仅管理员，docs/01 9.3）：排障速览白名单
 * 字段（版本/uptime/堆/池/磁盘/水位计数/号引擎/批次近况/近 7 日前端错误）+
 * 统一告警（已读按钮）+ 手动帳実自検 + 诊断包一键导出（甲方动作=点按钮发文件）。
 * 阈值语义不在此页判定（自检报告呈现各节 ok）；页加载拉取，刷新承载实时性。
 */

const { t } = useI18n()

/** 备份陈旧阈值 25h（与 kcgl-doctor 检查 4 同口径：lastSuccessAt >25h 即警示）。 */
const BACKUP_STALE_SECONDS = 25 * 3600

// ------------------------------------------------------------- システム状況

const status = ref<SystemStatus | null>(null)
const loading = ref(true)
const loadError = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    status.value = await fetchSystemStatus()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function uptimeText(seconds: number | undefined): string {
  if (seconds === undefined) {
    return '—'
  }
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) {
    return t('system.uptimeDays', { d: days, h: hours })
  }
  if (hours > 0) {
    return t('system.uptimeHours', { h: hours, m: minutes })
  }
  return t('system.uptimeMinutes', { m: minutes })
}

/** 堆/池/磁盘用速览卡；数值键值对由模板就地拼装。backup 超 25h 标红（D-3 故障首查项）。 */
const overviewCards = computed(() => {
  const s = status.value
  if (s === null) {
    return []
  }
  return [
    { key: 'version', label: t('system.appVersion'), value: s.appVersion },
    { key: 'flyway', label: t('system.flywayVersion'), value: s.flywayVersion ?? '—' },
    { key: 'startedAt', label: t('system.startedAt'), value: formatJstDateTime(s.startedAt) },
    { key: 'uptime', label: t('system.uptime'), value: uptimeText(s.uptimeSeconds) },
    { key: 'backup', label: t('system.backupLastSuccess'), value: s.backup === null
      ? t('system.unavailable')
      : formatJstDateTime(s.backup.lastSuccessAtJst) },
    { key: 'sse', label: t('system.sseConnections'), value: String(s.sseConnections) },
    { key: 'openAlerts', label: t('system.openAlerts'), value: String(s.openAlerts) },
  ]
})

/** backup 卡是否陈旧（>25h）——dd 标红，runbook D-3 引导。null=不可知不标红。 */
function isBackupStale(): boolean {
  return (status.value?.backup?.staleSeconds ?? 0) > BACKUP_STALE_SECONDS
}

const resourceCards = computed(() => {
  const s = status.value
  if (s === null) {
    return []
  }
  return [
    {
      key: 'heap',
      label: t('system.heap'),
      value: `${formatBytes(s.heap.usedBytes)} / ${formatBytes(s.heap.maxBytes)}`,
    },
    {
      key: 'pool',
      label: t('system.pool'),
      value: s.pool.total < 0
        ? t('system.unavailable')
        : t('system.poolValue', {
            active: s.pool.active,
            idle: s.pool.idle,
            total: s.pool.total,
            waiting: s.pool.waiting,
          }),
    },
    {
      key: 'disk',
      label: t('system.disk'),
      value: s.disk.totalBytes === 0
        ? t('system.unavailable')
        : t('system.diskValue', {
            used: formatBytes(s.disk.totalBytes - s.disk.usableBytes),
            total: formatBytes(s.disk.totalBytes),
            percent: s.disk.usedPercent,
          }),
    },
  ]
})

const volumeCards = computed(() => {
  const s = status.value
  if (s === null) {
    return []
  }
  return [
    { key: 'items', label: t('system.volumeItems'), value: new Intl.NumberFormat('ja-JP').format(s.volumes.items) },
    { key: 'ledgers', label: t('system.volumeLedgers'), value: new Intl.NumberFormat('ja-JP').format(s.volumes.ledgers) },
    { key: 'operationLogs', label: t('system.volumeOperationLogs'), value: new Intl.NumberFormat('ja-JP').format(s.volumes.operationLogs) },
    { key: 'clientErrors', label: t('system.volumeClientErrors'), value: new Intl.NumberFormat('ja-JP').format(s.volumes.clientErrors) },
    { key: 'codeIssued', label: t('system.codeIssued'), value: new Intl.NumberFormat('ja-JP').format(s.codeEngine.issued) },
    { key: 'codeSkipped', label: t('system.codeSkipped'), value: new Intl.NumberFormat('ja-JP').format(s.codeEngine.skipped) },
  ]
})

/** 近 7 日前端错误条形（max 归一化宽度；0 也有格——缺日补零口径）。 */
const errorBars = computed(() => {
  const s = status.value
  if (s === null) {
    return []
  }
  const max = Math.max(1, ...s.clientErrors7d.map((d) => d.count))
  return s.clientErrors7d.map((d) => ({
    date: d.date.slice(5).replace('-', '/'),
    count: d.count,
    width: Math.round((d.count / max) * 100),
  }))
})

function batchStatusText(statusValue: number | null | undefined): string {
  return statusValue == null ? '' : t(`system.batchStatusItem.${statusValue}`)
}

// ------------------------------------------------------------- 統一告警

const alerts = ref<SysAlert[]>([])
const alertTotal = ref(0)
const alertsLoading = ref(false)
const alertsError = ref('')
const markingIds = ref<number[]>([])

async function loadAlerts(): Promise<void> {
  alertsLoading.value = true
  alertsError.value = ''
  try {
    const data = await fetchAlerts()
    alerts.value = data.list
    alertTotal.value = data.total
  } catch (e) {
    alertsError.value = toDisplayMessage(e, t)
  } finally {
    alertsLoading.value = false
  }
}

function isMarking(id: number): boolean {
  return markingIds.value.includes(id)
}

async function onMarkRead(alert: SysAlert): Promise<void> {
  if (isMarking(alert.id) || alert.status !== 0) {
    return
  }
  markingIds.value = [...markingIds.value, alert.id]
  alertsError.value = ''
  try {
    await markAlertRead(alert.id)
    await Promise.all([loadAlerts(), load()])
  } catch (e) {
    alertsError.value = toDisplayMessage(e, t)
  } finally {
    markingIds.value = markingIds.value.filter((x) => x !== alert.id)
  }
}

function alertLevelClass(level: number | null | undefined): string {
  return level === 3 ? 'is-danger' : level === 2 ? 'is-warning' : 'is-neutral'
}

function alertLevelText(level: number | null | undefined): string {
  return level == null ? '' : t(`system.alertLevelItem.${level}`)
}

// ------------------------------------------------------------- 帳実自検

const checking = ref(false)
const checkError = ref('')
const report = ref<SelfCheckReport | null>(null)

async function onSelfCheck(): Promise<void> {
  if (checking.value) {
    return
  }
  checking.value = true
  checkError.value = ''
  try {
    report.value = await runSelfCheck()
    // 自检发现即落 sys_alert：告警区与开启计数同步刷新
    await Promise.all([loadAlerts(), load()])
  } catch (e) {
    checkError.value = toDisplayMessage(e, t)
  } finally {
    checking.value = false
  }
}

/** 报告各节摘要行（ok 徽标 + 一句话）。 */
const reportRows = computed(() => {
  const r = report.value
  if (r === null) {
    return []
  }
  return [
    {
      key: 'ledger',
      label: t('system.check.ledger'),
      ok: r.ledger.ok,
      detail: r.ledger.ok
        ? t('system.check.ledgerOk')
        : t('system.check.ledgerNg', { n: r.ledger.driftCount }),
    },
    {
      key: 'counters',
      label: t('system.check.counters'),
      ok: r.counters.ok,
      detail: r.counters.ok
        ? t('system.check.countersOk')
        : t('system.check.countersNg', { n: r.counters.mismatches.length }),
    },
    {
      key: 'volumes',
      label: t('system.check.volumes'),
      ok: r.volumes.ok,
      detail: t('system.check.volumesValue', {
        items: r.volumes.itemCount,
        ledgers: r.volumes.ledgerCount,
        logs: r.volumes.logCount,
      }),
    },
    {
      key: 'disk',
      label: t('system.check.disk'),
      ok: r.disk.ok,
      detail: r.disk.totalBytes === 0
        ? t('system.unavailable')
        : t('system.diskValue', {
            used: formatBytes(r.disk.totalBytes - r.disk.usableBytes),
            total: formatBytes(r.disk.totalBytes),
            percent: r.disk.usedPercent,
          }),
    },
    {
      key: 'imageAudit',
      label: t('system.check.imageAudit'),
      ok: r.imageAudit.ok,
      detail: t('system.check.imageValue', {
        missing: r.imageAudit.missingCount,
        orphan: r.imageAudit.orphanCount,
      }),
    },
  ]
})

// ------------------------------------------------------------- 診断情報エクスポート

const exporting = ref(false)
const exportError = ref('')

/** 浏览器保存下载 blob（文件名取自 Content-Disposition）。 */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

async function onExport(): Promise<void> {
  if (exporting.value) {
    return
  }
  exporting.value = true
  exportError.value = ''
  try {
    const { blob, filename } = await downloadDiagnosticsExport()
    saveBlob(blob, filename)
  } catch (e) {
    exportError.value = toDisplayMessage(e, t)
  } finally {
    exporting.value = false
  }
}

// ------------------------------------------------------------- 装配

onMounted(() => {
  void load()
  void loadAlerts()
})
</script>

<template>
  <section class="system-view">
    <AppPageHeader :title="t('system.title')">
      <template #actions>
        <el-button
          type="primary"
          :disabled="checking"
          @click="onSelfCheck"
        >
          {{ checking ? t('system.check.running') : t('system.check.run') }}
        </el-button>
        <el-button
          :disabled="exporting"
          @click="onExport"
        >
          {{ exporting ? t('system.export.running') : t('system.export.run') }}
        </el-button>
      </template>
    </AppPageHeader>

    <div
      v-if="loadError"
      class="kcgl-card system-card"
    >
      <p class="system-error">
        {{ t('system.loadFailed') }}
      </p>
      <el-button
        :disabled="loading"
        @click="load"
      >
        {{ t('common.reload') }}
      </el-button>
    </div>

    <template v-else>
      <div
        v-loading="loading"
        class="kcgl-card system-card"
      >
        <h2 class="system-card-title">
          {{ t('system.overviewTitle') }}
        </h2>
        <dl class="system-stats">
          <div
            v-for="card in overviewCards"
            :key="card.key"
            class="system-stat"
            :class="`is-${card.key}`"
          >
            <dt>{{ card.label }}</dt>
            <dd :class="{ 'is-danger': card.key === 'backup' && isBackupStale() }">
              {{ card.value }}
            </dd>
          </div>
        </dl>
      </div>

      <div
        v-loading="loading"
        class="kcgl-card system-card"
      >
        <h2 class="system-card-title">
          {{ t('system.resourceTitle') }}
        </h2>
        <dl class="system-stats">
          <div
            v-for="card in resourceCards"
            :key="card.key"
            class="system-stat"
            :class="`is-${card.key}`"
          >
            <dt>{{ card.label }}</dt>
            <dd>{{ card.value }}</dd>
          </div>
        </dl>
      </div>

      <div
        v-loading="loading"
        class="kcgl-card system-card"
      >
        <h2 class="system-card-title">
          {{ t('system.volumeTitle') }}
        </h2>
        <dl class="system-stats">
          <div
            v-for="card in volumeCards"
            :key="card.key"
            class="system-stat"
            :class="`is-${card.key}`"
          >
            <dt>{{ card.label }}</dt>
            <dd>{{ card.value }}</dd>
          </div>
        </dl>
      </div>

      <div
        v-loading="loading"
        class="kcgl-card system-card"
      >
        <h2 class="system-card-title">
          {{ t('system.clientErrorsTitle') }}
        </h2>
        <div class="system-error-bars">
          <div
            v-for="bar in errorBars"
            :key="bar.date"
            class="system-error-bar"
            :title="`${bar.date}: ${bar.count}`"
          >
            <span
              class="system-error-bar-fill"
              :style="{ width: `${bar.width}%` }"
            />
            <span class="system-error-bar-count">{{ bar.count }}</span>
            <span class="system-error-bar-date">{{ bar.date }}</span>
          </div>
        </div>
      </div>

      <div
        v-if="status !== null && status.excelBatches.recent.length > 0"
        v-loading="loading"
        class="kcgl-card system-card"
      >
        <h2 class="system-card-title">
          {{ t('system.batchTitle', {
            total: status.excelBatches.total,
            done: status.excelBatches.done,
            failed: status.excelBatches.failed,
          }) }}
        </h2>
        <el-table
          :data="status.excelBatches.recent"
          row-key="id"
          class="system-batch-table"
        >
          <el-table-column
            :label="t('system.batchFile')"
            min-width="200"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ row.originalFilename }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('system.batchStatus')"
            width="90"
          >
            <template #default="{ row }">
              {{ batchStatusText(row.status) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('system.batchRows')"
            width="80"
            align="right"
          >
            <template #default="{ row }">
              {{ row.rowCount }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('system.batchErrors')"
            width="80"
            align="right"
          >
            <template #default="{ row }">
              {{ row.errorCount }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('system.batchCreatedAt')"
            width="150"
          >
            <template #default="{ row }">
              {{ formatJstDateTime(row.createdAt) }}
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="kcgl-card system-card">
        <h2 class="system-card-title">
          {{ t('system.alertTitle', { n: status?.openAlerts ?? 0 }) }}
        </h2>
        <p
          v-if="alertsError"
          class="kcgl-error-box"
          role="alert"
        >
          {{ alertsError }}
          <el-button
            link
            type="primary"
            @click="loadAlerts"
          >
            {{ t('common.reload') }}
          </el-button>
        </p>
        <el-table
          v-else
          v-loading="alertsLoading"
          :data="alerts"
          row-key="id"
          class="system-alert-table"
        >
          <el-table-column
            :label="t('system.alertLevel')"
            width="80"
          >
            <template #default="{ row }">
              <span
                class="system-alert-level"
                :class="alertLevelClass((row as SysAlert).level)"
              >{{ alertLevelText((row as SysAlert).level) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('system.alertMessage')"
            min-width="240"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span :class="{ 'system-alert-open': (row as SysAlert).status === 0 }">
                {{ (row as SysAlert).message }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.at')"
            width="150"
          >
            <template #default="{ row }">
              {{ formatJstDateTime((row as SysAlert).createdAt) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('admin.actions')"
            width="100"
          >
            <template #default="{ row }">
              <el-button
                v-if="(row as SysAlert).status === 0"
                link
                type="primary"
                :disabled="isMarking((row as SysAlert).id)"
                @click="onMarkRead(row as SysAlert)"
              >
                {{ t('system.alertMarkRead') }}
              </el-button>
              <span
                v-else
                class="system-alert-read"
              >{{ t('system.alertRead') }}</span>
            </template>
          </el-table-column>
          <template #empty>
            {{ t('system.alertEmpty') }}
          </template>
        </el-table>
      </div>

      <div
        v-if="report !== null"
        class="kcgl-card system-card"
      >
        <h2 class="system-card-title">
          {{ t('system.check.reportTitle') }}（{{ formatJstDateTime(report.ranAt) }}）
        </h2>
        <p
          class="system-check-summary"
          :class="report.ok ? 'is-ok' : 'is-ng'"
        >
          {{ report.ok ? t('system.check.allOk') : t('system.check.foundNg') }}
        </p>
        <ul class="system-check-list">
          <li
            v-for="row in reportRows"
            :key="row.key"
            class="system-check-row"
          >
            <span
              class="system-check-badge"
              :class="row.ok ? 'is-ok' : 'is-ng'"
            >{{ row.ok ? 'OK' : 'NG' }}</span>
            <span class="system-check-label">{{ row.label }}</span>
            <span class="system-check-detail">{{ row.detail }}</span>
          </li>
        </ul>
      </div>

      <p
        v-if="checkError"
        class="kcgl-error-box"
        role="alert"
      >
        {{ checkError }}
      </p>
      <p
        v-if="exportError"
        class="kcgl-error-box"
        role="alert"
      >
        {{ exportError }}
      </p>
    </template>
  </section>
</template>

<style scoped>
.system-view {
  display: grid;
  gap: 16px;
}

.system-card {
  display: grid;
  gap: 12px;
  padding: 20px;
  justify-items: start;
}

.system-card-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.system-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  width: 100%;
}

.system-stat {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: var(--kcgl-color-bg);
}

.system-stat dt {
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
}

.system-stat dd {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  word-break: break-all;
}

/* backup 陈旧（>25h）红字——kcgl-doctor 检查 4 同口径，runbook D-3 首查项 */
.system-stat dd.is-danger {
  color: var(--kcgl-color-danger);
}

.system-error-bars {
  display: grid;
  gap: 4px;
  width: 100%;
}

.system-error-bar {
  display: grid;
  grid-template-columns: 1fr 36px 52px;
  align-items: center;
  gap: 8px;
}

.system-error-bar-fill {
  height: 12px;
  border-radius: 2px;
  background: var(--kcgl-color-primary);
  min-width: 2px;
}

.system-error-bar-count {
  text-align: right;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.system-error-bar-date {
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.system-alert-level {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 0.75rem;
}

.system-alert-level.is-danger {
  background: var(--kcgl-color-danger-bg, #fde2e2);
  color: var(--kcgl-color-danger);
}

.system-alert-level.is-warning {
  background: var(--kcgl-color-warning-bg, #fdf3d7);
  color: #9a6700;
}

.system-alert-level.is-neutral {
  background: var(--kcgl-color-bg);
  color: var(--kcgl-color-text-sub);
}

.system-alert-open {
  font-weight: 600;
}

.system-alert-read {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.system-check-summary {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
}

.system-check-summary.is-ok {
  color: var(--kcgl-color-primary);
}

.system-check-summary.is-ng {
  color: var(--kcgl-color-danger);
}

.system-check-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.system-check-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 0.85rem;
}

.system-check-badge {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 0.75rem;
  font-weight: 600;
}

.system-check-badge.is-ok {
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
}

.system-check-badge.is-ng {
  background: var(--kcgl-color-danger-bg, #fde2e2);
  color: var(--kcgl-color-danger);
}

.system-check-label {
  flex-shrink: 0;
  min-width: 6em;
  font-weight: 600;
}

.system-check-detail {
  color: var(--kcgl-color-text-sub);
}

.system-error {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

@media (max-width: 720px) {
  .system-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
