<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import epEn from 'element-plus/es/locale/lang/en'
import epJa from 'element-plus/es/locale/lang/ja'
import epZhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthStore } from '@/stores/auth'
import { useDictsStore } from '@/stores/dicts'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import AppEmptyState from '@/components/AppEmptyState.vue'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { dayjs, formatJstDateTime, JST_TZ } from '@/utils/format'
import { normalizeNumericText } from '@/utils/normalize'
import { toDisplayMessage } from '@/utils/errors'
import {
  downloadExcelExport,
  downloadExcelTemplate,
  fetchExcelBatches,
  uploadExcelWorkbook,
} from '@/utils/api'
import type { ExcelImportBatch } from '@/utils/api'

/**
 * エクセル連携桌面页（M4-⑤，D-058）：两标签——インポート（模板下载+上传毫秒级
 * 受理+处理中批次 2s 轮询/SSE 双通道接力终态、错误行与採番メモ展开）、
 * エクスポート（作成日区间+会场+管理番号単票抽出→帳票 25 列下载）。
 * 模板/上传仅编辑者以上；批次报告/导出全员（服务端 @PreAuthorize 兜底）。
 */

const POLL_INTERVAL_MS = 2000

const { t, locale } = useI18n()
const auth = useAuthStore()
const dicts = useDictsStore()

const canUpload = computed(() => auth.me != null && auth.me.role <= 2)

const route = useRoute()

/** 标签页取值白名单：query 是外部输入（手改地址/深链），非法值一律落回既定默认。 */
const TAB_NAMES = ['import', 'export'] as const
type TabName = (typeof TAB_NAMES)[number]

function tabFromQuery(): TabName {
  const requested = route.query.tab
  return TAB_NAMES.find((name) => name === requested) ?? 'import'
}

const activeTab = ref<TabName>(tabFromQuery())

/**
 * 商品页工具栏的「一括入出力」深链走 `?tab=`；两处同 path、只有 query 变，
 * 换页用的是 path 键（D-104），组件不会重挂 → 必须监听 query 才能切换标签页。
 */
watch(
  () => route.query.tab,
  () => {
    activeTab.value = tabFromQuery()
  },
)

// EP 组件内置文案随应用语言联动（日期面板/清除按钮等）
const epLocale = computed(() => {
  if (locale.value === 'zh-CN') return epZhCn
  if (locale.value === 'en-US') return epEn
  return epJa
})

// ------------------------------------------------------------- インポート

const batches = ref<ExcelImportBatch[]>([])
const batchesError = ref('')
// 遮罩只认"有没有在请求"，不认"有没有数据"：曾用 batches.length === 0 当条件，
// 于是取回一份**空列表**（本页最常态：还没导入过）时永远不撤——用户看到一直转圈。
// 初值 true 覆盖首帧到首次响应之间；之后只在显式重载时再置起，轮询刷新不闪遮罩。
const batchesLoading = ref(true)
const uploading = ref(false)
const uploadError = ref('')
const templateDownloading = ref(false)
const templateError = ref('')
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
  }
}

async function loadBatches(): Promise<void> {
  const seq = ++batchesSeq
  batchesError.value = ''
  try {
    const data = await fetchExcelBatches()
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

/** 浏览器保存下载 blob（模板/导出共用；文件名取自 Content-Disposition）。 */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

async function downloadTemplate(): Promise<void> {
  templateDownloading.value = true
  templateError.value = ''
  try {
    const { blob, filename } = await downloadExcelTemplate()
    saveBlob(blob, filename)
  } catch (error) {
    templateError.value = toDisplayMessage(error, t)
  } finally {
    templateDownloading.value = false
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
    await uploadExcelWorkbook(form)
    await loadBatches()
  } catch (error) {
    uploadError.value = toDisplayMessage(error, t)
  } finally {
    uploading.value = false
  }
}

function statusText(status: number): string {
  return status === 0
    ? t('excel.import.statusProcessing')
    : status === 1 ? t('excel.import.statusDone') : t('excel.import.statusFailed')
}

function statusClass(status: number): string {
  return status === 0 ? 'is-processing' : status === 1 ? 'is-done' : 'is-failed'
}

// ------------------------------------------------------------- エクスポート

function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

const exportRange = ref<[string, string]>([todayJst(), todayJst()])
const exportVenueId = ref<number | null>(null)
const exportCode = ref('')
const exporting = ref(false)
const exportError = ref('')

/** 管理号入力（blur 归一化，IME 组合输入中不转换——7.8 纪律）。 */
function normalizeCodeOnBlur(): void {
  exportCode.value = normalizeNumericText(exportCode.value).toUpperCase()
}

async function runExport(): Promise<void> {
  exportError.value = ''
  const code = normalizeNumericText(exportCode.value).trim().toUpperCase()
  const [from, to] = exportRange.value
  // 无码条件时倒挂区间就地提示（有码=単票抽出，区间不校验——与后端口径一致）
  if (code === '' && from > to) {
    exportError.value = t('excel.export.rangeInverted')
    return
  }
  exporting.value = true
  try {
    const { blob, filename } = await downloadExcelExport({
      createdFrom: from,
      createdTo: to,
      venueId: exportVenueId.value,
      code: code === '' ? undefined : code,
    })
    saveBlob(blob, filename)
  } catch (error) {
    exportError.value = toDisplayMessage(error, t)
  } finally {
    exporting.value = false
  }
}

// ------------------------------------------------------------- 装配

function reloadAll(): void {
  void loadBatches()
}

// 导入完成广播 EXCEL_IMPORT（批次域）+ ITEM（商品域）；本页只消费批次域
useSyncInvalidation(['EXCEL_IMPORT'], reloadAll)

onMounted(() => {
  void loadBatches()
  // 会场下拉加载失败不阻断导出（无会场筛选仍可按日期导出）
  dicts.ensureLoaded().catch(() => {})
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <el-config-provider :locale="epLocale">
    <section class="excel-view">
      <AppPageHeader :title="t('excel.title')" />

      <div class="kcgl-card excel-body">
        <el-tabs
          v-model="activeTab"
          class="excel-tabs"
        >
          <el-tab-pane
            :label="t('excel.import.title')"
            name="import"
          >
            <div class="excel-upload">
              <input
                ref="fileInput"
                type="file"
                accept=".xlsx"
                class="excel-upload-input"
                @change="onFileChange"
              >
              <div class="excel-upload-row">
                <el-button
                  :loading="templateDownloading"
                  :disabled="!canUpload"
                  @click="downloadTemplate"
                >
                  {{ t('excel.import.template') }}
                </el-button>
                <el-button
                  type="primary"
                  :loading="uploading"
                  :disabled="!canUpload"
                  @click="pickFile"
                >
                  {{ uploading ? t('excel.import.uploading') : t('excel.import.upload') }}
                </el-button>
              </div>
              <p class="excel-hint">
                {{ t('excel.import.hint') }}
              </p>
              <p
                v-if="!canUpload"
                class="kcgl-info-box"
              >
                {{ t('excel.import.roleDenied') }}
              </p>
              <p
                v-if="uploadError"
                class="kcgl-error-box"
                role="alert"
              >
                {{ uploadError }}
              </p>
              <p
                v-if="templateError"
                class="kcgl-error-box"
                role="alert"
              >
                {{ templateError }}
              </p>
            </div>

            <h2 class="excel-section-title">
              {{ t('excel.import.listTitle') }}
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
              class="excel-table"
            >
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="excel-detail">
                    <p
                      v-if="(row as ExcelImportBatch).errorMessage"
                      class="kcgl-error-box"
                    >
                      {{ t('excel.import.errorMessage') }}：{{ (row as ExcelImportBatch).errorMessage }}
                    </p>
                    <template v-if="(row as ExcelImportBatch).errorRows.length > 0">
                      <p class="excel-errors-title">
                        {{ t('excel.import.errorRows') }}
                      </p>
                      <el-table
                        :data="(row as ExcelImportBatch).errorRows"
                        size="small"
                        class="excel-errors-table"
                      >
                        <el-table-column
                          :label="t('excel.import.errorLine')"
                          prop="line"
                          width="80"
                        />
                        <el-table-column
                          :label="t('excel.import.errorRaw')"
                          prop="raw"
                          min-width="260"
                        />
                        <el-table-column
                          :label="t('excel.import.errorReason')"
                          prop="reason"
                          min-width="220"
                        />
                      </el-table>
                    </template>
                    <p
                      v-if="(row as ExcelImportBatch).note"
                      class="excel-note"
                    >
                      {{ t('excel.import.note') }}：{{ (row as ExcelImportBatch).note }}
                    </p>
                    <p class="excel-note-hint">
                      {{ t('excel.import.noteHint') }}
                    </p>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.filename')"
                prop="originalFilename"
                min-width="170"
                show-overflow-tooltip
              />
              <el-table-column
                :label="t('admin.status')"
                width="90"
              >
                <template #default="{ row }">
                  <span
                    class="excel-tag"
                    :class="statusClass((row as ExcelImportBatch).status)"
                  >{{ statusText((row as ExcelImportBatch).status) }}</span>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.rowCount')"
                width="90"
                align="right"
              >
                <template #default="{ row }">
                  {{ (row as ExcelImportBatch).rowCount }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.generated')"
                width="100"
                align="right"
              >
                <template #default="{ row }">
                  {{ (row as ExcelImportBatch).generatedCount }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.imported')"
                width="100"
                align="right"
              >
                <template #default="{ row }">
                  {{ (row as ExcelImportBatch).importedCount }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.errorCount')"
                width="90"
                align="right"
              >
                <template #default="{ row }">
                  {{ (row as ExcelImportBatch).errorCount }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.note')"
                min-width="150"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ (row as ExcelImportBatch).note ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.uploadedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as ExcelImportBatch).createdAt) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="t('excel.import.finishedAt')"
                width="150"
              >
                <template #default="{ row }">
                  {{ formatJstDateTime((row as ExcelImportBatch).finishedAt) }}
                </template>
              </el-table-column>
              <template #empty>
                <AppEmptyState
                  compact
                  :title="t('excel.import.emptyList')"
                  :description="t('excel.import.emptyHint')"
                />
              </template>
            </el-table>
          </el-tab-pane>

          <el-tab-pane
            :label="t('excel.export.title')"
            name="export"
          >
            <div class="excel-export">
              <div class="excel-export-row">
                <label class="excel-field">
                  <span class="excel-field-label">{{ t('excel.export.rangeLabel') }}</span>
                  <el-date-picker
                    v-model="exportRange"
                    type="daterange"
                    value-format="YYYY-MM-DD"
                    :clearable="false"
                    :start-placeholder="t('excel.export.rangeFrom')"
                    :end-placeholder="t('excel.export.rangeTo')"
                    class="excel-range"
                  />
                </label>
                <label class="excel-field">
                  <span class="excel-field-label">{{ t('excel.export.venueLabel') }}</span>
                  <el-select
                    v-model="exportVenueId"
                    clearable
                    :placeholder="t('excel.export.venueAll')"
                    class="excel-venue"
                  >
                    <el-option
                      v-for="venue in dicts.enabledVenues"
                      :key="venue.id"
                      :value="venue.id"
                      :label="`${venue.name}（${venue.code}）`"
                    />
                  </el-select>
                </label>
                <label class="excel-field">
                  <span class="excel-field-label">{{ t('excel.export.codeLabel') }}</span>
                  <el-input
                    v-model="exportCode"
                    class="excel-code"
                    :placeholder="t('excel.export.codePlaceholder')"
                    clearable
                    @blur="normalizeCodeOnBlur"
                  />
                </label>
                <div class="excel-field">
                  <span class="excel-field-label">&nbsp;</span>
                  <el-button
                    type="primary"
                    :loading="exporting"
                    @click="runExport"
                  >
                    {{ exporting ? t('excel.export.downloading') : t('excel.export.download') }}
                  </el-button>
                </div>
              </div>
              <p class="excel-hint">
                {{ t('excel.export.hint') }}
              </p>
              <p
                v-if="exportError"
                class="kcgl-error-box"
                role="alert"
              >
                {{ exportError }}
              </p>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>
  </el-config-provider>
</template>

<style scoped>
.excel-view {
  display: grid;
  gap: 16px;
}

.excel-body {
  padding: 20px 24px;
}

.excel-upload,
.excel-export {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-bg);
}

.excel-upload-input {
  display: none;
}

.excel-upload-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.excel-hint {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.excel-export-row {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}

.excel-field {
  display: grid;
  gap: 6px;
}

.excel-field-label {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.excel-range {
  width: 240px;
}

.excel-venue {
  width: 220px;
}

.excel-code {
  width: 220px;
}

.excel-section-title {
  margin: 24px 0 10px;
  font-size: 1.05rem;
  font-weight: 600;
}

.excel-table {
  width: 100%;
}

.excel-detail {
  display: grid;
  gap: 8px;
  padding: 4px 8px;
}

.excel-errors-title {
  margin: 0;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.excel-errors-table {
  max-width: 760px;
}

.excel-note {
  margin: 0;
  font-size: 0.85rem;
  font-weight: 600;
}

.excel-note-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.excel-tag {
  display: inline-block;
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.excel-tag.is-processing {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.excel-tag.is-done {
  border-color: var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.excel-tag.is-failed {
  border-color: var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
}
</style>
