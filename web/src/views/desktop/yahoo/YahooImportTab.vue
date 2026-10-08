<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import AppEmptyState from '@/components/AppEmptyState.vue'
import { formatJstDateTime, formatYen } from '@/utils/format'
import { renderMessage, renderMessageJson, renderNoteJson, toDisplayMessage } from '@/utils/errors'
import { fetchYahooBatches, fetchYahooUnmatched, uploadYahooImport } from '@/utils/api'
import type { YahooImportBatch, YahooImportErrorRow, YahooUnmatchedRows } from '@/utils/api'

/**
 * 受注インポート页签（M5-②b）：受注 xlsx 上传毫秒级受理；处理中批次 2s 轮询 +
 * SSE 双通道接力终态；批次报告（状态/四计数/まとめ売り補注）、错误行与不一致行明细
 * （按需取 + 缓存）。上传仅编辑者以上。
 *
 * 从 YahooView 抽出来的（D-144）。与另两个页签的唯一真依赖是"批次到终态了、导入
 * 回写已可见"——这条用 emit('settled') 交给父组件去刷那两份数据，本组件不直呼它们
 * 的加载函数。2s 轮询定时器随本组件走，随它一起卸载。
 */

const { t } = useI18n()

const POLL_INTERVAL_MS = 2000

const emit = defineEmits<{ settled: [] }>()

const auth = useAuthStore()

const canUpload = computed(() => auth.me != null && auth.me.role <= 2)

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
    // 轮询见证批次终态：导入回写已可见——通知父组件补齐另两个页签的数据
    // （自己的 YAHOO_IMPORT 回声被 D-070 抑制，轮询是自端终态感知的主路径）
    emit('settled')
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

// 导入报告三处落库文案的渲染出口（D-127）：后端同时存 code+params 与日文原文，
// 这里按当前语言出文案，历史批次（无 code）自动回退原文。见 utils/errors.ts。

/** 批次级失败提示（errorMessageParams 是 JSON 列，线上为字符串）。 */
function batchErrorMessage(row: YahooImportBatch): string | null {
  return renderMessageJson(row.errorMessageCode, row.errorMessageParams, t, row.errorMessage)
}

/** 批次補注（まとめ売り 単価未分割）：结构化数组逐条翻译后重连。 */
function batchNote(row: YahooImportBatch): string | null {
  return renderNoteJson(row.noteJson, row.note, t)
}

/** 错误行原因。 */
function rowReason(row: YahooImportErrorRow): string {
  return renderMessage(row.code, row.params, t, row.reason)
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
      emit('settled')
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

// 离开本页时正在处理的批次还没到终态是常态：定时器必须随组件一起停，
// 否则它继续按 2s 打接口，而回调里的 batches 早已无人渲染。
onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
})

/** 父组件调它：初始载入与 SSE 失效整页重取（首载由父组件的 onMounted 触发，本组件不自载）。 */
function reload(): Promise<void> {
  return loadBatches()
}

defineExpose({ reload })
</script>

<template>
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
    class="kcgl-yahoo-table"
    @expand-change="onBatchExpand"
  >
    <el-table-column type="expand">
      <template #default="{ row }">
        <div class="yahoo-detail">
          <p
            v-if="batchErrorMessage(row as YahooImportBatch)"
            class="kcgl-error-box"
          >
            {{ t('yahoo.import.errorMessage') }}：{{ batchErrorMessage(row as YahooImportBatch) }}
          </p>
          <p
            v-if="batchNote(row as YahooImportBatch)"
            class="kcgl-info-box"
          >
            {{ batchNote(row as YahooImportBatch) }}
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
                min-width="220"
              >
                <template #default="{ row: errorRow }">
                  {{ rowReason(errorRow as YahooImportErrorRow) }}
                </template>
              </el-table-column>
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
              class="kcgl-yahoo-note"
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
                class="kcgl-yahoo-note"
              >
                {{
                  t('yahoo.import.unmatchedTruncated', {
                    shown: unmatchedState[row.id]?.data?.rows.length ?? 0,
                  })
                }}
              </p>
              <!-- 说明句与它说明的清单同进同出：有清单才说"下記" -->
              <p class="kcgl-yahoo-note">
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
              class="kcgl-yahoo-note"
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
          class="kcgl-yahoo-tag"
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
</template>

<style scoped>
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
</style>
