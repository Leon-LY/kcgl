<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import { dayjs, formatJstDate, JST_TZ } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { newClientId } from '@/utils/id'
import { confirmArrivals, fetchPendingArrivals } from '@/utils/api'
import type { ConfirmArrivalLine, PendingArrival } from '@/utils/api'

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

/**
 * 到仓改仓/上架货架（A7）：按 itemId 记行级覆盖，**只加不隐**——没记的行沿用
 * 录入时的预计仓库（服务端 warehouse 缺省即原值、shelfNo 缺省即不改）。
 * 货直送仓库、标签还在办公室的路径靠这里一次落对仓，省掉入库后再调拨一趟。
 */
const warehouseOverrides = ref<Record<number, number>>({})
const shelfOverrides = ref<Record<number, string>>({})

/** 已选商品行（弹层逐行给改仓/货架输入；选中顺序即清单顺序）。 */
const selectedRows = computed(() =>
  selectedIds.value
    .map((id) => items.value.find((row) => row.id === id))
    .filter((row): row is PendingArrival => row != null),
)

function setWarehouse(itemId: number, warehouse: number | null): void {
  const next = { ...warehouseOverrides.value }
  if (warehouse == null) {
    // 点回「予定どおり」= 删键而非写回原值：只有「没记过的行」才不上报仓库，
    // 写回原值会让服务端分不清「用户确认过」与「用户没看」（见 D-116）
    delete next[itemId]
  } else {
    next[itemId] = warehouse
  }
  warehouseOverrides.value = next
}

function setShelf(itemId: number, shelfNo: string): void {
  shelfOverrides.value = { ...shelfOverrides.value, [itemId]: shelfNo }
}

function openDialog(): void {
  if (selectedCount.value === 0) return
  inDate.value = ''
  confirmError.value = ''
  warehouseOverrides.value = {}
  shelfOverrides.value = {}
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
  const lines = selectedIds.value.map((itemId) => {
    const line: ConfirmArrivalLine = { itemId, clientReqId: clientKeyFor(itemId) }
    const warehouse = warehouseOverrides.value[itemId]
    if (warehouse != null) {
      line.warehouse = warehouse
    }
    const shelfNo = shelfOverrides.value[itemId]?.trim()
    if (shelfNo != null && shelfNo !== '') {
      line.shelfNo = shelfNo
    }
    return line
  })
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

          <div class="kcgl-field">
            <p class="kcgl-label">
              {{ t('arrival.overrideTitle') }}
            </p>
            <ul class="arrival-override-list">
              <li
                v-for="row in selectedRows"
                :key="row.id"
                class="arrival-override-row"
              >
                <span class="arrival-override-code">{{ row.itemCode }}</span>
                <div
                  class="arrival-override-wh"
                  role="group"
                  :aria-label="t('arrival.changeWarehouse')"
                >
                  <button
                    type="button"
                    class="arrival-override-wh-option"
                    :class="{ 'is-active': warehouseOverrides[row.id] == null }"
                    :aria-pressed="warehouseOverrides[row.id] == null"
                    :disabled="confirming"
                    @click="setWarehouse(row.id, null)"
                  >
                    {{ t('arrival.keepPlanned', { wh: t(`common.warehouse.${row.warehouse}`) }) }}
                  </button>
                  <button
                    v-for="wh in [1, 2]"
                    :key="wh"
                    type="button"
                    class="arrival-override-wh-option"
                    :class="{ 'is-active': warehouseOverrides[row.id] === wh }"
                    :aria-pressed="warehouseOverrides[row.id] === wh"
                    :disabled="confirming"
                    @click="setWarehouse(row.id, wh)"
                  >
                    {{ t(`common.warehouse.${wh}`) }}
                  </button>
                </div>
                <input
                  class="kcgl-input arrival-override-shelf"
                  type="text"
                  maxlength="20"
                  :value="shelfOverrides[row.id] ?? ''"
                  :placeholder="t('arrival.shelfNo')"
                  :aria-label="t('arrival.shelfNo')"
                  :disabled="confirming"
                  @input="setShelf(row.id, ($event.target as HTMLInputElement).value)"
                >
              </li>
            </ul>
            <p class="arrival-dialog-hint">
              {{ t('arrival.overrideHint') }}
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
  font-size: 0.9rem;
  color: var(--kcgl-color-text-faint);
}

/* 到仓改仓（A7）：批量选中可能几十行，列表自身滚动，弹层不顶破视口 */
.arrival-override-list {
  max-height: 40vh;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch; /* iOS 保持惯性滚动 */
  overscroll-behavior: contain; /* 内滚到底不回弹传染给背后的页面 */
  list-style: none;
  display: grid;
  gap: 10px;
}

.arrival-override-row {
  display: grid;
  gap: 6px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--kcgl-color-border);
}

.arrival-override-row:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.arrival-override-code {
  font-size: 0.9rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.arrival-override-wh {
  display: flex;
  gap: 6px;
}

/* 改仓选项承载日文最长文案「予定どおり（第１倉庫）」：0.75→0.9rem、行高
   1.3→1.5，min-height 34→44px 直接抬高视觉盒。这里不做不可见热区——三片
   同排、行间仅 6px，外扩热区会互相盖住导致误点；弹层清单本就自滚，加高不挤 */
.arrival-override-wh-option {
  flex: 1;
  min-height: 44px;
  padding: 4px 6px;
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

.arrival-override-wh-option.is-active {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 同筛选片：按下态置于 .is-active 之后，选中项再按也有反馈 */
.arrival-override-wh-option:active:not(:disabled) {
  background: var(--kcgl-color-fill);
  border-color: var(--kcgl-color-border-strong);
}

/* 货架输入：输入值是内容，0.85→0.9rem；min-height 补到 44px 与触控下限一致
   （.kcgl-input 基元本就有 height:44px，此前的 36px 是个不起作用的旧值） */
.arrival-override-shelf {
  min-height: 44px;
  font-size: 0.9rem;
}

.arrival-dialog-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.9rem;
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
  transition: opacity var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.arrival-fade-enter-from,
.arrival-fade-leave-to {
  opacity: 0;
}

/* 手机档（<600px）：改仓三项一行放不下——「予定どおり（名古屋倉庫）」是最长的
   一片（12 字 ≈ 190px），挤在一行里三片各自折行反而更乱。改仓这一组本来就是
   「沿用默认」+「两个具体仓库」的语义，按语义分组竖排：
   第一片（沿用）独占一行，后两片两两并排。
   （仓库筛选那三片是「すべて／名古屋／福岡」，最窄机型 320px 下也只需 272px，
   一行放得下，保持单行不折。） */
@media (max-width: 599px) {
  .arrival-override-wh {
    flex-wrap: wrap;
  }

  .arrival-override-wh-option:first-child {
    flex: 1 1 100%;
  }

  .arrival-override-wh-option:not(:first-child) {
    flex: 1 1 calc(50% - 3px);
  }
}
</style>
