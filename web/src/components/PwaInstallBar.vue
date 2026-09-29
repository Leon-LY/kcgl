<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePwaInstall } from '@/composables/usePwaInstall'
import { useUploadQueue } from '@/composables/useUploadQueue'

/**
 * 主屏安装引导条（M6-①，docs/01 7.5）：
 * - guide（可关闭）：非 standalone 且未「後で」——安装步骤引导（iOS 手动步骤/
 *   Chrome 原生安装按钮）。
 * - warning（不可关闭）：照片队列非空且未安装——ITP 7 天清 IndexedDB 的数据
 *   风险提示，风险存续期间持续呈现（docs/01「页头持续警示条」）。
 * 双壳共用；warning 优先于 guide。桌面壳传 warningOnly（桌面无照片录入路径，
 * 安装引导属移动端数据安全叙事，桌面只保留未送信警示）。
 */
const props = withDefaults(defineProps<{ warningOnly?: boolean }>(), { warningOnly: false })

const { t } = useI18n()
const pwa = usePwaInstall()
const uploadQueue = useUploadQueue()

const pendingCount = computed(
  () =>
    uploadQueue.state.waitingCount +
    uploadQueue.state.uploadingCount +
    uploadQueue.state.unboundCount,
)

const mode = computed<'hidden' | 'guide' | 'warning'>(() => {
  if (pwa.state.standalone) {
    return 'hidden'
  }
  if (pendingCount.value > 0) {
    return 'warning'
  }
  return !props.warningOnly && pwa.shouldShowGuide.value ? 'guide' : 'hidden'
})

async function onInstall(): Promise<void> {
  await pwa.promptInstall()
}

onMounted(() => {
  pwa.init()
  void uploadQueue.init()
})
</script>

<template>
  <aside
    v-if="mode !== 'hidden'"
    class="pwa-bar"
    :class="`is-${mode}`"
    data-testid="pwa-bar"
  >
    <template v-if="mode === 'warning'">
      <p class="pwa-bar-title">
        {{ t('pwa.warning.title', { n: pendingCount }) }}
      </p>
      <p class="pwa-bar-body">
        {{ t('pwa.warning.body') }}
      </p>
      <p class="pwa-bar-steps">
        {{ pwa.isIos ? t('pwa.guide.ios') : t('pwa.guide.generic') }}
      </p>
    </template>
    <template v-else>
      <p class="pwa-bar-title">
        {{ t('pwa.guide.title') }}
      </p>
      <p class="pwa-bar-body">
        {{ t('pwa.guide.body') }}
      </p>
      <p class="pwa-bar-steps">
        {{ pwa.isIos ? t('pwa.guide.ios') : t('pwa.guide.generic') }}
      </p>
      <div class="pwa-bar-actions">
        <button
          v-if="pwa.state.canPrompt"
          type="button"
          class="pwa-bar-install"
          @click="onInstall"
        >
          {{ t('pwa.guide.install') }}
        </button>
        <button
          type="button"
          class="pwa-bar-dismiss"
          @click="pwa.dismissGuide()"
        >
          {{ t('pwa.guide.later') }}
        </button>
      </div>
    </template>
  </aside>
</template>

<style scoped>
.pwa-bar {
  display: grid;
  gap: 4px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--kcgl-color-border);
  font-size: 0.8rem;
  line-height: 1.5;
}

.pwa-bar.is-guide {
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary-dark);
}

.pwa-bar.is-warning {
  background: var(--kcgl-color-warning-bg);
  border-bottom-color: var(--kcgl-color-warning-border);
  color: var(--kcgl-color-warning);
}

.pwa-bar-title {
  margin: 0;
  font-weight: 600;
}

.pwa-bar-body {
  margin: 0;
}

.pwa-bar-steps {
  margin: 0;
  color: inherit;
  opacity: 0.85;
}

.pwa-bar-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.pwa-bar-install {
  min-height: 32px;
  padding: 0 16px;
  border: none;
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-primary);
  color: #fff;
  font: inherit;
  font-weight: 500;
  cursor: pointer;
}

.pwa-bar-dismiss {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font: inherit;
  cursor: pointer;
}
</style>
