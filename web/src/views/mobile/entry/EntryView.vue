<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { useDictsStore } from '@/stores/dicts'
import { useEntrySessionStore } from '@/stores/entrySession'
import type { ItemResponse } from '@/utils/api'
import EntryForm from './EntryForm.vue'
import EntrySuccess from './EntrySuccess.vue'

/**
 * 连续录入页（/entry，E+）：字典加载与失败重试在视图层；
 * 表单/成功页组件按保存状态切换（成功后重挂表单取新沿用值）。
 * 挂载即初始化图片上传队列（启动扫描：遗留 uploading 复位 + 存量续传，7.5）。
 */

const { t } = useI18n()
const dicts = useDictsStore()
const session = useEntrySessionStore()
const uploadQueue = useUploadQueue()

const dictError = ref(false)
const savedItem = ref<ItemResponse | null>(null)
const savedPhotoCount = ref(0)

onMounted(() => {
  session.ensureToday()
  dictError.value = false
  dicts.ensureLoaded().catch(() => {
    dictError.value = true
  })
  void uploadQueue.init()
})

async function reloadDicts(): Promise<void> {
  dictError.value = false
  try {
    await dicts.reload()
  } catch {
    dictError.value = true
  }
}

function onSaved(item: ItemResponse, photoCount: number): void {
  session.recordSaved(item)
  savedItem.value = item
  savedPhotoCount.value = photoCount
}

function onContinue(): void {
  savedItem.value = null
  savedPhotoCount.value = 0
}
</script>

<template>
  <section class="entry-view">
    <div
      v-if="dictError"
      class="kcgl-error-box entry-dict-error"
    >
      <p>{{ t('entry.dictLoadFailed') }}</p>
      <button
        type="button"
        class="kcgl-btn kcgl-btn-primary entry-dict-retry"
        @click="reloadDicts"
      >
        {{ t('common.reload') }}
      </button>
    </div>

    <EntrySuccess
      v-else-if="savedItem"
      :key="savedItem.id"
      :item="savedItem"
      :today-count="session.todayCount"
      :photo-count="savedPhotoCount"
      @continue="onContinue"
    />

    <EntryForm
      v-else
      @saved="onSaved"
    />
  </section>
</template>

<style scoped>
.entry-view {
  max-width: 560px;
  margin: 0 auto;
}

.entry-dict-error {
  display: grid;
  gap: 12px;
}

.entry-dict-error p {
  margin: 0;
}

.entry-dict-retry {
  justify-self: start;
  font-size: 0.85rem;
  height: 36px;
}
</style>
