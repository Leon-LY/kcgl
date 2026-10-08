<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { useDictsStore } from '@/stores/dicts'
import { useEntrySessionStore } from '@/stores/entrySession'
import { ApiError, createItem, type ItemResponse } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { JST_TZ, dayjs } from '@/utils/format'
import { newClientId } from '@/utils/id'
import { normalizeNumericText, parseAmount, trimText } from '@/utils/normalize'
import { MIN_DATE, fromPickerValues, jstTodayEnd, toPickerValues, todayJst } from './entryShared'
import EntryCodePreview from './EntryCodePreview.vue'
import EntryPhotoField from './EntryPhotoField.vue'

/**
 * 连续录入表单（docs/01 4.3 验收核心页）。本文件只留表单主体：两个自成一段的字段已拆成
 * 子组件（D-148）——照片与重录继承图片在 EntryPhotoField，管理号预览在 EntryCodePreview。
 * - 沿用上一件（A13）：会场/仓库/落札日期/单价——挂载时从会话读，成功后父级重挂载取新值
 * - 两级预览：档位字母本地即时算（零往返，留在本组件）；完整号 300ms 防抖调 preview
 *   （≠保留，文案明示；在 EntryCodePreview）
 * - 幂等（7.0）：clientReqId 一次逻辑保存从生成到成功共用；失败重试复用同键防重复件
 * - IME（7.8）：金额字段仅在 blur 归一化（NFKC 全角→半角）；备注仅 trim
 */

const emit = defineEmits<{ saved: [item: ItemResponse, photoCount: number]; cancelReEntry: [] }>()

/** 重录源（M2-6）：已作废原件——非空时以原件预填全字段并携带 reEntryOf 提交。 */
const props = withDefaults(defineProps<{ reEntry?: ItemResponse | null }>(), { reEntry: null })

const { t } = useI18n()
const dicts = useDictsStore()
const session = useEntrySessionStore()
const uploadQueue = useUploadQueue()

// ------------------------------------------------------------------ 表单状态

// 重录模式：全部字段以原件预填（用户只改错处）；普通模式沿用上一件（A13）。
// 撮影日不预填——新拍照片取新日期，无新照片时服务端继承原件（M2-6 语义）。
const venueId = ref<number | null>(props.reEntry?.venueId ?? session.venueId)
const warehouse = ref<number>(props.reEntry?.warehouse ?? session.warehouse)
const buyDate = ref<string>(props.reEntry?.buyDate ?? session.buyDate)
const priceText = ref<string>(
  props.reEntry != null
    ? String(props.reEntry.purchasePrice)
    : session.purchasePrice != null
      ? String(session.purchasePrice)
      : '',
)
const feeText = ref(props.reEntry?.fee != null ? String(props.reEntry.fee) : '')
const shippingText = ref(props.reEntry?.shippingFee != null ? String(props.reEntry.shippingFee) : '')
const taxText = ref(props.reEntry?.tax != null ? String(props.reEntry.tax) : '')
const groupNo = ref(props.reEntry?.groupNo ?? '')
const shelfNo = ref(props.reEntry?.shelfNo ?? '')
const warehouseInDate = ref(props.reEntry?.warehouseInDate ?? '')
const remark = ref(props.reEntry?.remark ?? '')
const showMore = ref(false)

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

/**
 * 沿用会场失效兜底：venueId 来自上次保存（会话持久化）或重录原件，两者都可能指向
 * 一个**后来被后台停用**的会场。下拉只列启用会场，于是旧写法里 venueDisplay 算成空串
 * 而 venueId 仍是那个停用 id——字段看着是空的、内部却有值，提交被 required 规则拦下，
 * 提示只有一句「请选择会场」，用户不明白自己明明选过。字典就绪后一旦发现沿用 id
 * 不在启用列表，清掉并给一句白话说明。清空不丢能力：停用会场本就不在可选列表里。
 */
const venueUnavailable = ref(false)

watch(
  () => [dicts.loaded, venueId.value, dicts.enabledVenues] as const,
  () => {
    if (!dicts.loaded || venueId.value == null) {
      return
    }
    const stillEnabled = dicts.enabledVenues.some((venue) => venue.id === venueId.value)
    if (stillEnabled) {
      venueUnavailable.value = false
      return
    }
    venueId.value = null
    venueUnavailable.value = true
  },
  { immediate: true },
)

/** 落札日禁未来（JST 日界）：max 取 JST 今日 23:59，+08 深夜开发场景下默认值与选择上限一致。 */
const buyDateMax = jstTodayEnd()
/** 入库日允许未来（预填则到仓扫码不覆盖）。 */
const inDateMax = dayjs().tz(JST_TZ).add(1, 'year').endOf('day').toDate()

const showBuyDatePicker = ref(false)
const showInDatePicker = ref(false)

function onBuyDateConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  buyDate.value = fromPickerValues(selectedValues)
  showBuyDatePicker.value = false
}

function onInDateConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  warehouseInDate.value = fromPickerValues(selectedValues)
  showInDatePicker.value = false
}

const buyDateDisplay = computed(() => buyDate.value.replaceAll('-', '/'))

// ------------------------------------------------------------------ 照片字段（EntryPhotoField）

/**
 * 照片字段的引用：挂载时读回重录原件已有的图片，提交时给出随件上传的撮影日。
 * 子组件不自带 onMounted——挂载时机只由这里的 onMounted 决定（同 ItemListTab 那条规矩）。
 */
const photoField = ref<InstanceType<typeof EntryPhotoField> | null>(null)

onMounted(() => {
  // 重录时读回原件图片（只读展示）；失败不阻断——服务端保存时自会整组复制
  void photoField.value?.loadInherited()
})

// ------------------------------------------------------------------ 校验与提交

const venueRules = [{ required: true, message: t('entry.validation.venueRequired') }]
const priceRules = [
  { required: true, message: t('entry.validation.priceRequired') },
  { validator: (value: string) => (parseAmount(value) ?? 0) >= 1, message: t('entry.validation.priceMin') },
]

const warehouseOptions = [1, 2]

let clientReqId = ''

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
    clientReqId = newClientId()
  }
  submitting.value = true
  saveError.value = null
  saveErrorId.value = null
  try {
    const item = await createItem({
      clientReqId,
      reEntryOf: props.reEntry?.id,
      venueId: venueId.value,
      buyDate: buyDate.value,
      purchasePrice: price,
      warehouse: warehouse.value,
      photoDate: photoField.value?.photoDateForPayload(),
      fee: parseAmount(feeText.value) ?? undefined,
      shippingFee: parseAmount(shippingText.value) ?? undefined,
      tax: parseAmount(taxText.value) ?? undefined,
      shelfNo: trimText(shelfNo.value) || undefined,
      warehouseInDate: warehouseInDate.value || undefined,
      groupNo: trimText(groupNo.value) || undefined,
      remark: trimText(remark.value) || undefined,
    })
    // 照片绑定新商品（孤儿 pending_bind → pending），随后后台续传；失败保留 clientReqId
    const boundCount = await uploadQueue.bindItem(item.id)
    clientReqId = '' // 成功出清：下一件用新键
    emit('saved', item, boundCount)
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
    <div
      v-if="reEntry"
      class="kcgl-info-box entry-reentry"
    >
      <div class="entry-reentry-text">
        <p class="entry-reentry-title">
          {{ t('entry.reEntryTitle', { code: reEntry.itemCode }) }}
        </p>
        <p class="entry-reentry-note">
          {{ t('entry.reEntryNote') }}
        </p>
      </div>
      <button
        type="button"
        class="entry-reentry-cancel"
        @click="emit('cancelReEntry')"
      >
        {{ t('entry.reEntryCancel') }}
      </button>
    </div>

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
      <p
        v-if="venueUnavailable"
        class="entry-venue-unavailable"
      >
        {{ t('entry.venueUnavailable') }}
      </p>
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

    <EntryPhotoField
      ref="photoField"
      :re-entry-id="reEntry?.id ?? null"
    />

    <EntryCodePreview
      :venue-id="venueId"
      :buy-date="buyDate"
      :price="priceValue"
    />

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
  font-size: 0.8rem;
  border: 1px solid var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  border-radius: 4px;
  padding: 0 6px;
  white-space: nowrap;
}

.entry-venue-unavailable {
  margin: 0;
  padding: 8px 16px 10px;
  font-size: 0.9rem;
  line-height: 1.5;
  color: var(--kcgl-color-warning);
  background: var(--kcgl-color-warning-bg);
  border-top: 1px solid var(--kcgl-color-warning-border);
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
  height: 44px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.9rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.entry-warehouse-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 按压反馈：1px 下沉 + 边框加重（触屏无 hover，按压态是唯一反馈） */
.entry-warehouse-option:active {
  transform: translateY(1px);
  border-color: var(--kcgl-color-border-strong);
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
  font-size: 0.9rem;
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
  font-size: 0.9rem;
  opacity: 0.8;
}

/* 去掉 36px 覆盖：回到 .kcgl-btn 的 44px 触控下限 */
.entry-error-retry {
  justify-self: start;
  background: var(--kcgl-color-danger);
  color: #fff;
  font-size: 0.9rem;
}

/* 按压反馈：1px 下沉（.kcgl-btn 已含 transform 过渡） */
.entry-error-retry:active {
  transform: translateY(1px);
}

.entry-submit {
  margin-top: 4px;
}

/* 重录横幅：作废原件信息 + 放弃链接——info-box 基底上强调标题 */
.entry-reentry {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin: 0 16px;
}

.entry-reentry-text {
  flex: 1;
  display: grid;
  gap: 4px;
}

.entry-reentry-title {
  margin: 0;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--kcgl-color-text);
}

.entry-reentry-note {
  margin: 0;
  font-size: 0.9rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.entry-reentry-cancel {
  position: relative;
  border: none;
  background: none;
  padding: 2px 4px;
  font: inherit;
  font-size: 0.9rem;
  color: var(--kcgl-color-primary);
  text-decoration: underline;
  white-space: nowrap;
  cursor: pointer;
  transition: color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 文字链接视觉保持小号，仅用透明覆盖层把命中区撑到 ≥44px（不撑大视觉盒子） */
.entry-reentry-cancel::after {
  content: '';
  position: absolute;
  inset: -12px;
}

/* 按压反馈：主色加深（触屏无 hover，按压态是唯一反馈） */
.entry-reentry-cancel:active {
  color: var(--kcgl-color-primary-dark);
}

</style>
