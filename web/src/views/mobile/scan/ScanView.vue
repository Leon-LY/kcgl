<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { QrcodeStream } from 'vue-qrcode-reader'
import type { DetectedBarcode } from 'vue-qrcode-reader'
import { useAuthStore } from '@/stores/auth'
import { beep, createScanGate, vibrate } from '@/composables/useScan'
import { CAMERA_CONSTRAINTS, FORMATS, useQrCamera } from '@/composables/useQrCamera'
import type { ScanAction } from '@/utils/inventoryActions'
import { formatJstDate } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { normalizeItemCode } from '@/utils/normalize'
import { fetchItemByCode } from '@/utils/api'
import type { ItemByCode } from '@/utils/api'
import { ApiError } from '@/utils/api'
import ScanActionPanel from './ScanActionPanel.vue'

/**
 * 扫码操作页（/scan，M3-④，docs/01 4.3）：后置摄像头扫管理号 QR → 定位卡
 * （缩略图/仓库/状态/备注一次往返直出，by-code 带 thumbUrl）→ 按当前状态只渲染
 * 合法动作（inventoryActions=后端边表前端镜像）→ 动作弹层（卖出=落札价选填/
 * 报废=理由必填/调拨=仓选择/退货=说明选填/上架与取消标记=仅确认）。
 * 幂等契约（docs/01 7.0）：动作×商品幂等键生成后保留到成功为止，失败重试复用
 * 同键（服务端读回原结果 200 出清）。手动输入兜底（NFKC 归一仅在提交时——
 * 输入中转换会打断日文 IME）。作废件提示重录新号（docs/01 7.1）。
 */

const DONE_BANNER_MS = 4000

const { t } = useI18n()
const auth = useAuthStore()

const canAct = computed(() => auth.me != null && auth.me.role <= 2)

// ------------------------------------------------------------- 摄像头（QR 连续取流）

/** 取流状态与回调走共用件（D-149）；暂停的诱因留在本页（见 cameraPaused）。 */
const {
  cameraReady,
  torchOn,
  torchSupported,
  cameraErrorMessage,
  onCameraOn,
  onCameraOff,
  onCameraError,
  toggleTorch,
} = useQrCamera()

// ------------------------------------------------------------- 定位（扫码 + 手输兜底）

const gate = createScanGate()
const item = ref<ItemByCode | null>(null)
const locating = ref(false)
const locateError = ref('')

async function locate(code: string): Promise<void> {
  if (locating.value) {
    return
  }
  locating.value = true
  locateError.value = ''
  try {
    item.value = await fetchItemByCode(code)
  } catch (error) {
    item.value = null
    locateError.value =
      error instanceof ApiError && error.code === 404001
        ? t('scan.notFound')
        : toDisplayMessage(error, t)
  } finally {
    locating.value = false
  }
}

function onDetect(detectedCodes: DetectedBarcode[]): void {
  const code = normalizeItemCode(detectedCodes[0]?.rawValue ?? '')
  if (code === '' || !gate.accept(code)) {
    return
  }
  beep()
  vibrate()
  void locate(code)
}

const manualInput = ref('')

async function onManualSubmit(): Promise<void> {
  const code = normalizeItemCode(manualInput.value)
  if (code === '' || locating.value) {
    return
  }
  await locate(code)
  if (locateError.value === '') {
    manualInput.value = ''
  }
}

// ------------------------------------------------------------- 动作面板接线

/** 动作面板（ScanActionPanel）自持 useItemActions 的全部状态与 Esc 键监听（D-151）；
 *  本页只留两件：它开着时暂停取流（暂停的诱因归页面，D-149），以及成功后亮横幅+重读卡。 */
const cameraPaused = ref(false)

/** 动作成功（面板回报动作名与件号）：亮横幅并按服务端口径重读定位卡。 */
async function onActionDone(action: ScanAction, itemCode: string): Promise<void> {
  showDone(action)
  await refreshCard(itemCode)
}

/** 动作成功后重读定位卡（现态+动作菜单随之刷新）；重读失败保留旧卡不打断操作流。 */
async function refreshCard(code: string): Promise<void> {
  try {
    item.value = await fetchItemByCode(code)
  } catch {
    // 下次扫码/动作会重见真实态；此处静默降级
  }
  gate.reset()
}

// ------------------------------------------------------------- 成功横幅

const doneBanner = ref('')
let doneTimer = 0

function showDone(action: ScanAction): void {
  doneBanner.value = t(`scan.done.${action}`)
  if (doneTimer !== 0) {
    window.clearTimeout(doneTimer)
  }
  doneTimer = window.setTimeout(() => {
    doneBanner.value = ''
  }, DONE_BANNER_MS)
}

const route = useRoute()

onMounted(() => {
  // 深链定位（?code=）：出荷待ち「この商品を売り上げる」等入口跳转进来
  // 即定位该件，动作菜单直出（与手动输入同一路径，仅免敲码）
  const linked = typeof route.query.code === 'string' ? normalizeItemCode(route.query.code) : ''
  if (linked !== '') {
    void locate(linked)
  }
})

onBeforeUnmount(() => {
  if (doneTimer !== 0) {
    window.clearTimeout(doneTimer)
  }
})
</script>

<template>
  <section class="scan-view">
    <h1 class="scan-title">
      {{ t('scan.title') }}
    </h1>

    <div class="scan-camera">
      <QrcodeStream
        :constraints="CAMERA_CONSTRAINTS"
        :formats="FORMATS"
        :paused="cameraPaused"
        :torch="torchOn"
        @detect="onDetect"
        @camera-on="onCameraOn"
        @camera-off="onCameraOff"
        @error="onCameraError"
      />
      <button
        v-if="torchSupported"
        type="button"
        class="scan-torch"
        :class="{ 'is-on': torchOn }"
        :aria-pressed="torchOn"
        @click="toggleTorch"
      >
        {{ t('scan.torch') }}
      </button>
      <p
        v-if="cameraReady && item == null && cameraErrorMessage === ''"
        class="scan-guide"
      >
        {{ t('scan.cameraGuide') }}
      </p>
    </div>

    <p
      v-if="cameraErrorMessage"
      class="kcgl-info-box"
    >
      {{ cameraErrorMessage }}
    </p>

    <form
      class="kcgl-card scan-manual"
      @submit.prevent="onManualSubmit"
    >
      <label
        class="kcgl-label"
        for="scan-manual-input"
      >
        {{ t('scan.manualLabel') }}
      </label>
      <div class="scan-manual-row">
        <input
          id="scan-manual-input"
          v-model="manualInput"
          class="kcgl-input"
          type="text"
          :placeholder="t('scan.manualPlaceholder')"
          autocomplete="off"
          :disabled="locating"
        >
        <button
          type="submit"
          class="kcgl-btn kcgl-btn-primary"
          :disabled="locating || normalizeItemCode(manualInput) === ''"
        >
          {{ locating ? t('common.loading') : t('scan.manualSubmit') }}
        </button>
      </div>
    </form>

    <p
      v-if="locateError"
      class="scan-error"
      role="alert"
    >
      {{ locateError }}
    </p>

    <div
      v-if="doneBanner"
      class="scan-done"
      role="status"
    >
      {{ doneBanner }}
    </div>

    <div
      v-if="item"
      class="kcgl-card scan-card"
    >
      <div class="scan-card-head">
        <span class="scan-thumb">
          <img
            v-if="item.thumbUrl"
            :src="item.thumbUrl"
            alt=""
          >
        </span>
        <div class="scan-card-main">
          <p class="scan-code">
            {{ item.item.itemCode }}
          </p>
          <p class="scan-meta">
            {{ t(`common.warehouse.${item.item.warehouse}`) }}
            <span class="scan-meta-sep">｜</span>
            {{ t('scan.buyDate', { date: formatJstDate(item.item.buyDate) }) }}
          </p>
          <p class="scan-tags">
            <span class="scan-tag">{{ t(`scan.stock.${item.item.stockStatus}`) }}</span>
            <span class="scan-tag">{{ t(`scan.sale.${item.item.saleStatus}`) }}</span>
            <span
              v-if="item.item.voided"
              class="scan-tag is-danger"
            >{{ t('scan.voidedTag') }}</span>
            <span
              v-if="item.item.deleted"
              class="scan-tag is-danger"
            >{{ t('scan.deletedTag') }}</span>
          </p>
        </div>
      </div>
      <p
        v-if="item.item.remark"
        class="scan-remark"
      >
        {{ item.item.remark }}
      </p>
      <p
        v-if="item.item.voided"
        class="kcgl-info-box scan-warn"
      >
        {{
          item.reEntry
            ? t('scan.voidedWithReEntry', { code: item.reEntry.itemCode })
            : t('scan.voidedNoReEntry')
        }}
      </p>
      <p
        v-if="item.item.deleted"
        class="kcgl-info-box scan-warn"
      >
        {{ t('scan.deletedNote') }}
      </p>
      <p
        v-if="!canAct"
        class="kcgl-info-box"
      >
        {{ t('scan.viewerNote') }}
      </p>
    </div>

    <ScanActionPanel
      :item="item"
      :can-act="canAct"
      @update:open="cameraPaused = $event"
      @done="onActionDone"
    />
  </section>
</template>

<style scoped>
/* 内容列宽由移动壳统一持有（--kcgl-content-width），视图根不再自设限宽/居中：
   否则窄壳与平板档各自算一套宽度，手机档被夹成"缩小版 PC"（docs/07 §1 设备三档）。 */
.scan-view {
  display: grid;
  gap: 12px;
}

.scan-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.scan-camera {
  position: relative;
  aspect-ratio: 4 / 3;
  max-height: 46vh;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.scan-camera :deep(video) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.scan-torch {
  position: absolute;
  right: 10px;
  bottom: 10px;
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: rgba(31, 35, 41, 0.55);
  color: #fff;
  font: inherit;
  font-size: 0.9rem;
  cursor: pointer;
  transition: transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 触控目标 ≥44px，但视觉保持紧凑：按钮压在取景框上，放大视觉会遮住扫码区；
   故不撑大本体的盒子，改用透明 ::after 外扩热区（36 + 4×2 = 44）。 */
.scan-torch::after {
  content: '';
  position: absolute;
  inset: -4px;
}

/* 触屏没有 hover，按压态是唯一反馈：1px 下沉（对齐 .kcgl-btn-primary:active）。 */
.scan-torch:active {
  transform: translateY(1px);
}

.scan-torch.is-on {
  background: var(--kcgl-color-primary);
  border-color: var(--kcgl-color-primary);
}

.scan-guide {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 12px;
  margin: 0;
  padding: 6px 12px;
  text-align: center;
  color: #fff;
  font-size: 0.9rem;
  text-shadow: 0 0 4px rgba(31, 35, 41, 0.7);
  pointer-events: none;
}

.scan-manual {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
}

.scan-manual-row {
  display: flex;
  gap: 8px;
}

.scan-manual-row .kcgl-input {
  flex: 1;
  min-width: 0;
}

.scan-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.9rem;
}

.scan-done {
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-success-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
  font-size: 0.9rem;
  font-weight: 600;
}

.scan-card {
  display: grid;
  gap: 10px;
  padding: 16px;
}

.scan-card-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.scan-thumb {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.scan-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.scan-card-main {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 4px;
}

.scan-code {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  word-break: break-all;
}

.scan-meta {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.scan-meta-sep {
  color: var(--kcgl-color-text-faint);
}

.scan-tags {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.scan-tag {
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.scan-tag.is-danger {
  border-color: var(--kcgl-color-danger-border);
  color: var(--kcgl-color-danger);
}

.scan-remark {
  margin: 0;
  font-size: 0.9rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
  word-break: break-all;
}

.scan-warn {
  font-size: 0.9rem;
}

</style>
