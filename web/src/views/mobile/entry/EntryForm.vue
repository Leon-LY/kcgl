<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { useDictsStore } from '@/stores/dicts'
import { useEntrySessionStore } from '@/stores/entrySession'
import { ApiError, createItem, fetchItemImages, previewItemCode, type ItemResponse } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { JST_TZ, dayjs } from '@/utils/format'
import { newClientId } from '@/utils/id'
import { normalizeNumericText, parseAmount, trimText } from '@/utils/normalize'

/**
 * 连续录入表单（docs/01 4.3 验收核心页）：
 * - 沿用上一件（A13）：会场/仓库/落札日期/单价——挂载时从会话读，成功后父级重挂载取新值
 * - 两级预览：档位字母本地即时算（零往返）；完整号 300ms 防抖调 preview（≠保留，文案明示）
 * - 幂等（7.0）：clientReqId 一次逻辑保存从生成到成功共用；失败重试复用同键防重复件
 * - IME（7.8）：金额字段仅在 blur 归一化（NFKC 全角→半角）；备注仅 trim
 * - 照片（7.5）：选图即压缩落 Dexie（pending_bind）；保存成功后 bindItem 绑新商品后台续传
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

// ------------------------------------------------------------------ 照片（7.5 先存后传）

const MAX_PHOTOS = 9
type PhotoSource = 'camera' | 'album'

interface LocalPhoto {
  clientUuid: string
  previewUrl: string
  source: PhotoSource
}

const photos = ref<LocalPhoto[]>([])
const photoDate = ref('')
const photoError = ref<string | null>(null)
const showPhotoDatePicker = ref(false)
const cameraInput = ref<HTMLInputElement | null>(null)
const albumInput = ref<HTMLInputElement | null>(null)
/** 撮影日上限=今天（后端同口径校验）。 */
const photoDateMax = buyDateMax

async function onFilesChosen(event: Event, source: PhotoSource): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // 允许再次选择同一张
  if (files.length === 0) {
    return
  }
  if (photos.value.length + files.length > MAX_PHOTOS) {
    photoError.value = t('entry.photoLimit')
    return
  }
  photoError.value = null
  try {
    // 压缩（≤0.3MB/1920px）后即刻入 Dexie 队列——崩溃/刷新不丢
    const entries = await uploadQueue.addFiles(files)
    for (const entry of entries) {
      photos.value.push({
        clientUuid: entry.clientUuid,
        previewUrl: URL.createObjectURL(new Blob([entry.data], { type: entry.mimeType })),
        source,
      })
    }
    // 拍照=撮影日=今天（7.5）；相册不默认今天（EXIF 自动读取为 D-034 决策延后项）
    if (source === 'camera' && photoDate.value === '') {
      photoDate.value = todayJst()
    }
  } catch {
    photoError.value = t('entry.photoReadFailed')
  }
}

async function removePhoto(clientUuid: string): Promise<void> {
  const index = photos.value.findIndex((photo) => photo.clientUuid === clientUuid)
  if (index === -1) {
    return
  }
  URL.revokeObjectURL(photos.value[index]!.previewUrl)
  photos.value.splice(index, 1)
  await uploadQueue.removeUnbound(clientUuid)
}

onUnmounted(() => {
  for (const photo of photos.value) {
    URL.revokeObjectURL(photo.previewUrl)
  }
})

// ------------------------------------------------------------------ 重录继承图片（M2-6）

/** 原件已上传图片（只读展示）：保存时由服务端复制行到新商品，不进本地 Dexie、无需重拍。 */
const inheritedImages = ref<Array<{ id: number; thumbUrl: string }>>([])

onMounted(async () => {
  if (props.reEntry == null) {
    return
  }
  try {
    const images = await fetchItemImages(props.reEntry.id)
    inheritedImages.value = images.map((image) => ({ id: image.id, thumbUrl: image.thumbUrl }))
  } catch {
    // 读回失败不阻断重录：服务端复制不依赖前端展示，新件仍会带上图片
  }
})

const photoDateDisplay = computed(() => (photoDate.value ? photoDate.value.replaceAll('-', '/') : ''))

function onPhotoDateConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  photoDate.value = fromPickerValues(selectedValues)
  showPhotoDatePicker.value = false
}

/** 有照片时随保存提交撮影日（拍照自动=当天，可改；无照片不提交）。 */
function photoDateForPayload(): string | undefined {
  return photos.value.length > 0 && photoDate.value !== '' ? photoDate.value : undefined
}

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
      photoDate: photoDateForPayload(),
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

    <van-cell-group
      v-if="inheritedImages.length > 0"
      inset
      class="entry-inherited"
    >
      <van-cell :title="t('entry.inheritedImages')" />
      <div class="entry-inherited-photos">
        <img
          v-for="image in inheritedImages"
          :key="image.id"
          :src="image.thumbUrl"
          :alt="t('entry.inheritedImages')"
        >
      </div>
      <p class="entry-inherited-note">
        {{ t('entry.inheritedImagesNote') }}
      </p>
    </van-cell-group>

    <van-cell-group inset>
      <van-field :label="t('entry.photos')">
        <template #input>
          <div class="entry-photos">
            <div
              v-for="photo in photos"
              :key="photo.clientUuid"
              class="entry-photo"
            >
              <img
                :src="photo.previewUrl"
                :alt="t('entry.photos')"
              >
              <button
                type="button"
                class="entry-photo-remove"
                :aria-label="t('entry.photoRemove')"
                @click="removePhoto(photo.clientUuid)"
              >
                ×
              </button>
            </div>
            <span
              v-if="photos.length > 0"
              class="entry-photo-count"
            >{{ photos.length }}/9</span>
          </div>
        </template>
      </van-field>
      <div class="entry-photo-actions">
        <button
          type="button"
          class="kcgl-btn entry-photo-btn"
          @click="cameraInput?.click()"
        >
          {{ t('entry.photoCamera') }}
        </button>
        <button
          type="button"
          class="kcgl-btn entry-photo-btn"
          @click="albumInput?.click()"
        >
          {{ t('entry.photoAlbum') }}
        </button>
      </div>
      <p
        v-if="photoError"
        class="entry-photo-error"
      >
        {{ photoError }}
      </p>
      <p class="entry-photo-hint">
        {{ t('entry.photosHint') }}
      </p>
      <van-field
        :model-value="photoDateDisplay"
        :label="t('entry.photoDate')"
        :placeholder="t('entry.photoDatePlaceholder')"
        class="entry-photo-date"
        readonly
        is-link
        name="photoDate"
        @click="showPhotoDatePicker = true"
      />
      <van-popup
        v-model:show="showPhotoDatePicker"
        position="bottom"
        round
      >
        <van-date-picker
          :model-value="toPickerValues(photoDate)"
          :min-date="MIN_DATE"
          :max-date="photoDateMax"
          :title="t('entry.photoDate')"
          @confirm="onPhotoDateConfirm"
          @cancel="showPhotoDatePicker = false"
        />
      </van-popup>
    </van-cell-group>

    <!-- iOS 相机直启（capture=environment）与相册多选分开两个入口：撮影日来源语义 -->
    <input
      ref="cameraInput"
      type="file"
      accept="image/jpeg,image/png"
      capture="environment"
      hidden
      @change="onFilesChosen($event, 'camera')"
    >
    <input
      ref="albumInput"
      type="file"
      accept="image/jpeg,image/png"
      multiple
      hidden
      @change="onFilesChosen($event, 'album')"
    >

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

/* 照片区：缩略图 56px + 删除角标（触控目标 ≥44px 由按钮整体承担，角标为可点区域中心） */
.entry-photos {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.entry-photo {
  position: relative;
  width: 56px;
  height: 56px;
}

.entry-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--kcgl-color-border);
  display: block;
}

.entry-photo-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 0.8rem;
  line-height: 1;
  cursor: pointer;
}

.entry-photo-count {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.entry-photo-actions {
  display: flex;
  gap: 8px;
  padding: 0 16px 8px;
}

.entry-photo-btn {
  flex: 1;
  height: 36px;
  font-size: 0.85rem;
}

.entry-photo-error {
  margin: 0 16px 4px;
  font-size: 0.8rem;
  color: var(--kcgl-color-danger);
}

.entry-photo-hint {
  margin: 0 16px 8px;
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
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
  font-size: 0.78rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.entry-reentry-cancel {
  border: none;
  background: none;
  padding: 2px 4px;
  font: inherit;
  font-size: 0.8rem;
  color: var(--kcgl-color-primary);
  text-decoration: underline;
  white-space: nowrap;
  cursor: pointer;
}

/* 继承图片：只读缩略图（不可删——服务端保存时整组复制到新件） */
.entry-inherited-photos {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 16px 8px;
}

.entry-inherited-photos img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--kcgl-color-border);
  display: block;
}

.entry-inherited-note {
  margin: 0 16px 12px;
  font-size: 0.75rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}
</style>
