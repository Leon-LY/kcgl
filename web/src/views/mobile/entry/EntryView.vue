<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { useDictsStore } from '@/stores/dicts'
import { useEntrySessionStore } from '@/stores/entrySession'
import { fetchItem } from '@/utils/api'
import type { ItemResponse } from '@/utils/api'
import EntryForm from './EntryForm.vue'
import EntrySuccess from './EntrySuccess.vue'

/**
 * 连续录入页（/entry，E+）：字典加载与失败重试在视图层；
 * 表单/成功页组件按保存状态切换（成功后重挂表单取新沿用值）。
 * 挂载即初始化图片上传队列（启动扫描：遗留 uploading 复位 + 存量续传，7.5）。
 * M2-6：成功页「取り消して再登録」→ 作废后携带原商品转重录模式（预填全字段）。
 * M5-①：商品详情页「作废」成功后跳 ?reEntry={id} 深链进入同款重录模式
 * （路由层不做参数语义——view 自取自清，失败静默回普通录入）。
 */

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const dicts = useDictsStore()
const session = useEntrySessionStore()
const uploadQueue = useUploadQueue()

const dictError = ref(false)
const savedItem = ref<ItemResponse | null>(null)
const savedPhotoCount = ref(0)
/** 重录源（已作废原件）：非空时表单以原件预填并携带 reEntryOf。 */
const reEntrySource = ref<ItemResponse | null>(null)

onMounted(() => {
  session.ensureToday()
  dictError.value = false
  dicts.ensureLoaded().catch(() => {
    dictError.value = true
  })
  void uploadQueue.init()
  loadReEntryFromQuery()
})

/** 详情页作废跳转深链（?reEntry={id}）：取原件预填后即刻清参（刷新/分享不留痕）。 */
function loadReEntryFromQuery(): void {
  const raw = route.query.reEntry
  const value = Array.isArray(raw) ? raw[0] : raw
  const id = value != null && value !== '' ? Number(value) : NaN
  if (value == null) {
    return
  }
  void router.replace({ query: {} })
  if (!Number.isInteger(id) || id <= 0) {
    return
  }
  fetchItem(id)
    .then((item) => {
      if (item.voided) {
        reEntrySource.value = item
      }
    })
    .catch(() => undefined) // 取件失败/非作废件：静默回普通录入（详情页已作废成功，属罕见态）
}

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
  reEntrySource.value = null
}

function onContinue(): void {
  savedItem.value = null
  savedPhotoCount.value = 0
}

/** 作废完成 → 转重录模式（表单重挂载以原件预填）。 */
function onReEntry(voidedItem: ItemResponse): void {
  savedItem.value = null
  savedPhotoCount.value = 0
  reEntrySource.value = voidedItem
}

/** 放弃重录（表单横幅「やめる」）→ 回普通录入。 */
function onCancelReEntry(): void {
  reEntrySource.value = null
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
      @re-entry="onReEntry"
    />

    <EntryForm
      v-else
      :re-entry="reEntrySource"
      @saved="onSaved"
      @cancel-re-entry="onCancelReEntry"
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
