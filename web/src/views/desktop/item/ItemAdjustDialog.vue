<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { adjustItem } from '@/utils/api'
import type { AdjustItemPayload, ItemResponse } from '@/utils/api'

/**
 * 手工修正弹层（D4，A-only）：直接改库存态/销售态两轴，绕开状态机。
 *
 * 「不变」用哨兵 -1 而非 null——Element Plus 的 el-option 以 null 作值会在
 * 选中态判定上打滑，而 null 又恰好是「未选」，两者混一起就会把「保持不变」
 * 误判成「没选」。哨兵把「没选」从状态里彻底删掉。
 * 改仓库不在此列（改仓走扫码页「移动」，保住「改仓必走台账」对账不变量）。
 */

const UNCHANGED = -1
const STOCK_OPTIONS = [0, 1, 2] as const
const SALE_OPTIONS = [0, 1, 2, 3] as const

const props = defineProps<{ item: ItemResponse }>()
const emit = defineEmits<{ adjusted: [] }>()
const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()

const reason = ref('')
const stockStatus = ref<number>(UNCHANGED)
const saleStatus = ref<number>(UNCHANGED)
const busy = ref(false)
const error = ref('')

/** 每次打开都从干净态起步：残留的上次选择会被当成「本次要改的轴」误提交。 */
watch(visible, (open) => {
  if (open) {
    reason.value = ''
    stockStatus.value = UNCHANGED
    saleStatus.value = UNCHANGED
    error.value = ''
  }
})

function stockText(status: number): string {
  return t(`scan.stock.${status}`)
}

function saleText(status: number): string {
  return t(`scan.sale.${status}`)
}

/** 选中的轴里至少一轴与现态不同才算有修正（服务端也拒无变化，这里先给清楚的提示）。 */
function hasChange(): boolean {
  const stockChanged = stockStatus.value !== UNCHANGED && stockStatus.value !== props.item.stockStatus
  const saleChanged = saleStatus.value !== UNCHANGED && saleStatus.value !== props.item.saleStatus
  return stockChanged || saleChanged
}

async function onSubmit(): Promise<void> {
  if (busy.value) {
    return
  }
  error.value = ''
  const trimmedReason = reason.value.trim()
  if (trimmedReason === '') {
    error.value = t('items.detail.adjustReasonRequired')
    return
  }
  if (!hasChange()) {
    error.value = t('items.detail.adjustNoChange')
    return
  }
  // 只带被指定的轴：另一轴键不出现=保持不变（服务端以键缺失为「不变」语义）
  const payload: AdjustItemPayload = { clientReqId: crypto.randomUUID(), reason: trimmedReason }
  if (stockStatus.value !== UNCHANGED) {
    payload.stockStatus = stockStatus.value
  }
  if (saleStatus.value !== UNCHANGED) {
    payload.saleStatus = saleStatus.value
  }
  busy.value = true
  try {
    await adjustItem(props.item.id, payload)
    visible.value = false
    emit('adjusted')
  } catch (err) {
    error.value = toDisplayMessage(err, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('items.detail.adjustTitle')"
    width="460px"
    :close-on-click-modal="!busy"
  >
    <div class="adjust-form">
      <p class="adjust-note">
        {{ t('items.detail.adjustNote') }}
      </p>
      <div class="kcgl-field">
        <span class="kcgl-label">{{ t('items.detail.adjustReasonLabel') }}</span>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          :disabled="busy"
        />
      </div>
      <div class="kcgl-field">
        <span class="kcgl-label">{{ t('items.detail.adjustStockLabel') }}</span>
        <p class="adjust-current">
          {{ t('items.detail.adjustCurrent', { value: stockText(item.stockStatus) }) }}
        </p>
        <el-select
          v-model="stockStatus"
          class="adjust-select"
          :disabled="busy"
        >
          <el-option
            :value="UNCHANGED"
            :label="t('items.detail.adjustUnchanged')"
          />
          <el-option
            v-for="n in STOCK_OPTIONS"
            :key="n"
            :value="n"
            :label="stockText(n)"
          />
        </el-select>
      </div>
      <div class="kcgl-field">
        <span class="kcgl-label">{{ t('items.detail.adjustSaleLabel') }}</span>
        <p class="adjust-current">
          {{ t('items.detail.adjustCurrent', { value: saleText(item.saleStatus) }) }}
        </p>
        <el-select
          v-model="saleStatus"
          class="adjust-select"
          :disabled="busy"
        >
          <el-option
            :value="UNCHANGED"
            :label="t('items.detail.adjustUnchanged')"
          />
          <el-option
            v-for="n in SALE_OPTIONS"
            :key="n"
            :value="n"
            :label="saleText(n)"
          />
        </el-select>
      </div>
      <p class="adjust-hint">
        {{ t('items.detail.adjustWarehouseHint') }}
      </p>
      <p
        v-if="error"
        class="kcgl-error-box"
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
        {{ t('items.detail.adjustConfirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 仅本弹层自有类；表单字段复用全局 .kcgl-field/.kcgl-label（styles/brand.css，
   跨视图共享——scoped 样式不能跨组件边界，复制父组件的 .itemd-* 会失效）。 */
.adjust-form {
  display: grid;
  gap: var(--kcgl-space-3);
}

.adjust-note {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.adjust-current {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.adjust-select {
  width: 100%;
}

.adjust-hint {
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-faint);
}
</style>
