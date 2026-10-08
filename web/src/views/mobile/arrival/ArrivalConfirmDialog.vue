<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { dayjs, JST_TZ } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { newClientId } from '@/utils/id'
import { confirmArrivals } from '@/utils/api'
import type { ConfirmArrivalLine, PendingArrival } from '@/utils/api'

/**
 * 到货核对的确认弹层（M2-8a）：从 ArrivalView 抽出来的（D-153）。本件自持开合、
 * 入库日、行级改仓/货架覆盖、提交中的状态、错误文案与 Esc 监听，以及每件的
 * clientReqId（幂等契约 docs/01 7.0：失败重试复用同键，成功才清）。
 * 选区（选了哪几件）在页面——本件只拿它算好的那份行清单，对外全是单向回报：
 * 成功报件数（页面清选择、亮横幅、重取清单）、出错后关闭时报一声（页面同样清
 * 选择重取），以及「请求在途」的状态回报（页面 SSE 失效重取要据此让路）。
 * 模板与样式逐字来自原页面，类名与 id 一个未改——它们是单测与 e2e 的定位锚点。
 */

const props = defineProps<{
  /** 已选商品行（顺序即清单顺序）；页面保证非空才调 open()。 */
  selectedRows: PendingArrival[]
}>()

const emit = defineEmits<{
  /** 请求在途（页面 SSE 失效重取据此让路）。 */
  'update:busy': [busy: boolean]
  /** 确认成功：入库件数（页面清选择、亮横幅、重取清单）。 */
  confirmed: [count: number]
  /** 出错后关闭（取消或 Esc）：可能存在请求已提交但响应丢失，页面据此清选择重取。 */
  dismissed: []
}>()

const { t } = useI18n()

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

/** 已选件数（弹层按拿到的行数算；选区本身在页面）。 */
const selectedCount = computed(() => props.selectedRows.length)

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

function open(): void {
  if (props.selectedRows.length === 0) return
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
    // 失败后关闭：清选择与重载清单归页面（本件只报「这么关的」）——可能存在
    // 「请求已提交但响应丢失」的超时场景，页面据此重取反映真实状态（已入库
    // 件从在途清单消失；再确认由同键重放兜底）。
    emit('dismissed')
  }
}

async function onConfirm(): Promise<void> {
  if (confirming.value) return
  confirming.value = true
  confirmError.value = ''
  const lines = props.selectedRows.map(({ id: itemId }) => {
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
    dialogOpen.value = false
    emit('confirmed', result.arrivedCount)
  } catch (error) {
    // 弹层保持打开显示错误：直接重试同键即可安全重放（7.0），无需重选
    confirmError.value = toDisplayMessage(error, t)
  } finally {
    confirming.value = false
  }
}

/** 「请求在途」回报页面：SSE 失效重取要在确认途中让路（原页面的 confirming 就取这个信号）。 */
watch(confirming, (current) => {
  emit('update:busy', current)
})

/** Esc 关闭确认弹层（桌面键盘友好；确认中不响应）。 */
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && dialogOpen.value) {
    closeDialog()
  }
}

// 常驻键监听（与拆分前一致）：弹层没开不做事，不必随开合挂卸
onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

defineExpose({ open })
</script>

<template>
  <Transition name="kcgl-sheet">
    <div
      v-if="dialogOpen"
      class="kcgl-sheet-overlay arrival-overlay"
    >
      <div
        class="kcgl-card kcgl-sheet arrival-dialog"
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
</template>

<style scoped>
/* 弹层的几何与升起动效由共用基元给（brand.css ⑨ .kcgl-sheet-overlay /
   .kcgl-sheet）。.arrival-overlay / .arrival-dialog 这两个类名留在标签上只是给测试定位用
   （e2e 与单测按它取弹层），本身不再压样式。 */

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

/* 到仓改仓（A7）：批量选中可能几十行，列表自身滚动（面板整体已封顶 85dvh，
   这里再封一层是为了让标题与"确定"按钮始终露在屏幕上，不跟着列表滚走）。
   dvh 而非 vh：iOS 上 vh 含地址栏高度，40vh 能比真视口高出一截。 */
.arrival-override-list {
  max-height: 40dvh;
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
