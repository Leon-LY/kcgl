<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import { toDisplayMessage } from '@/utils/errors'
import { newClientId } from '@/utils/id'
import { fetchStocktake, fetchStocktakeDiffs, resolveStocktakeDiff } from '@/utils/api'
import type { StocktakeDiffRow, StocktakeSummary } from '@/utils/api'

/**
 * 盘点差异确认页（/stocktake/:id/diffs，M3-⑥）：close 冻结期望集合后的人工
 * 裁决。CONFIRM=应用 STOCKTAKE_ADJUST（幂等键同库存动作 7.0：失败重试复用
 * 同键）；IGNORE=留痕置位（天然幂等）。冻结品（diffType 4）禁止 CONFIRM 仅可
 * 忽略/线下处理。全部处理完单据自动转已确认 → allDone 横幅。
 */

const PAGE_SIZE = 20
const DIFF_TYPE_LOSS = 1
const DIFF_TYPE_FROZEN = 4
const STATUS_PENDING = 0

const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()

const canAct = computed(() => auth.me != null && auth.me.role <= 2)
const stocktakeId = computed(() => Number(route.params.id))

// ------------------------------------------------------------- 会话摘要

const summary = ref<StocktakeSummary | null>(null)
const summaryFailed = ref(false)

async function loadSummary(): Promise<void> {
  summaryFailed.value = false
  try {
    summary.value = await fetchStocktake(stocktakeId.value)
  } catch {
    summary.value = null
    summaryFailed.value = true
  }
}

const allDone = computed(
  () =>
    summary.value != null &&
    summary.value.status === 2 &&
    (summary.value.pendingDiffCount ?? 0) === 0,
)

// ------------------------------------------------------------- 差异清单（van-list 分页）

const showPendingOnly = ref(true)
const rows = ref<StocktakeDiffRow[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadError = ref(false)
const finished = ref(false)

/** 请求代次：筛选切换后旧响应作废，防竞态写入（stale append）。 */
let requestSeq = 0

async function onLoad(): Promise<void> {
  const seq = ++requestSeq
  try {
    const res = await fetchStocktakeDiffs(
      stocktakeId.value,
      page.value,
      PAGE_SIZE,
      showPendingOnly.value ? STATUS_PENDING : undefined,
    )
    if (seq !== requestSeq) return
    rows.value = page.value === 1 ? res.rows : [...rows.value, ...res.rows]
    total.value = res.total
    page.value += 1
    loadError.value = false
    if (rows.value.length >= res.total || res.rows.length === 0) {
      finished.value = true
    }
  } catch {
    if (seq !== requestSeq) return
    loadError.value = true
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

function resetList(): void {
  rows.value = []
  total.value = 0
  page.value = 1
  finished.value = false
  loadError.value = false
  loading.value = true
  void onLoad()
}

function onFilterChange(pendingOnly: boolean): void {
  if (showPendingOnly.value === pendingOnly) return
  showPendingOnly.value = pendingOnly
  armedId.value = null
  resetList()
}

// ------------------------------------------------------------- 行内两步确认与忽略

/** armed：第一次点击「調整する」后的待执行行（再点「はい」才发请求）。 */
const armedId = ref<number | null>(null)
const busyId = ref<number | null>(null)
const actionError = ref('')

/** CONFIRM 幂等键（7.0）：生成后保留到成功为止，失败重试复用同键。 */
const confirmKeys = new Map<number, string>()

function confirmKeyFor(diffId: number): string {
  const existing = confirmKeys.get(diffId)
  if (existing != null) return existing
  const key = newClientId()
  confirmKeys.set(diffId, key)
  return key
}

/** 差异行幂等读回键（IGNORE 不落流水，键仅随请求透传供审计）。 */
const ignoreKeys = new Map<number, string>()

function ignoreKeyFor(diffId: number): string {
  const existing = ignoreKeys.get(diffId)
  if (existing != null) return existing
  const key = newClientId()
  ignoreKeys.set(diffId, key)
  return key
}

/** 响应行就地替换（不可变更新）+ 待确认计数联动；清零后重读摘要取回自动确认终态。 */
function applyRow(next: StocktakeDiffRow): void {
  rows.value = rows.value.map((row) => (row.id === next.id ? next : row))
  const current = summary.value
  if (current != null && current.pendingDiffCount != null && current.pendingDiffCount > 0) {
    const pendingDiffCount = current.pendingDiffCount - 1
    summary.value = { ...current, pendingDiffCount }
    if (pendingDiffCount === 0) {
      // 最后一条处理完：服务端已把单据自动转已确认——重读摘要驱动 allDone 横幅，
      // 否则本地 status 停在「確認待ち」、终态提示永不出现
      void loadSummary()
    }
  }
}

async function resolveDiff(diff: StocktakeDiffRow, action: 'CONFIRM' | 'IGNORE'): Promise<void> {
  if (busyId.value != null) return
  busyId.value = diff.id
  actionError.value = ''
  try {
    const clientReqId =
      action === 'CONFIRM' ? confirmKeyFor(diff.id) : ignoreKeyFor(diff.id)
    const result = await resolveStocktakeDiff(stocktakeId.value, diff.id, action, clientReqId)
    if (action === 'CONFIRM') {
      confirmKeys.delete(diff.id)
    }
    armedId.value = null
    applyRow(result.diff)
  } catch (error) {
    // 两步按钮复位但键保留：重试同键安全重放（7.0）
    actionError.value = toDisplayMessage(error, t)
  } finally {
    busyId.value = null
  }
}

function hintKey(diff: StocktakeDiffRow): string {
  if (diff.diffType === DIFF_TYPE_LOSS) return 'lossHint'
  if (diff.diffType === 2) return 'gainHint'
  if (diff.diffType === 3) return 'whHint'
  return 'frozenNote'
}

function warehouseLabel(value: number | null): string {
  return value == null ? '—' : t(`common.warehouse.${value}`)
}

/** 会话摘要先行载入；差异列表由 van-list 首次 check() 触发（同 ArrivalView 模式）。 */
void loadSummary()

/**
 * 他端失效重取（SSE）：他人处理差异（STOCKTAKE）→ 待确认数与行状态同步；
 * 差异请求在途时跳过（applyRow 就地更新，重取竞态会回卷行内两步确认）。
 */
useSyncInvalidation(['STOCKTAKE'], () => {
  if (busyId.value != null) return
  armedId.value = null
  void loadSummary()
  resetList()
})
</script>

<template>
  <section class="diff-view">
    <h1 class="diff-title">
      {{ t('stocktake.diff.title') }}
    </h1>

    <div
      v-if="allDone"
      class="diff-alldone"
      role="status"
    >
      {{ t('stocktake.diff.allDone') }}
    </div>

    <div
      v-if="summaryFailed"
      class="kcgl-info-box"
    >
      {{ t('stocktake.loadFailed') }}
    </div>
    <p
      v-else-if="summary != null && summary.pendingDiffCount != null"
      class="diff-pending-count"
    >
      {{ t('stocktake.pendingDiff', { n: summary.pendingDiffCount }) }}
    </p>

    <div
      class="diff-filter"
      role="group"
      :aria-label="t('stocktake.diff.filterAll')"
    >
      <button
        type="button"
        class="diff-filter-option"
        :class="{ 'is-active': showPendingOnly }"
        :aria-pressed="showPendingOnly"
        @click="onFilterChange(true)"
      >
        {{ t('stocktake.diff.filterPending') }}
      </button>
      <button
        type="button"
        class="diff-filter-option"
        :class="{ 'is-active': !showPendingOnly }"
        :aria-pressed="!showPendingOnly"
        @click="onFilterChange(false)"
      >
        {{ t('stocktake.diff.filterAll') }}
      </button>
    </div>

    <p
      v-if="actionError"
      class="diff-action-error"
      role="alert"
    >
      {{ actionError }}
    </p>

    <van-list
      v-model:loading="loading"
      v-model:error="loadError"
      :finished="finished"
      :loading-text="t('common.loading')"
      :error-text="t('stocktake.loadFailed')"
      @load="onLoad"
    >
      <div
        v-if="finished && rows.length === 0"
        class="diff-empty"
      >
        {{ t('stocktake.diff.empty') }}
      </div>
      <div
        v-for="row in rows"
        :key="row.id"
        class="diff-row"
        :class="{ 'is-pending': row.confirmStatus === 0 }"
      >
        <div class="diff-row-head">
          <span class="diff-thumb">
            <img
              v-if="row.thumbUrl"
              :src="row.thumbUrl"
              alt=""
              loading="lazy"
            >
          </span>
          <div class="diff-row-main">
            <p class="diff-code">
              {{ row.itemCode }}
            </p>
            <p class="diff-type-line">
              <span
                class="diff-type"
                :class="`is-type-${row.diffType}`"
              >{{ t(`stocktake.diff.type.${row.diffType}`) }}</span>
              <span
                v-if="row.confirmStatus !== 0"
                class="diff-confirm-status"
                :class="{ 'is-ignored': row.confirmStatus === 2 }"
              >{{ row.confirmStatus === 1 ? t('stocktake.diff.adjusted') : t('stocktake.diff.ignored') }}</span>
            </p>
            <p
              v-if="row.diffType === 3"
              class="diff-wh"
            >
              {{
                t('stocktake.diff.whMove', {
                  from: warehouseLabel(row.expectedWarehouse),
                  to: warehouseLabel(row.actualWarehouse),
                })
              }}
            </p>
          </div>
        </div>
        <p
          v-if="row.confirmStatus === 0"
          class="diff-hint"
        >
          {{ t(`stocktake.diff.${hintKey(row)}`) }}
        </p>
        <p
          v-if="row.note != null && row.note !== ''"
          class="diff-note-changed"
        >
          {{ t('stocktake.diff.noteChanged') }}
        </p>
        <div
          v-if="canAct && row.confirmStatus === 0"
          class="diff-actions"
        >
          <template v-if="armedId === row.id">
            <span class="diff-armed-label">{{ t('stocktake.diff.confirmSure') }}</span>
            <button
              type="button"
              class="kcgl-btn diff-no-btn"
              :disabled="busyId != null"
              @click="armedId = null"
            >
              {{ t('stocktake.diff.no') }}
            </button>
            <button
              type="button"
              class="kcgl-btn kcgl-btn-primary"
              :disabled="busyId != null"
              @click="resolveDiff(row, 'CONFIRM')"
            >
              {{ t('stocktake.diff.yes') }}
            </button>
          </template>
          <template v-else>
            <button
              v-if="row.diffType !== DIFF_TYPE_FROZEN"
              type="button"
              class="kcgl-btn kcgl-btn-primary diff-yes-btn"
              :disabled="busyId != null"
              @click="armedId = row.id"
            >
              {{ t('stocktake.diff.confirm') }}
            </button>
            <button
              type="button"
              class="kcgl-btn diff-ignore-btn"
              :disabled="busyId != null"
              @click="resolveDiff(row, 'IGNORE')"
            >
              {{ t('stocktake.diff.ignore') }}
            </button>
          </template>
        </div>
      </div>
    </van-list>
  </section>
</template>

<style scoped>
.diff-view {
  max-width: 560px;
  margin: 0 auto;
  display: grid;
  gap: 12px;
}

.diff-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.diff-alldone {
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-success-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
  font-size: 0.9rem;
  font-weight: 600;
}

.diff-pending-count {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.diff-filter {
  display: flex;
  gap: 8px;
}

.diff-filter-option {
  flex: 1;
  min-height: 40px;
  padding: 4px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.85rem;
  cursor: pointer;
}

.diff-filter-option.is-active {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.diff-action-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
}

.diff-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--kcgl-color-text-faint);
  font-size: 0.9rem;
}

.diff-row {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-card);
  box-shadow: var(--kcgl-shadow-card);
}

.diff-row + .diff-row {
  margin-top: 8px;
}

.diff-row.is-pending {
  border-left: 3px solid var(--kcgl-color-warning);
}

.diff-row-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.diff-thumb {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.diff-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.diff-row-main {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 4px;
}

.diff-code {
  margin: 0;
  font-size: 0.98rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  word-break: break-all;
}

.diff-type-line {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.diff-type {
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.diff-type.is-type-1 {
  border-color: var(--kcgl-color-danger-border);
  color: var(--kcgl-color-danger);
}

.diff-type.is-type-2 {
  border-color: var(--kcgl-color-success-border);
  color: var(--kcgl-color-success);
}

.diff-type.is-type-3 {
  border-color: var(--kcgl-color-info-border);
  color: var(--kcgl-color-primary);
}

.diff-type.is-type-4 {
  border-color: var(--kcgl-color-warning-border);
  color: var(--kcgl-color-warning);
}

.diff-confirm-status {
  font-size: 0.75rem;
  color: var(--kcgl-color-success);
}

.diff-confirm-status.is-ignored {
  color: var(--kcgl-color-text-faint);
}

.diff-wh {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.diff-hint {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.diff-note-changed {
  margin: 0;
  padding: 6px 10px;
  border: 1px solid var(--kcgl-color-warning-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
  font-size: 0.78rem;
}

.diff-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.diff-armed-label {
  margin-right: auto;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--kcgl-color-danger);
}

.diff-yes-btn,
.diff-no-btn,
.diff-ignore-btn {
  min-width: 88px;
}

.diff-no-btn,
.diff-ignore-btn {
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-weight: 500;
}
</style>
