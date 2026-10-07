<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { ApiError, updateItem } from '@/utils/api'
import type { ItemResponse, Venue } from '@/utils/api'

/**
 * 商品编辑弹层（E+，全量 PUT）：可选字段留空即 null（服务端语义=清空）。
 *
 * 弹层自持表单与提交，父组件只开合它并在成功后重载详情；409000（乐观锁冲突）
 * 走 stale 让父组件重读——表单输入保留，version 由 props 刷新后可直接再提交。
 * 样式走全局 .kcgl-* 基元（跨组件边界，见 brand.css）：scoped 样式在子组件里
 * 拿不到父组件的 .itemd-* 规则。
 */

const props = defineProps<{
  item: ItemResponse
  venues: Venue[]
}>()

const emit = defineEmits<{
  /** 保存成功：父组件重载详情 */
  saved: []
  /** 版本冲突：父组件重读以刷新 version（弹层保持打开、输入不丢） */
  stale: []
}>()

const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()

interface EditForm {
  venueId: number | null
  buyDate: string | null
  purchasePrice: number | undefined
  warehouse: number | null
  photoDate: string | null
  fee: number | undefined
  shippingFee: number | undefined
  tax: number | undefined
  shelfNo: string
  warehouseInDate: string | null
  groupNo: string
  remark: string
  itemName: string
  category: string
  authorKiln: string
  sizeText: string
  weightG: number | undefined
  salesChannel: string
}

const busy = ref(false)
const error = ref('')
const form = ref<EditForm | null>(null)

/** 编辑下拉只给启用会场；现值若已停用则保留（快照可查不可新选）。 */
const editVenues = computed(() =>
  props.venues.filter((v) => v.enabled || v.id === props.item.venueId))

function buildForm(): EditForm {
  const current = props.item
  return {
    venueId: current.venueId,
    buyDate: current.buyDate,
    purchasePrice: current.purchasePrice,
    warehouse: current.warehouse,
    photoDate: current.photoDate,
    fee: current.fee ?? undefined,
    shippingFee: current.shippingFee ?? undefined,
    tax: current.tax ?? undefined,
    shelfNo: current.shelfNo ?? '',
    warehouseInDate: current.warehouseInDate,
    groupNo: current.groupNo ?? '',
    remark: current.remark ?? '',
    itemName: current.itemName ?? '',
    category: current.category ?? '',
    authorKiln: current.authorKiln ?? '',
    sizeText: current.sizeText ?? '',
    weightG: current.weightG ?? undefined,
    salesChannel: current.salesChannel ?? '',
  }
}

/** 每次打开都以现值起步：残留的上次编辑会被当成本次输入误提交。 */
watch(visible, (open) => {
  if (open) {
    form.value = buildForm()
    error.value = ''
  }
})

function validate(): string {
  const current = form.value
  if (current == null) {
    return ''
  }
  if (current.venueId == null || current.buyDate == null
    || current.purchasePrice == null || current.warehouse == null) {
    return t('items.edit.validationRequired')
  }
  const price = current.purchasePrice
  if (!Number.isInteger(price) || price < 1 || price > 99_999_999) {
    return t('items.edit.validationPrice')
  }
  return ''
}

function trimmed(value: string): string | null {
  const result = value.trim()
  return result === '' ? null : result
}

async function onSubmit(): Promise<void> {
  const current = form.value
  if (current == null || busy.value) {
    return
  }
  error.value = validate()
  if (error.value !== '') {
    return
  }
  busy.value = true
  try {
    // version 取 props 现值：409000 后父组件已重读，下次提交自动带上新版本
    await updateItem(props.item.id, {
      version: props.item.version,
      venueId: current.venueId!,
      buyDate: current.buyDate!,
      purchasePrice: current.purchasePrice!,
      warehouse: current.warehouse!,
      photoDate: current.photoDate ?? null,
      fee: current.fee ?? null,
      shippingFee: current.shippingFee ?? null,
      tax: current.tax ?? null,
      shelfNo: trimmed(current.shelfNo),
      warehouseInDate: current.warehouseInDate ?? null,
      groupNo: trimmed(current.groupNo),
      remark: trimmed(current.remark),
      itemName: trimmed(current.itemName),
      category: trimmed(current.category),
      authorKiln: trimmed(current.authorKiln),
      sizeText: trimmed(current.sizeText),
      weightG: current.weightG ?? null,
      salesChannel: trimmed(current.salesChannel),
    })
    visible.value = false
    emit('saved')
  } catch (err) {
    if (err instanceof ApiError && err.code === 409000) {
      // 乐观锁冲突：父组件重读刷新 version（表单输入保留，改完可直接再提交）
      error.value = t('items.edit.versionConflict')
      emit('stale')
    } else {
      error.value = toDisplayMessage(err, t)
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('items.edit.title')"
    width="640px"
    :close-on-click-modal="!busy"
  >
    <div
      v-if="form != null"
      class="kcgl-form"
    >
      <p class="kcgl-form-note">
        {{ t('items.edit.note') }}
      </p>
      <div class="kcgl-form-grid">
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.venue') }}</span>
          <el-select
            v-model="form.venueId"
            filterable
            :disabled="busy"
          >
            <el-option
              v-for="venue in editVenues"
              :key="venue.id"
              :label="venue.name"
              :value="venue.id"
            />
          </el-select>
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.buyDate') }}</span>
          <el-date-picker
            v-model="form.buyDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.purchasePrice') }}</span>
          <el-input-number
            v-model="form.purchasePrice"
            :min="1"
            :max="99999999"
            :step="1"
            :precision="0"
            :controls="false"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.warehouse') }}</span>
          <el-select
            v-model="form.warehouse"
            :disabled="busy || item.stockStatus !== 0"
          >
            <el-option
              :label="t('common.warehouse.1')"
              :value="1"
            />
            <el-option
              :label="t('common.warehouse.2')"
              :value="2"
            />
          </el-select>
          <span class="kcgl-field-hint">{{ t('items.edit.warehouseHint') }}</span>
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.fee') }}</span>
          <el-input-number
            v-model="form.fee"
            :min="0"
            :max="99999999"
            :precision="0"
            :controls="false"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.shippingFee') }}</span>
          <el-input-number
            v-model="form.shippingFee"
            :min="0"
            :max="99999999"
            :precision="0"
            :controls="false"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.tax') }}</span>
          <el-input-number
            v-model="form.tax"
            :min="0"
            :max="99999999"
            :precision="0"
            :controls="false"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.photoDate') }}</span>
          <el-date-picker
            v-model="form.photoDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.shelfNo') }}</span>
          <el-input
            v-model="form.shelfNo"
            maxlength="32"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.warehouseInDate') }}</span>
          <el-date-picker
            v-model="form.warehouseInDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.groupNo') }}</span>
          <el-input
            v-model="form.groupNo"
            maxlength="32"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.weightG') }}</span>
          <el-input-number
            v-model="form.weightG"
            :min="1"
            :max="2000000"
            :precision="0"
            :controls="false"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.itemName') }}</span>
          <el-input
            v-model="form.itemName"
            maxlength="200"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.category') }}</span>
          <el-input
            v-model="form.category"
            maxlength="64"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.authorKiln') }}</span>
          <el-input
            v-model="form.authorKiln"
            maxlength="128"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.sizeText') }}</span>
          <el-input
            v-model="form.sizeText"
            maxlength="64"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field">
          <span class="kcgl-label">{{ t('items.detail.field.salesChannel') }}</span>
          <el-input
            v-model="form.salesChannel"
            maxlength="32"
            :disabled="busy"
          />
        </label>
        <label class="kcgl-field kcgl-field-wide">
          <span class="kcgl-label">{{ t('items.detail.field.remark') }}</span>
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            :disabled="busy"
          />
        </label>
      </div>
      <p
        v-if="error"
        class="kcgl-form-error"
        role="alert"
      >
        {{ error }}
      </p>
    </div>
    <template #footer>
      <el-button
        :disabled="busy"
        @click="visible = false"
      >
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        :loading="busy"
        @click="onSubmit"
      >
        {{ t('items.edit.save') }}
      </el-button>
    </template>
  </el-dialog>
</template>
