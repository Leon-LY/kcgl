<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import QRCode from 'qrcode'
import { useUploadQueue } from '@/composables/useUploadQueue'
import type { ItemResponse } from '@/utils/api'

/**
 * 录入成功页：大字管理号 + 本地二维码（离线可用，D-033 决策：qrcode 前端唯一实现）
 * + 本日计数 + 照片上传角标（7.5：保存后后台续传，弱网时角标持续显示）+ 抄号流程文案
 * （H3：现场无打印机，「录一件抄一件（笔）+回社贴一件（标签）」）。
 */

const props = defineProps<{ item: ItemResponse; todayCount: number; photoCount: number }>()
const emit = defineEmits<{ continue: [] }>()

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
}
</style>
