<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { fetchItemImages } from '@/utils/api'
import { MIN_DATE, fromPickerValues, jstTodayEnd, toPickerValues, todayJst } from './entryShared'

/**
 * 照片字段（docs/01 7.5「先存后传」+ M2-6 重录继承）：选图即压缩落 Dexie（pending_bind），
 * 保存成功后由表单主体 bindItem 绑到新商品、后台续传。
 *
 * 从 EntryForm 抽出来的（D-148）。**不自带 onMounted**：重录时要读的原件图片由父组件在
 * 自己的 onMounted 里调 loadInherited()——挂载时机只由装配处决定，本组件在树里怎么摆、
 * 加载次数都不变（同 ItemListTab / ItemRecycleTab 那条规矩）。
 *
 * 上传队列是全局 composable（useUploadQueue），入队在这里、绑件在表单主体，各调一次，
 * 不需要经父组件传递。对外只有两件事：loadInherited() 与提交时要的撮影日。
 */

/** 重录源 id：非空时把原件已有图片读回来只读展示（保存时由服务端整组复制）。 */
const props = withDefaults(defineProps<{ reEntryId?: number | null }>(), { reEntryId: null })

const { t } = useI18n()
const uploadQueue = useUploadQueue()

// ------------------------------------------------------------------ 照片（7.5 先存后传）

const MAX_PHOTOS = 9
type PhotoSource = 'camera' | 'album'

interface LocalPhoto {
  clientUuid: string
  previewUrl: string
  source: PhotoSource
}

const photos = ref<LocalPhoto[]>([])
const photoDate = ref('')
const photoError = ref<string | null>(null)
const showPhotoDatePicker = ref(false)
const cameraInput = ref<HTMLInputElement | null>(null)
const albumInput = ref<HTMLInputElement | null>(null)
/** 撮影日上限=今天（后端同口径校验）。 */
const photoDateMax = jstTodayEnd()

async function onFilesChosen(event: Event, source: PhotoSource): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // 允许再次选择同一张
  if (files.length === 0) {
    return
  }
  if (photos.value.length + files.length > MAX_PHOTOS) {
    photoError.value = t('entry.photoLimit')
    return
  }
  photoError.value = null
  try {
    // 压缩（≤0.3MB/1920px）后即刻入 Dexie 队列——崩溃/刷新不丢
    const entries = await uploadQueue.addFiles(files)
    for (const entry of entries) {
      photos.value.push({
        clientUuid: entry.clientUuid,
        previewUrl: URL.createObjectURL(new Blob([entry.data], { type: entry.mimeType })),
        source,
      })
    }
    // 拍照=撮影日=今天（7.5）；相册不默认今天（EXIF 自动读取为 D-034 决策延后项）
    if (source === 'camera' && photoDate.value === '') {
      photoDate.value = todayJst()
    }
  } catch {
    photoError.value = t('entry.photoReadFailed')
  }
}

async function removePhoto(clientUuid: string): Promise<void> {
  const index = photos.value.findIndex((photo) => photo.clientUuid === clientUuid)
  if (index === -1) {
    return
  }
  URL.revokeObjectURL(photos.value[index]!.previewUrl)
  photos.value.splice(index, 1)
  await uploadQueue.removeUnbound(clientUuid)
}

onUnmounted(() => {
  for (const photo of photos.value) {
    URL.revokeObjectURL(photo.previewUrl)
  }
})

// ------------------------------------------------------------------ 重录继承图片（M2-6）

/** 原件已上传图片（只读展示）：保存时由服务端复制行到新商品，不进本地 Dexie、无需重拍。 */
const inheritedImages = ref<Array<{ id: number; thumbUrl: string }>>([])

/**
 * 重录时读回原件已有的图片（只读展示）。由父组件的 onMounted 调用——本组件不自带
 * onMounted。读回失败不阻断重录：服务端复制不依赖前端展示，新件仍会带上图片。
 */
async function loadInherited(): Promise<void> {
  if (props.reEntryId == null) {
    return
  }
  try {
    const images = await fetchItemImages(props.reEntryId)
    inheritedImages.value = images.map((image) => ({ id: image.id, thumbUrl: image.thumbUrl }))
  } catch {
    // 读回失败不阻断重录：服务端复制不依赖前端展示，新件仍会带上图片
  }
}

const photoDateDisplay = computed(() => (photoDate.value ? photoDate.value.replaceAll('-', '/') : ''))

function onPhotoDateConfirm({ selectedValues }: { selectedValues: Array<string | number> }): void {
  photoDate.value = fromPickerValues(selectedValues)
  showPhotoDatePicker.value = false
}

/** 有照片时随保存提交撮影日（拍照自动=当天，可改；无照片不提交）。 */
function photoDateForPayload(): string | undefined {
  return photos.value.length > 0 && photoDate.value !== '' ? photoDate.value : undefined
}

// ------------------------------------------------------------- 对外接口

defineExpose({ loadInherited, photoDateForPayload })
</script>

<template>
  <div class="entry-photo-field">
    <van-cell-group
      v-if="inheritedImages.length > 0"
      inset
      class="entry-inherited"
    >
      <van-cell :title="t('entry.inheritedImages')" />
      <div class="entry-inherited-photos">
        <img
          v-for="image in inheritedImages"
          :key="image.id"
          :src="image.thumbUrl"
          :alt="t('entry.inheritedImages')"
        >
      </div>
      <p class="entry-inherited-note">
        {{ t('entry.inheritedImagesNote') }}
      </p>
    </van-cell-group>

    <van-cell-group inset>
      <van-field :label="t('entry.photos')">
        <template #input>
          <div class="entry-photos">
            <div
              v-for="photo in photos"
              :key="photo.clientUuid"
              class="entry-photo"
            >
              <img
                :src="photo.previewUrl"
                :alt="t('entry.photos')"
              >
              <button
                type="button"
                class="entry-photo-remove"
                :aria-label="t('entry.photoRemove')"
                @click="removePhoto(photo.clientUuid)"
              >
                ×
              </button>
            </div>
            <span
              v-if="photos.length > 0"
              class="entry-photo-count"
            >{{ photos.length }}/9</span>
          </div>
        </template>
      </van-field>
      <div class="entry-photo-actions">
        <button
          type="button"
          class="kcgl-btn entry-photo-btn"
          @click="cameraInput?.click()"
        >
          {{ t('entry.photoCamera') }}
        </button>
        <button
          type="button"
          class="kcgl-btn entry-photo-btn"
          @click="albumInput?.click()"
        >
          {{ t('entry.photoAlbum') }}
        </button>
      </div>
      <p
        v-if="photoError"
        class="entry-photo-error"
      >
        {{ photoError }}
      </p>
      <p class="entry-photo-hint">
        {{ t('entry.photosHint') }}
      </p>
      <van-field
        :model-value="photoDateDisplay"
        :label="t('entry.photoDate')"
        :placeholder="t('entry.photoDatePlaceholder')"
        class="entry-photo-date"
        readonly
        is-link
        name="photoDate"
        @click="showPhotoDatePicker = true"
      />
      <van-popup
        v-model:show="showPhotoDatePicker"
        position="bottom"
        round
      >
        <van-date-picker
          :model-value="toPickerValues(photoDate)"
          :min-date="MIN_DATE"
          :max-date="photoDateMax"
          :title="t('entry.photoDate')"
          @confirm="onPhotoDateConfirm"
          @cancel="showPhotoDatePicker = false"
        />
      </van-popup>
    </van-cell-group>

    <!-- iOS 相机直启（capture=environment）与相册多选分开两个入口：撮影日来源语义 -->
    <input
      ref="cameraInput"
      type="file"
      accept="image/jpeg,image/png"
      capture="environment"
      hidden
      @change="onFilesChosen($event, 'camera')"
    >
    <input
      ref="albumInput"
      type="file"
      accept="image/jpeg,image/png"
      multiple
      hidden
      @change="onFilesChosen($event, 'album')"
    >
  </div>
</template>

<style scoped>
/* 只有一个透明包裹层：给这段多根片段（两个 cell-group + 两个隐藏 input）一个单根。
   本就不带任何盒模型，故不改变外层表单的排布。 */
.entry-photo-field {
  display: block;
}

/* 照片区：缩略图 56px + 删除角标（触控目标 ≥44px 由按钮整体承担，角标为可点区域中心） */
.entry-photos {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.entry-photo {
  position: relative;
  width: 56px;
  height: 56px;
}

.entry-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--kcgl-color-border);
  display: block;
}

.entry-photo-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 0.8rem;
  line-height: 1;
  cursor: pointer;
  transition: transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 删除角标视觉必须保持 20px（放大就盖住缩略图），命中区靠透明覆盖层撑到 ≥44px */
.entry-photo-remove::after {
  content: '';
  position: absolute;
  inset: -12px;
}

.entry-photo-remove:active {
  transform: translateY(1px);
}

.entry-photo-count {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.entry-photo-actions {
  display: flex;
  gap: 8px;
  padding: 0 16px 8px;
}

/* 不再覆盖高度：回到 .kcgl-btn 的 44px 触控下限（覆盖前是 36px，手机偏小） */
.entry-photo-btn {
  flex: 1;
  font-size: 0.9rem;
}

/* 按压反馈：1px 下沉（.kcgl-btn 已含 transform 过渡） */
.entry-photo-btn:active {
  transform: translateY(1px);
}

.entry-photo-error {
  margin: 0 16px 4px;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

.entry-photo-hint {
  margin: 0 16px 8px;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

/* 继承图片：只读缩略图（不可删——服务端保存时整组复制到新件） */
.entry-inherited-photos {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 16px 8px;
}

.entry-inherited-photos img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--kcgl-color-border);
  display: block;
}

.entry-inherited-note {
  margin: 0 16px 12px;
  font-size: 0.9rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}
</style>
