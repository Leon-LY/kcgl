<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { toDisplayMessage } from '@/utils/errors'
import { createStocktake, fetchStocktakes } from '@/utils/api'
import type { StocktakeSummary } from '@/utils/api'
import { ApiError } from '@/utils/api'

/**
 * 盘点首页（/stocktake，M3-⑥，docs/01 7.3）：发起（选仓；同仓已有进行中单
 * → 409009，前端查出该单给直达按钮）+ 历史列表。列表全员可看；发起仅编辑者
 * 以上（服务端 403 兜底）。进行中/待确认单点击进入会话页，已确认/作废单同样
 * 可进（只读摘要）。
 */

const PAGE_SIZE = 20
const WAREHOUSES = [1, 2]

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const canAct = computed(() => auth.me != null && auth.me.role <= 2)

// ------------------------------------------------------------- 发起

const startWarehouse = ref(1)
const starting = ref(false)
/** 409009 时查到的同仓进行中单（前端给直达按钮，免用户在列表里找）。 */
const activeExisting = ref<StocktakeSummary | null>(null)
const startError = ref('')

async function onStart(): Promise<void> {
  if (starting.value) return
  starting.value = true
  startError.value = ''
  activeExisting.value = null
  try {
    const summary = await createStocktake(startWarehouse.value)
    await router.push({ name: 'stocktake-session', params: { id: summary.id } })
  } catch (error) {
    if (error instanceof ApiError && error.code === 409009) {
      // 同仓已有进行中单：查出来给直达入口（查不到时退化为纯提示文案）
      try {
        const active = await fetchStocktakes(1, PAGE_SIZE, 0)
        activeExisting.value = active.rows.find((row) => row.warehouse === startWarehouse.value) ?? null
      } catch {
        activeExisting.value = null
      }
      startError.value = t('stocktake.activeExists')
    } else {
      startError.value = toDisplayMessage(error, t)
    }
  } finally {
    starting.value = false
  }
}

// ------------------------------------------------------------- 历史列表（van-list 分页）

const rows = ref<StocktakeSummary[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadError = ref(false)
const finished = ref(false)

/** 请求代次：发起成功/返回本页时重载，旧响应作废防竞态写入。 */
let requestSeq = 0

async function onLoad(): Promise<void> {
  const seq = ++requestSeq
  try {
    const res = await fetchStocktakes(page.value, PAGE_SIZE)
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

function openSession(id: number): void {
  void router.push({ name: 'stocktake-session', params: { id } })
}

/** 行内计数：进行中=扫描数；close 后=期望/扫描 + 待确认差异。 */
function countText(row: StocktakeSummary): string {
  if (row.status === 0) {
    return t('stocktake.scan.scanned', { n: row.scannedCount })
  }
  const expected = row.expectedCount ?? 0
  const diffs = row.pendingDiffCount ?? 0
  return `${t('stocktake.counts', { expected, scanned: row.scannedCount })}・${t('stocktake.pendingDiff', { n: diffs })}`
}
</script>

<template>
  <section class="stocktake-view">
    <h1 class="stocktake-title">
      {{ t('stocktake.title') }}
    </h1>

    <div
      v-if="canAct"
      class="kcgl-card stocktake-start"
    >
      <h2 class="stocktake-start-title">
        {{ t('stocktake.startTitle') }}
      </h2>
      <span class="kcgl-label">{{ t('stocktake.warehouse') }}</span>
      <div class="stocktake-wh-options">
        <label
          v-for="warehouse in WAREHOUSES"
          :key="warehouse"
          class="stocktake-wh-option"
          :class="{ 'is-active': startWarehouse === warehouse }"
        >
          <input
            v-model="startWarehouse"
            type="radio"
            name="stocktake-start-wh"
            :value="warehouse"
            :disabled="starting"
          >
          {{ t(`common.warehouse.${warehouse}`) }}
        </label>
      </div>
      <p
        v-if="startError"
        class="stocktake-start-error"
        role="alert"
      >
        {{ startError }}
      </p>
      <button
        v-if="activeExisting"
        type="button"
        class="kcgl-btn stocktake-start-open"
        @click="openSession(activeExisting.id)"
      >
        {{ t('stocktake.openActive') }}
      </button>
      <button
        type="button"
        class="kcgl-btn kcgl-btn-primary"
        :disabled="starting"
        @click="onStart"
      >
        {{ starting ? t('stocktake.starting') : t('stocktake.start') }}
      </button>
    </div>
    <p
      v-else
      class="kcgl-info-box"
    >
      {{ t('stocktake.viewerNote') }}
    </p>

    <h2 class="stocktake-history-title">
      {{ t('stocktake.history') }}
    </h2>

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
        class="stocktake-empty"
      >
        {{ t('stocktake.empty') }}
      </div>
      <button
        v-for="row in rows"
        :key="row.id"
        type="button"
        class="stocktake-row"
        @click="openSession(row.id)"
      >
        <span class="stocktake-row-head">
          <span class="stocktake-row-no">{{ row.stocktakeNo }}</span>
          <span
            class="stocktake-row-status"
            :class="`is-status-${row.status}`"
          >{{ t(`stocktake.status.${row.status}`) }}</span>
        </span>
        <span class="stocktake-row-count">{{ countText(row) }}</span>
        <span class="stocktake-row-meta">
          {{ t(`common.warehouse.${row.warehouse}`) }}
          <span class="stocktake-row-sep">｜</span>
          {{ t('stocktake.createdBy', { name: row.createdByName, date: row.createdAt.slice(0, 10) }) }}
        </span>
      </button>
    </van-list>
  </section>
</template>

<style scoped>
.stocktake-view {
  max-width: 560px;
  margin: 0 auto;
  display: grid;
  gap: 12px;
}

.stocktake-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.stocktake-start {
  display: grid;
  gap: 12px;
  padding: 16px;
}

.stocktake-start-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
}

.stocktake-wh-options {
  display: flex;
  gap: 8px;
}

.stocktake-wh-option {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  padding: 4px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
  cursor: pointer;
}

.stocktake-wh-option.is-active {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.stocktake-wh-option input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.stocktake-start-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-warning-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
  font-size: 0.85rem;
  line-height: 1.6;
}

.stocktake-start-open {
  border: 1px solid var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.stocktake-history-title {
  margin: 4px 0 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.stocktake-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--kcgl-color-text-faint);
  font-size: 0.9rem;
}

.stocktake-row {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-card);
  box-shadow: var(--kcgl-shadow-card);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.stocktake-row + .stocktake-row {
  margin-top: 8px;
}

.stocktake-row:hover {
  border-color: var(--kcgl-color-primary);
}

.stocktake-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.stocktake-row-no {
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.stocktake-row-status {
  flex-shrink: 0;
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.stocktake-row-status.is-status-0 {
  border-color: var(--kcgl-color-info-border);
  color: var(--kcgl-color-primary);
}

.stocktake-row-status.is-status-2 {
  border-color: var(--kcgl-color-success-border);
  color: var(--kcgl-color-success);
}

.stocktake-row-count {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.stocktake-row-meta {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.stocktake-row-sep {
  color: var(--kcgl-color-text-faint);
}
</style>
