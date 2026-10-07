<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * 批量软删确认层（A-only，D-126）。
 *
 * 只负责收「理由」并回吐：**提交与结果展示都留在列表页**——批量的结果可能是
 * 「N 件成功、M 件失败」，那条信息必须留在列表上（用户要据此重选再试），而弹层
 * 关闭即卸载，把结果放里面等于让它消失。故本组件不碰 API。
 *
 * busy 由父组件传入而非自持：提交期间父组件在跑批，按钮 loading 必须跟着父组件的
 * 真实状态走，否则弹层会在请求还没回来时先解锁。
 */

defineProps<{ count: number; busy: boolean }>()
const emit = defineEmits<{ confirm: [reason: string] }>()

const visible = defineModel<boolean>({ required: true })
const { t } = useI18n()

const reason = ref('')

watch(visible, (open) => {
  if (open) {
    reason.value = ''
  }
})

function onSubmit(): void {
  emit('confirm', reason.value.trim())
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('items.batch.deleteTitle')"
    width="440px"
    :close-on-click-modal="!busy"
  >
    <div class="kcgl-form">
      <p class="kcgl-form-note">
        {{ t('items.batch.deleteNote', { n: count }) }}
      </p>
      <label class="kcgl-field">
        <span class="kcgl-label">{{ t('items.batch.reasonLabel') }}</span>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          :disabled="busy"
        />
      </label>
    </div>
    <template #footer>
      <el-button
        :disabled="busy"
        @click="visible = false"
      >
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="danger"
        :loading="busy"
        @click="onSubmit"
      >
        {{ t('items.batch.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>
