<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { deleteItem } from '@/utils/api'
import type { ItemResponse } from '@/utils/api'
import { newClientId } from '@/utils/id'

/**
 * 软删弹层（仅管理员）：成功后由父组件跳回商品一覧（回收站标签里可复原），
 * 详情页随之卸载，故不自行复位；失败留在弹层内报错。理由可选（软删可复原，
 * 与不可逆的作废区分——作废那边的理由必填）。
 */

const props = defineProps<{ item: ItemResponse }>()

const emit = defineEmits<{
  /** 软删成功：父组件回一覧 */
  deleted: []
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
  busy.value = true
  error.value = ''
  try {
    const trimmed = reason.value.trim()
    // 幂等键必须走 newClientId()：crypto.randomUUID 只在**安全上下文**（HTTPS / localhost）
    // 存在，而本系统按部署文档是 `http://<サーバ>:<ポート>` 访问，此处裸调会抛 TypeError，
    // 被下面 catch 收成通用「エラーが発生しました」——用户看到的就是「删除不好用、提示错误」。
    // E2E 拦不住是因为门禁跑在 http://127.0.0.1（属安全上下文），恰好绕开了这个前提。
    await deleteItem(props.item.id, newClientId(), trimmed === '' ? undefined : trimmed)
    emit('deleted')
  } catch (err) {
    error.value = toDisplayMessage(err, t)
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('items.detail.deleteTitle')"
    width="440px"
    :close-on-click-modal="!busy"
  >
    <div class="kcgl-form">
      <p class="kcgl-form-note">
        {{ t('items.detail.deleteNote') }}
      </p>
      <label class="kcgl-field">
        <span class="kcgl-label">{{ t('items.detail.deleteReasonLabel') }}</span>
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          :disabled="busy"
        />
      </label>
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
        type="danger"
        :loading="busy"
        @click="onSubmit"
      >
        {{ t('items.detail.deleteConfirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>
