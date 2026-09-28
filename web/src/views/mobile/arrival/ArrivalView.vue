<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import { dayjs, formatJstDate, JST_TZ } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { newClientId } from '@/utils/id'
import { confirmArrivals, fetchPendingArrivals } from '@/utils/api'
import type { PendingArrival } from '@/utils/api'

/**
 * 到货核对页（/arrival，M2-8a）：按预计仓库筛选在途件，卡片点选 → 底部
 * 操作条批量确认入库（同批全成全败）。清单全员可看（viewer 只读+提示条），
 * 确认仅编辑者以上（服务端 403 兜底）。幂等契约（docs/01 7.0）：每件一个
 * clientReqId，失败重试复用同键（服务端读回原结果 200 出清），成功后清除。
 */

const PAGE_SIZE = 20
const DONE_BANNER_MS = 4000
const FILTER_OPTIONS: (number | null)[] = [null, 1, 2]

const { t } = useI18n()
const auth = useAuthStore()

const canConfirm = computed(() => auth.me != null && auth.me.role <= 2)

// ------------------------------------------------------------- 在途清单（van-list 分页）

const filter = ref<number | null>(null)
const items = ref<PendingArrival[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadError = ref(false)
const finished = ref(false)
/** 首屏计数占位：首次成功加载前显示 loading 文案，避免「0 件」闪跳。 */
const loadedOnce = ref(false)

/** 请求代次：筛选切换后旧响应作废，防止竞态写入（stale append）。 */
let requestSeq = 0

async function onLoad(): Promise<void> {
  const seq = ++requestSeq
  try {
    const res = await fetchPendingArrivals(filter.value, page.value, PAGE_SIZE)
    if (seq !== requestSeq) return
    items.value = page.value === 1 ? res.rows : [...items.value, ...res.rows]
    total.value = res.total
    page.value += 1
    loadError.value = false
    loadedOnce.value = true
    if (items.value.length >= res.total || res.rows.length === 0) {
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

/** 重置回第 1 页（筛选切换/入库成功/失败关闭后刷新）。 */
function resetList(): void {
  items.value = []
  total.value = 0
  page.value = 1
  finished.value = false
  loadError.value = false
  loading.value = true
  void onLoad()
}

function filterLabel(value: number | null): string {
  return value == null ? t('arrival.filterAll') : t(`common.warehouse.${value}`)
}

function onFilterChange(value: number | null): void {
  if (filter.value === value) return
  filter.value = value
  selectedIds.value = []
  resetList()
}

// ------------------------------------------------------------- 选择与确认

const selectedIds = ref<number[]>([])
const selectedCount = computed(() => selectedIds.value.length)

function isSelected(id: number): boolean {
  return selectedIds.value.includes(id)
}

function toggle(id: number): void {
  if (!canConfirm.value) return
  selectedIds.value = isSelected(id)
    ? selectedIds.value.filter((x) => x !== id)
    : [...selectedIds.value, id]
}

/** 行级幂等键：itemId → clientReqId。生成后保留到该件成功为止（失败重试复用同键）。 */
const idempotencyKeys = new Map<number, string>()

function clientKeyFor(id: number): string {
  const existing = idempotencyKeys.get(id)
  if (existing != null) {
    return existing
  }
  const key = newClientId()
  idempotencyKeys.set(id, key)
  return key
}

const dialogOpen = ref(false)
const inDate = ref('')
const confirming = ref(false)
const confirmError = ref('')
/** 入库日上限=今天 JST（未来日服务端 400 拒绝，前端先行钳制）。 */
const todayInput = dayjs().tz(JST_TZ).format('YYYY-MM-DD')

function openDialog(): void {
  if (selectedCount.value === 0) return
  inDate.value = ''
  confirmError.value = ''
  dialogOpen.value = true
}

function closeDialog(): void {
  if (confirming.value) return
  dialogOpen.value = false
  if (confirmError.value !== '') {
    // 失败后关闭：可能存在「请求已提交但响应丢失」的超时场景 → 清选择并
    // 重载清单反映真实状态（已入库件从在途清单消失；再确认由同键重放兜底）
    selectedIds.value = []
    resetList()
  }
}

async function onConfirm(): Promise<void> {
  if (confirming.value) return
  confirming.value = true
  confirmError.value = ''
  const lines = selectedIds.value.map((itemId) => ({
    itemId,
    clientReqId: clientKeyFor(itemId),
  }))
  try {
    const result = await confirmArrivals(
      lines,
      inDate.value === '' ? undefined : inDate.value,
    )
    for (const line of lines) {
      idempotencyKeys.delete(line.itemId)
    }
    selectedIds.value = []
    dialogOpen.value = false
    showDoneBanner(result.arrivedCount)
    resetList()
  } catch (error) {
    // 弹层保持打开显示错误：直接重试同键即可安全重放（7.0），无需重选
    confirmError.value = toDisplayMessage(error, t)
  } finally {
    confirming.value = false
  }
}

/**
 * 他端失效重取（SSE）：ITEM/INVENTORY 事件=在途集合可能已变（他人录入/
 * 他端入库）。选择一并清空——被他人先入库的已选项会让本批确认整体 409，
 * 与其报错不如基于新清单重点一次。IMAGE=他人照片数秒后异步补传，重取后
 * 无图卡补上缩略图。
 */
useSyncInvalidation(['ITEM', 'INVENTORY', 'IMAGE'], () => {
  if (confirming.value) return // 确认请求在途时不打断（响应后本就 resetList）
  selectedIds.value = []
  resetList()
})

// ------------------------------------------------------------- 成功横幅与键盘收尾

const doneBanner = ref('')
let doneTimer = 0

function showDoneBanner(count: number): void {
  doneBanner.value = t('arrival.done', { n: count })
  if (doneTimer !== 0) window.clearTimeout(doneTimer)
  doneTimer = window.setTimeout(() => {
    doneBanner.value = ''
  }, DONE_BANNER_MS)
}

/** Esc 关闭确认弹层（桌面键盘友好；确认中不响应）。 */
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && dialogOpen.value) {
    closeDialog()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (doneTimer !== 0) window.clearTimeout(doneTimer)
})
</script>

<template>
  <section
    class="arrival-view"
    :class="{ 'has-actionbar': canConfirm }"
  >
    <h1 class="arrival-title">
      {{ t('arrival.title') }}
    </h1>

    <div class="kcgl-card arrival-toolbar">
      <p class="arrival-count">
        {{ loadedOnce ? t('arrival.pendingCount', { n: total }) : t('common.loading') }}
      </p>
      <div
        class="arrival-filter"
        role="group"
        :aria-label="t('arrival.filterLabel')"
      >
        <button
          v-for="option in FILTER_OPTIONS"
          :key="String(option)"
          type="button"
          class="arrival-filter-option"
          :class="{ 'is-active': filter === option }"
          :aria-pressed="filter === option"
          @click="onFilterChange(option)"
        >
          {{ filterLabel(option) }}
        </button>
      </div>
    </div>

    <div
      v-if="doneBanner"
      class="arrival-done"
      role="status"
    >
      {{ doneBanner }}
    </div>

    <div
      v-if="!canConfirm"
      class="kcgl-info-box"
    >
      {{ t('arrival.viewerNote') }}
    </div>

    <van-list
      v-model:loading="loading"
      v-model:error="loadError"
      :finished="finished"
      :finished-text="items.length === 0 ? '' : t('arrival.listEnd')"
      :loading-text="t('common.loading')"
      :error-text="t('arrival.loadFailed')"
      @load="onLoad"
    >
      <div
        v-if="finished && items.length === 0"
        class="arrival-empty"
      >
        {{ t('arrival.empty') }}
      </div>
      <button
        v-for="row in items"
        :key="row.id"
        type="button"
        class="arrival-card"
        :class="{ 'is-selected': canConfirm && isSelected(row.id) }"
        :aria-pressed="canConfirm ? isSelected(row.id) : undefined"
        :disabled="!canConfirm"
        @click="toggle(row.id)"
      >
        <span class="arrival-thumb">
          <img
            v-if="row.thumbUrl"
            :src="row.thumbUrl"
            alt=""
            loading="lazy"
          >
        </span>
        <span class="arrival-body">
          <span class="arrival-code">{{ row.itemCode }}</span>
          <span class="arrival-date">{{ t('arrival.buyDate', { date: formatJstDate(row.buyDate) }) }}</span>
        </span>
        <span class="arrival-wh">{{ t(`common.warehouse.${row.warehouse}`) }}</span>
        <span
          v-if="canConfirm"
          class="arrival-check"
          aria-hidden="true"
        />
      </button>
    </van-list>

    <div
      v-if="canConfirm"
      class="arrival-actionbar"
    >
      <div class="arrival-actionbar-inner">
        <button
          type="button"
          class="kcgl-btn kcgl-btn-primary kcgl-btn-block"
          :disabled="selectedCount === 0"
          @click="openDialog"
        >
          {{ t('arrival.confirmButton', { n: selectedCount }) }}
        </button>
      </div>
    </div>

    <Transition name="arrival-fade">
      <div
        v-if="dialogOpen"
        class="arrival-overlay"
      >
        <div
          class="kcgl-card arrival-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="arrival-dialog-title"
        >
          <h2
            id="arrival-dialog-title"
            class="arrival-dialog-title"
          >
            {{ t('arrival.confirmTitle') }}
          </h2>
          <p class="arrival-dialog-count">
            {{ t('arrival.confirmCount', { n: selectedCount }) }}
          </p>
          <div class="kcgl-field">
            <label
              class="kcgl-label"
              for="arrival-in-date"
            >
              {{ t('arrival.warehouseInDate') }}
            </label>
            <input
              id="arrival-in-date"
              v-model="inDate"
              class="kcgl-input"
              type="date"
              :max="todayInput"
              :disabled="confirming"
            >
            <p class="arrival-dialog-hint">
              {{ t('arrival.warehouseInDateHint') }}
            </p>
          </div>
          <p
            v-if="confirmError"
            class="arrival-dialog-error"
            role="alert"
          >
            {{ confirmError }}
          </p>
          <div class="arrival-dialog-actions">
            <button
              type="button"
              class="kcgl-btn arrival-dialog-cancel"
              :disabled="confirming"
              @click="closeDialog"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="kcgl-btn kcgl-btn-primary arrival-dialog-ok"
              :disabled="confirming"
              @click="onConfirm"
            >
              {{ confirming ? t('arrival.confirming') : t('arrival.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </section>
</template>

<style scoped>
.arrival-view {
  max-width: 560px;
  margin: 0 auto;
  display: grid;
  gap: 12px;
}

.arrival-view.has-actionbar {
  /* 底部固定操作条（44px 按钮 + 上下留白 + 安全区）不遮末行卡片 */
  padding-bottom: calc(76px + env(safe-area-inset-bottom));
}

.arrival-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.arrival-toolbar {
  display: grid;
  gap: 12px;
  padding: 16px;
}

.arrival-count {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.arrival-filter {
  display: flex;
  gap: 8px;
}

.arrival-filter-option {
  flex: 1;
  min-height: 40px;
  padding: 4px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.85rem;
  line-height: 1.4;
  cursor: pointer;
}

.arrival-filter-option.is-active {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.arrival-done {
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-success-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
  font-size: 0.9rem;
  font-weight: 600;
}

.arrival-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--kcgl-color-text-faint);
  font-size: 0.9rem;
}

.arrival-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-card);
  box-shadow: var(--kcgl-shadow-card);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.arrival-card + .arrival-card {
  margin-top: 8px;
}

.arrival-card:disabled {
  cursor: default;
}

.arrival-card.is-selected {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
}

.arrival-thumb {
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

.arrival-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.arrival-body {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
}

.arrival-code {
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.arrival-date {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.arrival-wh {
  flex-shrink: 0;
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.arrival-card.is-selected .arrival-wh {
  border-color: var(--kcgl-color-info-border);
  color: var(--kcgl-color-primary);
}

.arrival-check {
  position: relative;
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border: 2px solid var(--kcgl-color-border-strong);
  border-radius: 50%;
  background: var(--kcgl-color-card);
}

.arrival-card.is-selected .arrival-check {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary);
}

.arrival-card.is-selected .arrival-check::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 5px;
  height: 9px;
  border: solid #fff;
  border-width: 0 2px 2px 0;
  transform: translate(-50%, -60%) rotate(45deg);
}

.arrival-actionbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: var(--kcgl-color-card);
  border-top: 1px solid var(--kcgl-color-border);
}

.arrival-actionbar-inner {
  max-width: 560px;
  margin: 0 auto;
}

.arrival-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(31, 35, 41, 0.45);
}

.arrival-dialog {
  display: grid;
  gap: 12px;
  width: 100%;
  max-width: 360px;
  padding: 20px;
}

.arrival-dialog-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.arrival-dialog-count {
  margin: 0;
  font-size: 0.95rem;
  color: var(--kcgl-color-text-sub);
}

.arrival-dialog-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.arrival-dialog-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
}

.arrival-dialog-actions {
  display: flex;
  gap: 8px;
}

.arrival-dialog-cancel {
  flex: 1;
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-weight: 500;
}

.arrival-dialog-ok {
  flex: 2;
}

.arrival-fade-enter-active,
.arrival-fade-leave-active {
  transition: opacity 0.15s ease;
}

.arrival-fade-enter-from,
.arrival-fade-leave-to {
  opacity: 0;
}
</style>
