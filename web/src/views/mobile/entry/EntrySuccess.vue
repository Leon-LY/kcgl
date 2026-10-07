<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import QRCode from 'qrcode'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { ApiError, fetchItem, voidItem, type ItemResponse } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { newClientId } from '@/utils/id'

/**
 * 录入成功页：大字管理号 + 本地二维码（离线可用，D-033 决策：qrcode 前端唯一实现）
 * + 本日计数 + 照片上传角标（7.5：保存后后台续传，弱网时角标持续显示）+ 抄号流程文案
 * （H3：现场无打印机，「录一件抄一件（笔）+回社贴一件（标签）」）。
 * M2-6：取り消して再登録（docs/01 7.1 作废重录）——理由必填弹层（L7 撕标签提示）→
 * 作废后通知父级进入重录模式（预填原件全部字段）。
 */

const props = defineProps<{ item: ItemResponse; todayCount: number; photoCount: number }>()
const emit = defineEmits<{ continue: []; reEntry: [item: ItemResponse] }>()

const { t } = useI18n()
const uploadQueue = useUploadQueue()
const qrDataUrl = ref<string | null>(null)
const qrFailed = ref(false)

/** 本商品仍在传/待传数（reactive Map：随队列进度实时收缩到 0）。 */
const activeCount = computed(() => uploadQueue.activeByItem.get(props.item.id) ?? 0)
const failedCount = computed(
  () => uploadQueue.state.failures.filter((failure) => failure.itemId === props.item.id).length,
)
const uploadDone = computed(
  () => props.photoCount > 0 && activeCount.value === 0 && failedCount.value === 0,
)

onMounted(async () => {
  try {
    // 码内容恒为管理号明文（7.7）；ECC=M、quiet zone 默认 4 模块
    qrDataUrl.value = await QRCode.toDataURL(props.item.itemCode, {
      width: 192,
      margin: 2,
      errorCorrectionLevel: 'M',
    })
  } catch {
    // 二维码生成失败不阻断流程：管理号文字仍在（人读码是兜底路径）
    qrFailed.value = true
  }
})

// ------------------------------------------------------------------ 取り消して再登録（M2-6）

const showVoidDialog = ref(false)
const voidReason = ref('')
const voidError = ref<string | null>(null)
const voidErrorId = ref<string | null>(null)
const voiding = ref(false)

function openVoidDialog(): void {
  voidReason.value = ''
  voidError.value = null
  voidErrorId.value = null
  showVoidDialog.value = true
}

function closeVoidDialog(): void {
  showVoidDialog.value = false
}

/**
 * 作废并转入重录。幂等键每次打开弹层生成一次（同一次确认的重试共用；
 * 409006=超时重放窗口内已作废成功——读回后照常转入重录，不报错阻断）。
 */
async function confirmVoid(): Promise<void> {
  if (voiding.value) {
    return
  }
  const reason = voidReason.value.trim()
  if (reason === '') {
    voidError.value = t('entry.voidReasonRequired')
    return
  }
  voiding.value = true
  voidError.value = null
  voidErrorId.value = null
  try {
    const voided = await voidItem(props.item.id, newClientId(), reason)
    showVoidDialog.value = false
    emit('reEntry', voided)
  } catch (error) {
    if (error instanceof ApiError && error.code === 409006) {
      // 首次请求实际已成功但响应丢失：读回作废态商品继续重录流程
      try {
        const voided = await fetchItem(props.item.id)
        showVoidDialog.value = false
        emit('reEntry', voided)
        return
      } catch {
        // 读回也失败则走通用报错
      }
    }
    voidError.value = toDisplayMessage(error, t)
    voidErrorId.value = error instanceof ApiError ? (error.errorId ?? null) : null
  } finally {
    voiding.value = false
  }
}
</script>

<template>
  <section class="entry-success">
    <p class="entry-success-title">
      {{ t('entry.savedTitle') }}
    </p>

    <div class="kcgl-card entry-success-card">
      <p class="entry-success-label">
        {{ t('entry.itemCodeLabel') }}
      </p>
      <p class="entry-success-code">
        {{ item.itemCode }}
      </p>
      <p class="entry-success-count">
        {{ t('entry.todayCount', { count: todayCount }) }}
      </p>
      <p
        v-if="photoCount > 0 && activeCount > 0"
        class="entry-success-upload is-active"
      >
        {{ t('entry.uploading', { count: activeCount }) }}
      </p>
      <p
        v-else-if="uploadDone"
        class="entry-success-upload is-done"
      >
        {{ t('entry.uploadDone', { count: photoCount }) }}
      </p>
      <p
        v-else-if="failedCount > 0"
        class="entry-success-upload is-failed"
      >
        {{ t('entry.uploadFailed', { failed: failedCount }) }}
      </p>
      <img
        v-if="qrDataUrl"
        class="entry-success-qr"
        :src="qrDataUrl"
        :alt="item.itemCode"
        width="192"
        height="192"
      >
    </div>

    <div class="kcgl-info-box entry-success-guide">
      {{ t('entry.penGuide') }}
    </div>

    <van-button
      block
      type="primary"
      class="entry-success-continue"
      @click="emit('continue')"
    >
      {{ t('entry.continueEntry') }}
    </van-button>

    <button
      type="button"
      class="entry-success-void"
      @click="openVoidDialog"
    >
      {{ t('entry.voidButton') }}
    </button>

    <van-popup
      v-model:show="showVoidDialog"
      position="bottom"
      round
    >
      <div class="entry-void">
        <p class="entry-void-title">
          {{ t('entry.voidTitle') }}
        </p>
        <p class="entry-void-code">
          {{ item.itemCode }}
        </p>
        <p class="entry-void-note">
          {{ t('entry.voidNote') }}
        </p>
        <van-field
          v-model="voidReason"
          class="entry-void-reason"
          :label="t('entry.voidReasonLabel')"
          :placeholder="t('entry.voidReasonPlaceholder')"
          type="textarea"
          rows="2"
          autosize
          maxlength="255"
          name="voidReason"
        />
        <p class="entry-void-warn">
          {{ t('entry.voidLabelWarn') }}
        </p>
        <p
          v-if="voidError"
          class="entry-void-error"
        >
          {{ voidError }}<span v-if="voidErrorId">{{ t('common.errorIdSuffix', { id: voidErrorId }) }}</span>
        </p>
        <div class="entry-void-actions">
          <van-button
            class="entry-void-cancel"
            @click="closeVoidDialog"
          >
            {{ t('common.cancel') }}
          </van-button>
          <van-button
            type="danger"
            :loading="voiding"
            :loading-text="t('common.saving')"
            @click="confirmVoid"
          >
            {{ t('entry.voidConfirm') }}
          </van-button>
        </div>
      </div>
    </van-popup>
  </section>
</template>

<style scoped>
.entry-success {
  display: grid;
  gap: 16px;
  justify-items: center;
  padding-top: 8px;
}

.entry-success-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--kcgl-color-primary);
}

.entry-success-card {
  display: grid;
  gap: 8px;
  justify-items: center;
  width: 100%;
  padding: 24px 16px;
}

.entry-success-label {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.entry-success-code {
  margin: 0;
  font-size: 2.2rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  font-family: 'Courier New', monospace;
  color: var(--kcgl-color-text);
}

.entry-success-count {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.entry-success-upload {
  margin: 0;
  font-size: 0.85rem;
}

.entry-success-upload.is-active {
  color: var(--kcgl-color-info-text);
}

.entry-success-upload.is-done {
  color: var(--kcgl-color-success);
}

.entry-success-upload.is-failed {
  color: var(--kcgl-color-danger);
}

.entry-success-qr {
  margin-top: 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
}

.entry-success-guide {
  width: 100%;
}

.entry-success-continue {
  margin-top: 8px;
  width: 100%;
}

/* 危险操作走次级文字按钮：不与主流程（继续录入）争夺视觉焦点 */
.entry-success-void {
  border: none;
  background: none;
  padding: 4px 12px;
  font: inherit;
  font-size: 0.85rem;
  color: var(--kcgl-color-danger);
  text-decoration: underline;
  cursor: pointer;
}

.entry-void {
  display: grid;
  gap: 12px;
  padding: 20px 16px 24px;
}

.entry-void-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
}

.entry-void-code {
  margin: 0;
  font-size: 1.4rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  font-family: 'Courier New', monospace;
  color: var(--kcgl-color-danger);
}

.entry-void-note {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
  line-height: 1.6;
}

.entry-void-reason :deep(textarea) {
  font-size: 16px;
}

.entry-void-warn {
  margin: 0;
  padding: 8px 12px;
  font-size: 0.8rem;
  line-height: 1.6;
  color: var(--kcgl-color-danger);
  background: var(--kcgl-color-danger-bg);
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: 4px;
}

.entry-void-error {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-danger);
}

.entry-void-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.entry-void-cancel,
.entry-void-actions :deep(.van-button--default) {
  flex: 1;
}

.entry-void-actions :deep(.van-button) {
  flex: 1;
}
</style>
