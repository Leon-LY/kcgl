<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { deleteItemImage, fetchItemImages, reorderItemImages } from '@/utils/api'
import type { ImageUploadResult } from '@/utils/api'

/**
 * 详情页照片区（D5 解绑/重排，D-116 落地的能力，本组件自 ItemDetailView 抽出）。
 *
 * 自持取图与操作态，父组件只传 itemId + canEdit——图片不依赖商品字段，
 * 故换 itemId 才重取（父组件换路由参数时本组件随之重挂）。
 * 第 1 张为列表封面：顺序即封面，故重排=改封面。
 */

const props = defineProps<{
  itemId: number
  canEdit: boolean
}>()

const { t } = useI18n()

const images = ref<ImageUploadResult[]>([])
/** 照片区仅在成功取回后渲染（取图失败静默降级——不该阻断详情主体）。 */
const photosAvailable = ref(false)
const imageUrls = computed(() => images.value.map((x) => x.url))

async function loadImages(): Promise<void> {
  try {
    images.value = await fetchItemImages(props.itemId)
    photosAvailable.value = true
  } catch {
    // 静默降级：详情主体不受照片接口失败影响
  }
}

const photoBusy = ref(false)
const photoError = ref('')

/**
 * 前移/后移一位：本地先换位（即时反馈）再整表落库，失败回滚到点击前顺序——
 * 服务端要全量一致集合，故每次提交都带完整顺序（见 api#reorderItemImages）。
 * photoBusy 期间禁用全部照片操作，杜绝「回滚覆盖掉并发结果」的窗口。
 */
async function moveImage(index: number, delta: number): Promise<void> {
  if (photoBusy.value) return
  const next = index + delta
  if (next < 0 || next >= images.value.length) return
  const before = images.value
  const reordered = [...before]
  const [moved] = reordered.splice(index, 1)
  reordered.splice(next, 0, moved)
  images.value = reordered
  photoBusy.value = true
  photoError.value = ''
  try {
    await reorderItemImages(props.itemId, reordered.map((x) => x.id))
  } catch (error) {
    images.value = before
    photoError.value = toDisplayMessage(error, t)
  } finally {
    photoBusy.value = false
  }
}

const photoDeleteTarget = ref<ImageUploadResult | null>(null)
const photoDeleteBusy = ref(false)

function openPhotoDelete(target: ImageUploadResult): void {
  photoDeleteTarget.value = target
  photoDeleteBusy.value = false
  photoError.value = ''
}

async function onPhotoDeleteSubmit(): Promise<void> {
  const target = photoDeleteTarget.value
  if (target == null || photoDeleteBusy.value) return
  photoDeleteBusy.value = true
  try {
    await deleteItemImage(target.id)
    images.value = images.value.filter((x) => x.id !== target.id)
    photoDeleteTarget.value = null
  } catch (error) {
    photoError.value = toDisplayMessage(error, t)
  } finally {
    photoDeleteBusy.value = false
  }
}

onMounted(() => {
  void loadImages()
})

// 同一详情组件复用跳转（/items/5 → /items/12）→ itemId 变，重取该件图片
watch(() => props.itemId, () => {
  void loadImages()
})
</script>

<template>
  <div
    v-if="photosAvailable"
    class="itemd-photos-wrap"
  >
    <p class="itemd-photos-title">
      {{ t('items.detail.photoCount', { n: images.length }) }}
    </p>
    <div
      v-if="images.length > 0"
      class="itemd-photos"
    >
      <div
        v-for="(img, index) in images"
        :key="img.id"
        class="itemd-photo-item"
      >
        <el-image
          :src="img.thumbUrl"
          :preview-src-list="imageUrls"
          :initial-index="index"
          preview-teleported
          fit="cover"
          class="itemd-photo"
        />
        <!-- 第 1 张为列表封面：顺序即封面，故重排=改封面（D5） -->
        <div
          v-if="canEdit"
          class="itemd-photo-tools"
        >
          <button
            type="button"
            class="itemd-photo-tool"
            :disabled="photoBusy || index === 0"
            :aria-label="t('items.detail.photoMovePrev')"
            :title="t('items.detail.photoMovePrev')"
            @click="moveImage(index, -1)"
          >
            ←
          </button>
          <button
            type="button"
            class="itemd-photo-tool"
            :disabled="photoBusy || index === images.length - 1"
            :aria-label="t('items.detail.photoMoveNext')"
            :title="t('items.detail.photoMoveNext')"
            @click="moveImage(index, 1)"
          >
            →
          </button>
          <button
            type="button"
            class="itemd-photo-tool itemd-photo-remove"
            :disabled="photoBusy"
            :aria-label="t('items.detail.photoDelete')"
            :title="t('items.detail.photoDelete')"
            @click="openPhotoDelete(img)"
          >
            ×
          </button>
        </div>
      </div>
    </div>
    <p
      v-else
      class="itemd-nophoto"
    >
      {{ t('items.detail.noPhoto') }}
    </p>
    <p
      v-if="photoError"
      class="itemd-photo-error"
      role="alert"
    >
      {{ photoError }}
    </p>
  </div>

  <el-dialog
    :model-value="photoDeleteTarget != null"
    :title="t('items.detail.photoDeleteTitle')"
    width="440px"
    :close-on-click-modal="!photoDeleteBusy"
    @update:model-value="
      (open: boolean) => {
        if (!open && !photoDeleteBusy) photoDeleteTarget = null
      }
    "
  >
    <div class="kcgl-form">
      <img
        v-if="photoDeleteTarget"
        :src="photoDeleteTarget.thumbUrl"
        alt=""
        class="itemd-photo-preview"
      >
      <p class="kcgl-form-note">
        {{ t('items.detail.photoDeleteNote') }}
      </p>
    </div>
    <template #footer>
      <el-button
        :disabled="photoDeleteBusy"
        @click="photoDeleteTarget = null"
      >
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="danger"
        :loading="photoDeleteBusy"
        @click="onPhotoDeleteSubmit"
      >
        {{ t('items.detail.photoDeleteConfirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.itemd-photos-wrap {
  margin-bottom: 16px;
}

.itemd-photos-title {
  margin: 0 0 8px;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.itemd-photos {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.itemd-photo-item {
  display: grid;
  gap: 4px;
  justify-items: center;
}

.itemd-photo {
  width: 72px;
  height: 72px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
}

.itemd-photo-tools {
  display: flex;
  gap: 2px;
}

.itemd-photo-tool {
  width: 22px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.75rem;
  line-height: 1;
  cursor: pointer;
}

.itemd-photo-tool:hover:not(:disabled) {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

.itemd-photo-tool:disabled {
  opacity: 0.4;
  cursor: default;
}

.itemd-photo-remove:hover:not(:disabled) {
  border-color: var(--kcgl-color-danger);
  color: var(--kcgl-color-danger);
}

.itemd-photo-error {
  margin: 8px 0 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-danger);
}

.itemd-photo-preview {
  width: 96px;
  height: 96px;
  object-fit: cover;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
}

.itemd-nophoto {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-faint);
}
</style>
