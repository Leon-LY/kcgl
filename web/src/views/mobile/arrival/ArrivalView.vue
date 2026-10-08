<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import { formatJstDate } from '@/utils/format'
import { fetchPendingArrivals } from '@/utils/api'
import type { PendingArrival } from '@/utils/api'
import ArrivalConfirmDialog from './ArrivalConfirmDialog.vue'

/**
 * 到货核对页（/arrival，M2-8a）：按预计仓库筛选在途件，卡片点选 → 底部
 * 操作条批量确认入库（同批全成全败）。清单全员可看（viewer 只读+提示条），
 * 确认仅编辑者以上（服务端 403 兜底）。确认弹层（入库日、行级改仓/货架、
 * 幂等键与提交差错处理）在 ArrivalConfirmDialog 里（D-153）。幂等契约
 * （docs/01 7.0）：每件一个 clientReqId，失败重试复用同键（服务端读回原结果
 * 200 出清），成功后清除——键跟请求走，故随弹层。
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

// ------------------------------------------------------------- 选择（确认弹层另件）

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

/** 确认弹层（ArrivalConfirmDialog）：本页只留「谁开它」与它回报的两种收尾。 */
const dialogRef = ref<InstanceType<typeof ArrivalConfirmDialog> | null>(null)

/** 底部操作条按钮：交给弹层自己开（选区为空时按钮本就 disabled）。 */
function openConfirmDialog(): void {
  dialogRef.value?.open()
}

/**
 * 请求在途（弹层回报）：本页只在 SSE 失效重取时读它——在途时不打断
 * （响应后弹层本就报 confirmed / dismissed，届时才清选择重取）。
 */
const confirming = ref(false)

/** 已选商品行（选区在页面；弹层只拿这份清单渲染改仓/货架）。 */
const selectedRows = computed(() =>
  selectedIds.value
    .map((id) => items.value.find((row) => row.id === id))
    .filter((row): row is PendingArrival => row != null),
)

/** 确认成功（弹层回报入库件数）：清选择、亮横幅、重取清单。 */
function onArrivalConfirmed(count: number): void {
  selectedIds.value = []
  showDoneBanner(count)
  resetList()
}

/**
 * 弹层在「出错后关闭」时回报（取消或 Esc）：可能存在「请求已提交但响应丢失」的
 * 超时场景 → 清选择并重载清单反映真实状态（已入库件从在途清单消失；再确认由
 * 同键重放兜底）。
 */
function onArrivalDismissed(): void {
  selectedIds.value = []
  resetList()
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

// ------------------------------------------------------------- 成功横幅

const doneBanner = ref('')
let doneTimer = 0

function showDoneBanner(count: number): void {
  doneBanner.value = t('arrival.done', { n: count })
  if (doneTimer !== 0) window.clearTimeout(doneTimer)
  doneTimer = window.setTimeout(() => {
    doneBanner.value = ''
  }, DONE_BANNER_MS)
}

onBeforeUnmount(() => {
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
          @click="openConfirmDialog"
        >
          {{ t('arrival.confirmButton', { n: selectedCount }) }}
        </button>
      </div>
    </div>

    <ArrivalConfirmDialog
      ref="dialogRef"
      :selected-rows="selectedRows"
      @update:busy="confirming = $event"
      @confirmed="onArrivalConfirmed"
      @dismissed="onArrivalDismissed"
    />
  </section>
</template>

<style scoped>
/* 内容列宽由移动壳统一持有（--kcgl-content-width），页面根不再自设 560px——
   那等于在手机上又把版面缩回「PC 窄列」，正是「缩小版 PC」观感的成因之一 */
.arrival-view {
  display: grid;
  gap: 12px;
}

.arrival-view.has-actionbar {
  /* 壳已按底栏（50px + 安全区）预留；这里再加操作条自身高度不遮末行卡片 */
  padding-bottom: 76px;
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

/* 筛选片：文案是仓库名（内容不是微型标签），抬到移动端下限 0.9rem，行高随
   0.9rem 放到 1.5；min-height 40→44px——工具栏单行有富余，直接抬高视觉盒，
   不必做不可见热区（后者在三片相邻时会互相盖住） */
.arrival-filter-option {
  flex: 1;
  min-height: 44px;
  padding: 4px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.9rem;
  line-height: 1.5;
  cursor: pointer;
  transition:
    background-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.arrival-filter-option.is-active {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 触屏无 hover，按下态是唯一反馈：只换底色与描边，不动任何布局属性。放在
   .is-active 之后，保证选中片被再次按下时同样有反馈（同特异性、后者胜出） */
.arrival-filter-option:active {
  background: var(--kcgl-color-fill);
  border-color: var(--kcgl-color-border-strong);
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
  transition:
    background-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 整卡按压：1px 下沉（与 .kcgl-btn-primary:active 同一克制语汇）。触屏无 hover，
   这是选中操作唯一的即时反馈；transform 不触发重排 */
.arrival-card:active:not(:disabled) {
  transform: translateY(1px);
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
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

/* 仓库片：内容是仓库名（业务字段），不是微型标签，0.75→0.9rem */
.arrival-wh {
  flex-shrink: 0;
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.9rem;
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

/* 操作条悬在底部导航之上，不是取代它——早先 bottom:0 直接把壳的 van-tabbar
   盖死（操作条 z-index 20 > 底栏），编辑者选中行后从这页走不掉。
   安全区由底栏吃，这里不再重复加 env()。 */
.arrival-actionbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(var(--kcgl-tabbar-height, 50px) + env(safe-area-inset-bottom));
  z-index: 20;
  padding: 10px 16px;
  background: var(--kcgl-color-card);
  border-top: 1px solid var(--kcgl-color-border);
}

/* 固定操作条横跨视口（left/right:0），内层必须与内容列同宽并居中，否则按钮
   落在壳的内容列之外；限宽改用壳的内容列令牌而非写死 560px */
.arrival-actionbar-inner {
  max-width: var(--kcgl-content-width);
  margin-inline: auto;
}

</style>
