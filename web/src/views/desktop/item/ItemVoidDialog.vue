<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { voidItem } from '@/utils/api'
import type { ItemResponse } from '@/utils/api'

/**
 * 作废并重录弹层（E+）：作废成功后由父组件跳录入页（携 ?reEntry= 作重录源），
 * 详情页随之卸载，故本弹层不自行复位状态；失败则留在弹层内报错，输入不丢。
 * 理由必填（作废是不可逆动作，审计要留原因）。
 */

const props = defineProps<{ item: ItemResponse }>()

const emit = defineEmits<{
  /** 作废成功：父组件跳录入页 */
  voided: []
}>()

const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()

const busy = ref(false)
const error = ref('')
const reason = ref('')

watch(visible, (open) => {
  if (open) {
    reason.value = ''
    error.value = ''
  }
})

async function onSubmit(): Promise<void> {
  if (busy.value) {
    return
  }
  if (reason.value.trim() === '') {
    error.value = t('entry.voidReasonRequired')
    return
  }
  busy.value = true
  error.value = ''
  try {
    await voidItem(props.item.id, crypto.randomUUID(), reason.value.trim())
    emit('voided')
  } catch (err) {
    error.value = toDisplayMessage(err, t)
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('entry.voidTitle')"
    width="440px"
    :close-on-click-modal="!busy"
  >
    <div class="kcgl-form">
      <p class="kcgl-form-note">
        {{ t('entry.voidNote') }}
      </p>
      <label class="kcgl-field">
        <span class="kcgl-label">{{ t('entry.voidReasonLabel') }}</span>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          :placeholder="t('entry.voidReasonPlaceholder')"
          :disabled="busy"
        />
      </label>
      <p class="kcgl-warn-note">
        {{ t('entry.voidLabelWarn') }}
      </p>
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
        {{ t('entry.voidConfirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>
