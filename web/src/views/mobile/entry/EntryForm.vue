<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDictsStore } from '@/stores/dicts'
import { useEntrySessionStore } from '@/stores/entrySession'
import { ApiError, createItem, previewItemCode, type ItemResponse } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { JST_TZ, dayjs } from '@/utils/format'
import { normalizeNumericText, parseAmount, trimText } from '@/utils/normalize'

/**
 * 连续录入表单（docs/01 4.3 验收核心页）：
 * - 沿用上一件（A13）：会场/仓库/落札日期/单价——挂载时从会话读，成功后父级重挂载取新值
 * - 两级预览：档位字母本地即时算（零往返）；完整号 300ms 防抖调 preview（≠保留，文案明示）
 * - 幂等（7.0）：clientReqId 一次逻辑保存从生成到成功共用；失败重试复用同键防重复件
 * - IME（7.8）：金额字段仅在 blur 归一化（NFKC 全角→半角）；备注仅 trim
 */

const emit = defineEmits<{ saved: [item: ItemResponse] }>()

const { t } = useI18n()
const dicts = useDictsStore()
const session = useEntrySessionStore()

// ------------------------------------------------------------------ 表单状态

const venueId = ref<number | null>(session.venueId)
const warehouse = ref<number>(session.warehouse)
const buyDate = ref<string>(session.buyDate)
const priceText = ref<string>(session.purchasePrice != null ? String(session.purchasePrice) : '')
const feeText = ref('')
const shippingText = ref('')
const taxText = ref('')
const groupNo = ref('')
const shelfNo = ref('')
const warehouseInDate = ref('')
const remark = ref('')
const showMore = ref(false)

const todayJst = (): string => dayjs().tz(JST_TZ).format('YYYY-MM-DD')

/** 前日角标（L2）：沿用落札日 ≠ 今天且今日已有保存——多日拍卖会防第二日进错月桶。 */
const carryPrevDay = computed(() => buyDate.value !== todayJst() && session.todayCount > 0)

const priceValue = computed(() => parseAmount(priceText.value))
/** 档位字母：本地档位表即时算（与服务端同语义：左闭右开）。 */
const band = computed(() => dicts.matchBand(priceValue.value))

function normalizePriceOnBlur(): void {
  priceText.value = normalizeNumericText(priceText.value)
}

// ------------------------------------------------------------------ 会场/日期选择

const showVenuePicker = ref(false)
const venueColumns = computed(() =>
  dicts.enabledVenues.map((venue) => ({ text: `${venue.name}（${venue.code}）`, value: venue.id })),
)
const venueDisplay = computed(
  () => dicts.enabledVenues.find((venue) => venue.id === venueId.value)?.name ?? '',
)

function onVenueConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  venueId.value = Number(selectedValues[0])
  showVenuePicker.value = false
}

// 年代号种子自 2016 起（A2）；Vant 日历边界取本地语义的日历日（列渲染读本地 Y/M/D）
const MIN_DATE = dayjs('2016-01-01').toDate()
/** 落札日禁未来（JST 日界）：max 取 JST 今日 23:59，+08 深夜开发场景下默认值与选择上限一致。 */
const buyDateMax = dayjs().tz(JST_TZ).endOf('day').toDate()
/** 入库日允许未来（预填则到仓扫码不覆盖）。 */
const inDateMax = dayjs().tz(JST_TZ).add(1, 'year').endOf('day').toDate()

const showBuyDatePicker = ref(false)
const showInDatePicker = ref(false)

const pad2 = (value: string): string => String(Number(value)).padStart(2, '0')

function toPickerValues(date: string): string[] {
  return date ? [date.slice(0, 4), pad2(date.slice(5, 7)), pad2(date.slice(8, 10))] : []
}

function fromPickerValues(values: Array<string | number>): string {
  return `${values[0]}-${pad2(String(values[1]))}-${pad2(String(values[2]))}`
}

function onBuyDateConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  buyDate.value = fromPickerValues(selectedValues)
  showBuyDatePicker.value = false
}

function onInDateConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  warehouseInDate.value = fromPickerValues(selectedValues)
  showInDatePicker.value = false
}

const buyDateDisplay = computed(() => buyDate.value.replaceAll('-', '/'))

// ------------------------------------------------------------------ 管理号预览

const PREVIEW_DEBOUNCE_MS = 300

const previewCode = ref<string | null>(null)
const previewError = ref<string | null>(null)
const previewLoading = ref(false)
let previewTimer: ReturnType<typeof setTimeout> | null = null
let previewSeq = 0

const previewable = computed(
  () =>
    venueId.value != null &&
    buyDate.value !== '' &&
    priceValue.value != null &&
    priceValue.value >= 1 &&
    band.value != null,
)

watch([venueId, buyDate, priceValue], () => {
  if (previewTimer != null) {
    clearTimeout(previewTimer)
  }
  previewCode.value = null
  previewError.value = null
  if (!previewable.value) {
    return
  }
  previewTimer = setTimeout(runPreview, PREVIEW_DEBOUNCE_MS)
})

async function runPreview(): Promise<void> {
  if (!previewable.value || venueId.value == null || priceValue.value == null) {
    return
  }
  const seq = ++previewSeq
  previewLoading.value = true
  try {
    const result = await previewItemCode(venueId.value, buyDate.value, priceValue.value)
    if (seq !== previewSeq) {
      return // 已有更新的输入，丢弃过期响应
    }
    previewCode.value = result.code
  } catch (error) {
    if (seq !== previewSeq) {
      return
    }
    previewError.value = toDisplayMessage(error, t)
  } finally {
    if (seq === previewSeq) {
      previewLoading.value = false
    }
  }
}

onBeforeUnmount(() => {
  if (previewTimer != null) {
    clearTimeout(previewTimer)
  }
})

// ------------------------------------------------------------------ 校验与提交

const venueRules = [{ required: true, message: t('entry.validation.venueRequired') }]
const priceRules = [
  { required: true, message: t('entry.validation.priceRequired') },
  { validator: (value: string) => (parseAmount(value) ?? 0) >= 1, message: t('entry.validation.priceMin') },
]

const warehouseOptions = [1, 2]

let clientReqId = ''

function newReqId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `r-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

const submitting = ref(false)
const saveError = ref<string | null>(null)
const saveErrorId = ref<string | null>(null)

async function onSubmit(): Promise<void> {
  if (submitting.value) {
    return
  }
  const price = priceValue.value
  if (venueId.value == null || price == null || price < 1) {
    return // van-form 规则已拦，此为类型收窄兜底
  }
  if (!clientReqId) {
    clientReqId = newReqId()
  }
  submitting.value = true
  saveError.value = null
  saveErrorId.value = null
  try {
    const item = await createItem({
      clientReqId,
      venueId: venueId.value,
      buyDate: buyDate.value,
      purchasePrice: price,
      warehouse: warehouse.value,
      fee: parseAmount(feeText.value) ?? undefined,
      shippingFee: parseAmount(shippingText.value) ?? undefined,
      tax: parseAmount(taxText.value) ?? undefined,
      shelfNo: trimText(shelfNo.value) || undefined,
      warehouseInDate: warehouseInDate.value || undefined,
      groupNo: trimText(groupNo.value) || undefined,
      remark: trimText(remark.value) || undefined,
    })
    clientReqId = '' // 成功出清：下一件用新键
    emit('saved', item)
  } catch (error) {
    // 失败保留 clientReqId：「同じ内容で再送信」复用同键（7.0 防重复件）
    saveError.value = toDisplayMessage(error, t)
    saveErrorId.value = error instanceof ApiError ? (error.errorId ?? null) : null
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <van-form
    class="entry-form"
    @submit="onSubmit"
  >
    <van-cell-group inset>
      <van-field
        :model-value="venueDisplay"
        :label="t('entry.venue')"
        :placeholder="t('entry.venuePlaceholder')"
        :rules="venueRules"
        readonly
        is-link
        name="venue"
        @click="showVenuePicker = true"
      />
      <van-popup
        v-model:show="showVenuePicker"
        position="bottom"
        round
      >
        <van-picker
          :columns="venueColumns"
          :title="t('entry.venue')"
          @confirm="onVenueConfirm"
          @cancel="showVenuePicker = false"
        />
      </van-popup>

      <van-field
        :model-value="buyDateDisplay"
        :label="t('entry.buyDate')"
        readonly
        is-link
        name="buyDate"
        @click="showBuyDatePicker = true"
      >
        <template #extra>
          <span
            v-if="carryPrevDay"
            class="entry-prev-day"
          >{{ t('entry.carryPrevDay') }}</span>
        </template>
      </van-field>
      <van-popup
        v-model:show="showBuyDatePicker"
        position="bottom"
        round
      >
        <van-date-picker
          :model-value="toPickerValues(buyDate)"
          :min-date="MIN_DATE"
          :max-date="buyDateMax"
          :title="t('entry.buyDate')"
          @confirm="onBuyDateConfirm"
          @cancel="showBuyDatePicker = false"
        />
      </van-popup>

      <van-field
        v-model="priceText"
        :label="t('entry.purchasePrice')"
        :placeholder="t('entry.purchasePriceUnit')"
        :rules="priceRules"
        type="text"
        inputmode="numeric"
        name="purchasePrice"
        @blur="normalizePriceOnBlur"
      >
        <template #right-icon>
          <span
            v-if="band"
            class="entry-band"
          >{{ t('entry.band', { code: band.code }) }}</span>
          <span
            v-else-if="priceValue != null && priceValue > 0"
            class="entry-band entry-band-miss"
          >{{ t('entry.noBand') }}</span>
        </template>
      </van-field>

      <van-field
        :label="t('entry.warehouse')"
        name="warehouse"
      >
        <template #input>
          <div class="entry-warehouse">
            <button
              v-for="option in warehouseOptions"
              :key="option"
              type="button"
              class="entry-warehouse-option"
              :class="{ 'is-active': warehouse === option }"
              :aria-pressed="warehouse === option"
              @click="warehouse = option"
            >
              {{ t(`common.warehouse.${option}`) }}
            </button>
          </div>
        </template>
      </van-field>
    </van-cell-group>

    <div class="entry-preview">
      <div class="entry-preview-row">
        <span class="entry-preview-label">{{ t('entry.nextCode') }}</span>
        <span
          v-if="previewLoading"
          class="entry-preview-code"
        >…</span>
        <span
          v-else-if="previewCode"
          class="entry-preview-code"
        >{{ previewCode }}</span>
      </div>
      <p
        v-if="previewError"
        class="entry-preview-error"
      >
        {{ previewError }}
      </p>
      <p class="entry-preview-note">
        {{ t('entry.nextCodeNote') }}
      </p>
    </div>

    <van-cell-group
      inset
      class="entry-more"
    >
      <van-cell
        :title="showMore ? t('entry.lessFields') : t('entry.moreFields')"
        is-link
        :class="{ 'entry-more-open': showMore }"
        @click="showMore = !showMore"
      />
      <template v-if="showMore">
        <van-field
          v-model="feeText"
          :label="t('entry.fee')"
          type="text"
          inputmode="numeric"
          name="fee"
          @blur="feeText = normalizeNumericText(feeText)"
        />
        <van-field
          v-model="shippingText"
          :label="t('entry.shippingFee')"
          type="text"
          inputmode="numeric"
          name="shippingFee"
          @blur="shippingText = normalizeNumericText(shippingText)"
        />
        <van-field
          v-model="taxText"
          :label="t('entry.tax')"
          type="text"
          inputmode="numeric"
          name="tax"
          @blur="taxText = normalizeNumericText(taxText)"
        />
        <van-field
          v-model="groupNo"
          :label="t('entry.groupNo')"
          :placeholder="t('entry.groupNoPlaceholder')"
          name="groupNo"
        />
        <van-field
          v-model="shelfNo"
          :label="t('entry.shelfNo')"
          name="shelfNo"
        />
        <van-field
          :model-value="warehouseInDate ? warehouseInDate.replaceAll('-', '/') : ''"
          :label="t('entry.warehouseInDate')"
          readonly
          is-link
          name="warehouseInDate"
          @click="showInDatePicker = true"
        />
        <van-popup
          v-model:show="showInDatePicker"
          position="bottom"
          round
        >
          <van-date-picker
            :model-value="toPickerValues(warehouseInDate)"
            :min-date="MIN_DATE"
            :max-date="inDateMax"
            :title="t('entry.warehouseInDate')"
            @confirm="onInDateConfirm"
            @cancel="showInDatePicker = false"
          />
        </van-popup>
        <van-field
          v-model="remark"
          :label="t('entry.remark')"
          :placeholder="t('entry.remarkPlaceholder')"
          type="textarea"
          rows="2"
          autosize
          maxlength="500"
          show-word-limit
          name="remark"
        />
      </template>
    </van-cell-group>
    <p
      v-if="showMore"
      class="entry-in-date-hint"
    >
      {{ t('entry.warehouseInDateHint') }}
    </p>

    <div
      v-if="saveError"
      class="kcgl-error-box entry-error"
    >
      <p class="entry-error-message">
        {{ saveError }}
      </p>
      <p
        v-if="saveErrorId"
        class="entry-error-id"
      >
        ID: {{ saveErrorId }}
      </p>
      <button
        type="button"
        class="kcgl-btn entry-error-retry"
        @click="onSubmit"
      >
        {{ t('entry.retrySame') }}
      </button>
    </div>

    <van-button
      class="entry-submit"
      block
      type="primary"
      native-type="submit"
      :loading="submitting"
      :loading-text="t('entry.saving')"
    >
      {{ t('entry.save') }}
    </van-button>
  </van-form>
</template>

<style scoped>
.entry-form {
  display: grid;
  gap: 12px;
}

/* Vant cell 默认字号 14px：钉 16px 防 iOS 聚焦自动缩放 */
.entry-form :deep(.van-field__control) {
  font-size: 16px;
}

.entry-prev-day {
  color: var(--kcgl-color-danger);
  font-size: 0.75rem;
  border: 1px solid var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  border-radius: 4px;
  padding: 0 6px;
  white-space: nowrap;
}

.entry-band {
  color: var(--kcgl-color-info-text);
  background: var(--kcgl-color-info-bg);
  border: 1px solid var(--kcgl-color-info-border);
  border-radius: 4px;
  padding: 0 8px;
  font-size: 0.8rem;
  white-space: nowrap;
}

.entry-band-miss {
  color: var(--kcgl-color-danger);
  background: var(--kcgl-color-danger-bg);
  border-color: var(--kcgl-color-danger-border);
}

.entry-warehouse {
  display: flex;
  gap: 8px;
  width: 100%;
}

.entry-warehouse-option {
  flex: 1;
  height: 40px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.9rem;
  cursor: pointer;
}

.entry-warehouse-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.entry-preview {
  margin: 0 16px;
  padding: 12px 16px;
  border: 1px dashed var(--kcgl-color-border);
  border-radius: 6px;
  background: #fff;
  display: grid;
  gap: 4px;
}

.entry-preview-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.entry-preview-label {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.entry-preview-code {
  font-size: 1.3rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  font-family: 'Courier New', monospace;
}

.entry-preview-error {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-danger);
}

.entry-preview-note {
  margin: 0;
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
}

.entry-more :deep(.van-cell) {
  color: var(--kcgl-color-text-sub);
  font-size: 0.9rem;
}

.entry-more-open :deep(.van-icon) {
  transform: rotate(90deg);
}

.entry-in-date-hint {
  margin: -4px 16px 0;
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
}

.entry-error {
  margin: 0 16px;
  display: grid;
  gap: 8px;
}

.entry-error-message,
.entry-error-id {
  margin: 0;
}

.entry-error-id {
  font-size: 0.8rem;
  opacity: 0.8;
}

.entry-error-retry {
  justify-self: start;
  background: var(--kcgl-color-danger);
  color: #fff;
  font-size: 0.85rem;
  height: 36px;
}

.entry-submit {
  margin-top: 4px;
}
</style>
