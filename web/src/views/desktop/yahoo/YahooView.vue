<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import AppEmptyState from '@/components/AppEmptyState.vue'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import {
  fetchPendingShipments,
  fetchYahooBatches,
  fetchYahooReconcile,
  fetchYahooUnmatched,
  uploadYahooImport,
} from '@/utils/api'
import type {
  YahooImportBatch,
  YahooPendingShipment,
  YahooReconcile,
  YahooReconcileRow,
  YahooUnmatchedRows,
} from '@/utils/api'

/**
 * 雅虎联动桌面页（M5-②b，docs/01 7.2/7.4）：三标签——受注インポート（受注
 * xlsx 上传毫秒级受理+处理中批次 2s 轮询/SSE 双通道接力终态、错误行与
 * まとめ売り補注展开）、出荷待ち（拣货队列，行内直达扫码卖出）、照合三活
 * 视图（滞留红标/近期同步降灰）。上传仅编辑者以上；读取全员（服务端
 * @PreAuthorize 兜底）。
 */

const POLL_INTERVAL_MS = 2000

const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const canUpload = computed(() => auth.me != null && auth.me.role <= 2)

const activeTab = ref('import')

// ------------------------------------------------------------- 受注インポート

const batches = ref<YahooImportBatch[]>([])
const batchesError = ref('')
// 遮罩只认"有没有在请求"，不认"有没有数据"：曾用 batches.length === 0 当条件，
// 于是取回一份**空列表**（本页最常态：还没导入过）时永远不撤——用户看到一直转圈。
// 初值 true 覆盖首帧到首次响应之间；之后只在显式重载时再置起，轮询刷新不闪遮罩。
const batchesLoading = ref(true)
const uploading = ref(false)
const uploadError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
let batchesSeq = 0
let pollTimer: number | undefined

/** 处理中批次存在时启动 2s 轮询（SSE 断连兜底），全部终态即停。 */
function syncPolling(): void {
  const hasProcessing = batches.value.some((batch) => batch.status === 0)
  if (hasProcessing && pollTimer === undefined) {
    pollTimer = window.setInterval(() => {
      void loadBatches()
    }, POLL_INTERVAL_MS)
  } else if (!hasProcessing && pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
    // 轮询见证批次终态：导入回写已可见——补齐本页另两份数据
    // （自己的 YAHOO_IMPORT 回声被 D-070 抑制，轮询是自端终态感知的主路径）
    void loadShipments()
    void loadReconcile()
  }
}

async function loadBatches(): Promise<void> {
  const seq = ++batchesSeq
  batchesError.value = ''
  try {
    const data = await fetchYahooBatches()
    if (seq !== batchesSeq) {
      return
    }
    batches.value = data
    syncPolling()
  } catch (error) {
    if (seq !== batchesSeq) {
      return
    }
    batchesError.value = toDisplayMessage(error, t)
  } finally {
    if (seq === batchesSeq) {
      batchesLoading.value = false
    }
  }
}

/**
 * 批次不一致行明细（D-105）：按需取，不在批次列表里带明细（一次展开才一个请求）。
 * 取回即缓存：批次已达终态，明细不再变（同一拍卖在后续导入再被见到会改归后批，
 * 那是后批展开时才需要的新数据）。取不到时只影响这一块，不影响批次报告本身。
 */
const unmatchedState = ref<Record<number, YahooUnmatchedState>>({})
/** 请求序号按批次记（全局单计数器会让"连开两个批次"的先前响应被后一个顶掉，永不落位）。 */
const unmatchedSeqs = new Map<number, number>()

interface YahooUnmatchedState {
  loading: boolean
  error: string
  data?: YahooUnmatchedRows
}

/**
 * 是否展示/拉取不一致行明细：0 件不必请求，其余（含计数未知）都取。
 * 渲染条件与请求条件必须同一判据——否则会出现"请求了却永不渲染"的空转。
 */
function hasUnmatched(row: YahooImportBatch): boolean {
  return row.unmatchedCount !== 0
}

async function onBatchExpand(row: YahooImportBatch, expandedRows: YahooImportBatch[]): Promise<void> {
  if (!hasUnmatched(row) || !expandedRows.includes(row)) {
    return
  }
  const current = unmatchedState.value[row.id]
  if (current?.loading === true || current?.data != null) {
    return // 在途或已有明细：不重复请求（上次失败则允许再展开重试）
  }
  const seq = (unmatchedSeqs.get(row.id) ?? 0) + 1
  unmatchedSeqs.set(row.id, seq)
  unmatchedState.value[row.id] = { loading: true, error: '' }
  try {
    const data = await fetchYahooUnmatched(row.id)
    if (unmatchedSeqs.get(row.id) !== seq) {
      return
    }
    unmatchedState.value[row.id] = { loading: false, error: '', data }
  } catch (error) {
    if (unmatchedSeqs.get(row.id) !== seq) {
      return
    }
    unmatchedState.value[row.id] = { loading: false, error: toDisplayMessage(error, t) }
  }
}

function pickFile(): void {
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 清空选择：同一文件修正后（如 409 提示）可再次触发 change
  if (file == null || uploading.value) {
    return
  }
  uploading.value = true
  uploadError.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    await uploadYahooImport(form)
    await loadBatches()
    // 极快完成竞态兜底：响应后重读已全终态（轮询未启动过）时直接收敛另两份数据
    if (!batches.value.some((batch) => batch.status === 0)) {
      void loadShipments()
      void loadReconcile()
    }
  } catch (error) {
    uploadError.value = toDisplayMessage(error, t)
  } finally {
    uploading.value = false
  }
}

function statusText(status: number): string {
  return status === 0
    ? t('yahoo.import.statusProcessing')
    : status === 1 ? t('yahoo.import.statusDone') : t('yahoo.import.statusFailed')
}

function statusClass(status: number): string {
  return status === 0 ? 'is-processing' : status === 1 ? 'is-done' : 'is-failed'
}

// ------------------------------------------------------------- 出荷待ち

const shipmentItems = ref<YahooPendingShipment[]>([])
const shipmentsError = ref('')
let shipmentsSeq = 0

async function loadShipments(): Promise<void> {
  const seq = ++shipmentsSeq
  shipmentsError.value = ''
  try {
    const data = await fetchPendingShipments()
    if (seq !== shipmentsSeq) {
      return
    }
    shipmentItems.value = data.items
  } catch (error) {
    if (seq !== shipmentsSeq) {
      return
    }
    shipmentsError.value = toDisplayMessage(error, t)
  }
}

function goSell(item: YahooPendingShipment): void {
  void router.push({ name: 'scan', query: { code: item.itemCode } })
}

// ------------------------------------------------------------- 照合

const reconcileData = ref<YahooReconcile | null>(null)
const reconcileError = ref('')
let reconcileSeq = 0

async function loadReconcile(): Promise<void> {
  const seq = ++reconcileSeq
  reconcileError.value = ''
  try {
    const data = await fetchYahooReconcile()
    if (seq !== reconcileSeq) {
      return
    }
    reconcileData.value = data
  } catch (error) {
    if (seq !== reconcileSeq) {
      return
    }
    reconcileError.value = toDisplayMessage(error, t)
  }
}

// el-table-column 渲染列时以 {row:{}} 探测嵌套列（TableColumnRenderer），
// 动态 i18n key 必须空值兜底——否则空数据页也刷 missing-key 告警
function warehouseOf(warehouse: number | null | undefined): string {
  return warehouse == null ? '—' : t(`common.warehouse.${warehouse}`)
}

// ------------------------------------------------------------- 装配

function reloadAll(): void {
  void loadBatches()
  void loadShipments()
  void loadReconcile()
}

// 卖出（INVENTORY）与 CSV 回写（YAHOO_IMPORT）都会改变三份数据
useSyncInvalidation(['INVENTORY', 'YAHOO_IMPORT'], reloadAll)

onMounted(reloadAll)

onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <section class="yahoo-view">
    <AppPageHeader :title="t('yahoo.title')" />

    <div class="kcgl-card yahoo-body">
      <el-tabs
        v-model="activeTab"
        class="yahoo-tabs"
      >
        <el-tab-pane
          :label="t('yahoo.import.title')"
          name="import"
        >
          <div class="yahoo-upload">
            <input
              ref="fileInput"
              type="file"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              class="yahoo-upload-input"
              @change="onFileChange"
            >
            <div class="yahoo-upload-row">
              <el-button
                type="primary"
                :loading="uploading"
                :disabled="!canUpload"
                @click="pickFile"
              >
                {{ uploading ? t('yahoo.import.uploading') : t('yahoo.import.upload') }}
              </el-button>
              <p class="yahoo-hint">
                {{ t('yahoo.import.hint') }}
              </p>
            </div>
            <p
              v-if="!canUpload"
              class="kcgl-info-box"
            >
              {{ t('yahoo.import.roleDenied') }}
            </p>
            <p
              v-if="uploadError"
              class="kcgl-error-box"
              role="alert"
            >
              {{ uploadError }}
            </p>
          </div>

          <h2 class="yahoo-section-title">
            {{ t('yahoo.import.listTitle') }}
          </h2>
          <p
            v-if="batchesError"
            class="kcgl-error-box"
            role="alert"
          >
            {{ batchesError }}
            <el-button
              link
              type="primary"
              @click="loadBatches"
            >
              {{ t('common.reload') }}
            </el-button>
          </p>
          <el-table
            v-else
            v-loading="batchesLoading"
            :data="batches"
            row-key="id"
            class="yahoo-table"
            @expand-change="onBatchExpand"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="yahoo-detail">
                  <p
                    v-if="(row as YahooImportBatch).errorMessage"
                    class="kcgl-error-box"
                  >
                    {{ t('yahoo.import.errorMessage') }}：{{ (row as YahooImportBatch).errorMessage }}
                  </p>
                  <p
                    v-if="(row as YahooImportBatch).note"
                    class="kcgl-info-box"
                  >
                    {{ (row as YahooImportBatch).note }}
                  </p>
                  <template v-if="(row as YahooImportBatch).errorRows.length > 0">
                    <p class="yahoo-errors-title">
                      {{ t('yahoo.import.errorRows') }}
                    </p>
                    <el-table
                      :data="(row as YahooImportBatch).errorRows"
                      size="small"
                      class="yahoo-errors-table"
                    >
                      <el-table-column
                        :label="t('yahoo.import.errorLine')"
                        prop="line"
                        width="80"
                      />
                      <el-table-column
                        :label="t('yahoo.import.errorRaw')"
                        prop="raw"
                        min-width="260"
                      />
                      <el-table-column
                        :label="t('yahoo.import.errorReason')"
                        prop="reason"
                        min-width="220"
                      />
                    </el-table>
                  </template>
                  <template v-if="hasUnmatched(row as YahooImportBatch)">
                    <p class="yahoo-errors-title">
                      {{ t('yahoo.import.unmatchedRows') }}
                      <span
                        v-if="unmatchedState[row.id]?.data"
                        class="yahoo-unmatched-total"
                      >{{ t('yahoo.import.unmatchedTotal', { count: unmatchedState[row.id]?.data?.total ?? 0 }) }}</span>
                    </p>
                    <p
                      v-if="unmatchedState[row.id]?.loading"
                      class="yahoo-unmatched-note"
                    >
                      {{ t('common.loading') }}
                    </p>
                    <p
                      v-else-if="unmatchedState[row.id]?.error"
                      class="kcgl-error-box"
                    >
                      {{ unmatchedState[row.id]?.error }}
                    </p>
                    <template v-else-if="(unmatchedState[row.id]?.data?.rows.length ?? 0) > 0">
                      <el-table
                        :data="unmatchedState[row.id]?.data?.rows ?? []"
                        size="small"
                        class="yahoo-errors-table"
                      >
                        <el-table-column
                          :label="t('yahoo.itemCode')"
                          prop="selfCode"
                          min-width="150"
                        />
                        <el-table-column
                          :label="t('yahoo.orderId')"
                          prop="orderId"
                          min-width="120"
                        />
                        <el-table-column
                          :label="t('yahoo.auctionId')"
                          prop="auctionId"
                          min-width="150"
                        />
                        <el-table-column
                          :label="t('yahoo.reconcile.closedAt')"
                          width="170"
                        >
                          <template #default="scope">
                            {{ scope.row.closedAt ? formatJstDateTime(scope.row.closedAt) : '—' }}
                          </template>
                        </el-table-column>
                        <el-table-column
                          :label="t('yahoo.reconcile.soldPrice')"
                          width="120"
                          align="right"
                        >
                          <template #default="scope">
                            {{ scope.row.soldPrice == null ? '—' : formatYen(scope.row.soldPrice) }}
                          </template>
                        </el-table-column>
                      </el-table>
                      <p
                        v-if="unmatchedState[row.id]?.data?.truncated"
                        class="yahoo-unmatched-note"
                      >
                        {{
                          t('yahoo.import.unmatchedTruncated', {
                            shown: unmatchedState[row.id]?.data?.rows.length ?? 0,
                          })
                        }}
                      </p>
                      <!-- 说明句与它说明的清单同进同出：有清单才说"下記" -->
                      <p class="yahoo-unmatched-note">
                        {{ t('yahoo.import.unmatchedNote') }}
                      </p>
                    </template>
                    <!--
                      报告里的计数是**当时**的（unmatched_count），清单是**此刻**的
                      （last_seen_batch_id + item_id IS NULL）：同一拍卖在后续导入里被
                      认领后，计数仍在而清单已空。此时标题写着"不一致行"却什么都没有，
                      再挂"下記に…"就是指向不存在的东西——给一句明说。
                      判据带 data 非空：首帧（请求还没发出）不会闪一句错话。
                    -->
                    <p
                      v-else-if="unmatchedState[row.id]?.data"
                      class="yahoo-unmatched-note"
                    >
                      {{ t('yahoo.import.unmatchedEmpty') }}
                    </p>
                  </template>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.filename')"
              prop="originalFilename"
              min-width="180"
              show-overflow-tooltip
            />
            <el-table-column
              :label="t('admin.status')"
              width="90"
            >
              <template #default="{ row }">
                <span
                  class="yahoo-tag"
                  :class="statusClass((row as YahooImportBatch).status)"
                >{{ statusText((row as YahooImportBatch).status) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.rowCount')"
              width="90"
              align="right"
            >
              <template #default="{ row }">
                {{ (row as YahooImportBatch).rowCount ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.matched')"
              width="90"
              align="right"
            >
              <template #default="{ row }">
                {{ (row as YahooImportBatch).matchedCount ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.unmatched')"
              width="90"
              align="right"
            >
              <template #default="{ row }">
                {{ (row as YahooImportBatch).unmatchedCount ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.updated')"
              width="90"
              align="right"
            >
              <template #default="{ row }">
                {{ (row as YahooImportBatch).updatedCount ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.uploadedAt')"
              width="150"
            >
              <template #default="{ row }">
                {{ formatJstDateTime((row as YahooImportBatch).createdAt) }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('yahoo.import.finishedAt')"
              width="150"
            >
              <template #default="{ row }">
                {{ formatJstDateTime((row as YahooImportBatch).finishedAt) }}
              </template>
            </el-table-column>
            <template #empty>
              <AppEmptyState
                compact
                :title="t('yahoo.import.emptyList')"
                :description="t('yahoo.import.emptyHint')"
              />
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane
          :label="t('yahoo.shipments.title')"
          name="shipments"
        >
          <p
            v-if="shipmentsError"
            class="kcgl-error-box"
            role="alert"
          >
            {{ shipmentsError }}
            <el-button
              link
              type="primary"
              @click="loadShipments"
            >
              {{ t('common.reload') }}
            </el-button>
          </p>
          <template v-else>
            <p class="yahoo-section-count">
              {{ t('yahoo.shipments.count', { n: shipmentItems.length }) }}
            </p>
            <el-table
              :data="shipmentItems"
              row-key="itemId"
              class="yahoo-table"
            >
              <el-table-column
                width="70"
              >
                <template #default="{ row }">
                  <span class="yahoo-thumb">
                    <img
                      v-if="(row as YahooPendingShipment).thumbUrl"
                      :src="(row as YahooPendingShipment).thumbUrl ?? undefined"
                      alt=""
                      loading="lazy"
                    >
                  </span>
                </template>
              </el-table-column>
              <el-table-column
                prop="itemCode"
                :label="t('yahoo.itemCode')"
                min-width="130"
              >
                <template #default="{ row }">
                  <span class="yahoo-code">{{ (row as YahooPendingShipment).itemCode }}</span>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.warehouse')"
                width="120"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as YahooPendingShipment).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.shelfNo')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooPendingShipment).shelfNo ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.reconcile.soldPrice')"
                width="110"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as YahooPendingShipment).soldPrice) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.orderId')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooPendingShipment).orderId ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.shipments.closedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as YahooPendingShipment).closedAt) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.auctionId')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooPendingShipment).auctionId ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                width="110"
              >
                <template #default="{ row }">
                  <span
                    v-if="(row as YahooPendingShipment).delayed"
                    class="yahoo-tag is-failed"
                  >{{ t('yahoo.shipments.delayed') }}</span>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('admin.actions')"
                width="170"
              >
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="goSell(row as YahooPendingShipment)"
                  >
                    {{ t('yahoo.shipments.goSell') }}
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('yahoo.shipments.empty')"
                  :description="t('yahoo.shipments.emptyHint')"
                />
              </template>
            </el-table>
          </template>
        </el-tab-pane>

        <el-tab-pane
          :label="t('yahoo.reconcile.title')"
          name="reconcile"
        >
          <p
            v-if="reconcileError"
            class="kcgl-error-box"
            role="alert"
          >
            {{ reconcileError }}
            <el-button
              link
              type="primary"
              @click="loadReconcile"
            >
              {{ t('common.reload') }}
            </el-button>
          </p>
          <template v-else-if="reconcileData">
            <h3 class="yahoo-section-subtitle">
              {{ t('yahoo.reconcile.soldNotShipped') }}（{{ reconcileData.soldNotShipped.length }}）
            </h3>
            <el-table
              :data="reconcileData.soldNotShipped"
              row-key="itemId"
              class="yahoo-table"
            >
              <el-table-column
                prop="itemCode"
                :label="t('yahoo.itemCode')"
                min-width="130"
              >
                <template #default="{ row }">
                  <span class="yahoo-code">{{ (row as YahooReconcileRow).itemCode }}</span>
                </template>
              </el-table-column>
              <el-table-column
                width="140"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as YahooReconcileRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.shelfNo')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).shelfNo ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.reconcile.soldPrice')"
                width="110"
                align="right"
              >
                <template #default="{ row }">
                  {{ formatYen((row as YahooReconcileRow).soldPrice) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.orderId')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).orderId ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.auctionId')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).auctionId ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.reconcile.closedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as YahooReconcileRow).closedAt) }}
                </template>
              </el-table-column>
              <el-table-column
                width="100"
              >
                <template #default="{ row }">
                  <span
                    v-if="(row as YahooReconcileRow).delayed"
                    class="yahoo-tag is-failed"
                  >{{ t('yahoo.reconcile.delayed') }}</span>
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('yahoo.reconcile.empty')"
                  :description="t('yahoo.reconcile.emptyHint')"
                />
              </template>
            </el-table>

            <h3 class="yahoo-section-subtitle">
              {{ t('yahoo.reconcile.canceledNotRelisted') }}（{{ reconcileData.canceledNotRelisted.length }}）
            </h3>
            <el-table
              :data="reconcileData.canceledNotRelisted"
              row-key="itemId"
              class="yahoo-table"
            >
              <el-table-column
                prop="itemCode"
                :label="t('yahoo.itemCode')"
                min-width="130"
              >
                <template #default="{ row }">
                  <span class="yahoo-code">{{ (row as YahooReconcileRow).itemCode }}</span>
                </template>
              </el-table-column>
              <el-table-column
                width="140"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as YahooReconcileRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.shelfNo')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).shelfNo ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.auctionId')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).auctionId ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.reconcile.closedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as YahooReconcileRow).closedAt) }}
                </template>
              </el-table-column>
              <el-table-column
                width="100"
              >
                <template #default="{ row }">
                  <span
                    v-if="(row as YahooReconcileRow).delayed"
                    class="yahoo-tag is-failed"
                  >{{ t('yahoo.reconcile.delayed') }}</span>
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('yahoo.reconcile.empty')"
                  :description="t('yahoo.reconcile.emptyHint')"
                />
              </template>
            </el-table>

            <h3 class="yahoo-section-subtitle">
              {{ t('yahoo.reconcile.withdrawNeeded') }}（{{ reconcileData.withdrawNeeded.length }}）
            </h3>
            <el-table
              :data="reconcileData.withdrawNeeded"
              row-key="itemId"
              class="yahoo-table"
            >
              <el-table-column
                prop="itemCode"
                :label="t('yahoo.itemCode')"
                min-width="130"
              >
                <template #default="{ row }">
                  <span class="yahoo-code">{{ (row as YahooReconcileRow).itemCode }}</span>
                </template>
              </el-table-column>
              <el-table-column
                width="140"
              >
                <template #default="{ row }">
                  {{ warehouseOf((row as YahooReconcileRow).warehouse) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.shelfNo')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).shelfNo ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.auctionId')"
                width="110"
              >
                <template #default="{ row }">
                  {{ (row as YahooReconcileRow).auctionId ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.reconcile.closedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as YahooReconcileRow).closedAt) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('yahoo.reconcile.lastSyncedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as YahooReconcileRow).lastSyncedAt) }}
                </template>
              </el-table-column>
              <el-table-column
                width="150"
              >
                <template #default="{ row }">
                  <span
                    v-if="(row as YahooReconcileRow).recentlySynced"
                    class="yahoo-tag is-muted"
                  >{{ t('yahoo.reconcile.recentlySynced') }}</span>
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('yahoo.reconcile.empty')"
                  :description="t('yahoo.reconcile.emptyHint')"
                />
              </template>
            </el-table>

            <p class="yahoo-unmatched-note">
              {{ t('yahoo.reconcile.note') }}
            </p>
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>
  </section>
</template>

<style scoped>
.yahoo-view {
  display: grid;
  gap: 16px;
}

.yahoo-body {
  padding: 20px 24px;
}

.yahoo-upload {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-bg);
}

.yahoo-upload-input {
  display: none;
}

.yahoo-upload-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.yahoo-hint {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.yahoo-section-title {
  margin: 24px 0 10px;
  font-size: 1.05rem;
  font-weight: 600;
}

.yahoo-section-subtitle {
  margin: 20px 0 8px;
  font-size: 0.95rem;
  font-weight: 600;
}

.yahoo-section-count {
  margin: 0 0 10px;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.yahoo-table {
  width: 100%;
}

.yahoo-detail {
  display: grid;
  gap: 8px;
  padding: 4px 8px;
}

.yahoo-errors-title {
  margin: 0;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

/* 件数跟在标题后面：同一行读"不一致行 N 件"，不用再扫一遍报告列。 */
.yahoo-unmatched-total {
  margin-left: var(--kcgl-space-2);
  font-weight: 400;
  color: var(--kcgl-color-text-faint);
}

.yahoo-errors-table {
  max-width: 760px;
}

.yahoo-unmatched-note {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.yahoo-tag {
  display: inline-block;
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.yahoo-tag.is-processing {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.yahoo-tag.is-done {
  border-color: var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.yahoo-tag.is-failed {
  border-color: var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
}

.yahoo-tag.is-muted {
  border-color: var(--kcgl-color-border);
  background: var(--kcgl-color-bg);
  color: var(--kcgl-color-text-faint);
}

.yahoo-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.yahoo-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.yahoo-code {
  font-weight: 600;
  letter-spacing: 0.02em;
}
</style>
