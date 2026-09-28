<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { QrcodeStream } from 'vue-qrcode-reader'
import type { BarcodeFormat, DetectedBarcode } from 'vue-qrcode-reader'
import { useAuthStore } from '@/stores/auth'
import { beep, createScanGate, vibrate } from '@/composables/useScan'
import { formatJstDate } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { normalizeItemCode } from '@/utils/normalize'
import { cancelStocktake, closeStocktake, fetchStocktake, scanStocktakeItem } from '@/utils/api'
import type { ItemResponse, StocktakeSummary } from '@/utils/api'
import { ApiError } from '@/utils/api'

/**
 * 盘点会话页（/stocktake/:id，M3-⑥）：进行中=扫码记录（repeated 不报错、
 * 他仓/冻结/非在库照记并卡内警示——差异在 close 后人工裁决）；close=冻结
 * 期望集合生成差异表（盘点期间的自然变动落入差异）；发起人可撤单（mine 仅
 * 渲染依据，服务端强校验）。已 close=引导差异确认；已确认/作废=只读摘要。
 */

const CAMERA_CONSTRAINTS = { facingMode: 'environment' }
const FORMATS: BarcodeFormat[] = ['qr_code']

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const canAct = computed(() => auth.me != null && auth.me.role <= 2)
const stocktakeId = computed(() => Number(route.params.id))

// ------------------------------------------------------------- 会话载入

const summary = ref<StocktakeSummary | null>(null)
const loadFailed = ref(false)
const notFound = ref(false)

async function loadSummary(): Promise<void> {
  loadFailed.value = false
  notFound.value = false
  try {
    summary.value = await fetchStocktake(stocktakeId.value)
  } catch (error) {
    summary.value = null
    if (error instanceof ApiError && error.code === 404001) {
      notFound.value = true
    } else {
      loadFailed.value = true
    }
  }
}

const isActive = computed(() => summary.value?.status === 0)
const isClosed = computed(() => summary.value?.status === 1)
const canScan = computed(() => canAct.value && isActive.value)

// ------------------------------------------------------------- 摄像头（QR 连续取流，同 ScanView）

const cameraReady = ref(false)
const torchOn = ref(false)
const torchSupported = ref(false)
const cameraErrorName = ref('')

/** camera-on 载荷结构化收窄（torch 非标准能力位，DOM lib 未收录；见 ScanView 同型注释）。 */
function onCameraOn(capabilities: object): void {
  cameraReady.value = true
  torchSupported.value = (capabilities as { torch?: boolean }).torch === true
  cameraErrorName.value = ''
}

function onCameraOff(): void {
  cameraReady.value = false
  torchSupported.value = false
  torchOn.value = false
}

function onCameraError(error: Error): void {
  cameraReady.value = false
  cameraErrorName.value = error.name
}

const cameraErrorMessage = computed(() => {
  switch (cameraErrorName.value) {
    case '':
      return ''
    case 'NotAllowedError':
    case 'PermissionDeniedError':
      return t('scan.cameraDenied')
    case 'InsecureContextError':
      return t('scan.cameraInsecure')
    default:
      return t('scan.cameraFailed')
  }
})

// ------------------------------------------------------------- 扫码记录

const gate = createScanGate()
const scannedCard = ref<{ code: string; thumbUrl: string | null; warningKey: string | null; repeated: boolean } | null>(null)
const scanning = ref(false)
const scanError = ref('')

/** 卡内警示派生（docs/01 7.3 照记不拦）：冻结品（不可调整）＞他仓＞系统非在库。 */
function warningKeyFor(item: ItemResponse, stocktake: StocktakeSummary): string | null {
  if (item.voided || item.deleted) return 'warnFrozen'
  if (item.warehouse !== stocktake.warehouse) return 'warnOtherWarehouse'
  if (item.stockStatus !== 1) return 'warnNotInStock'
  return null
}

async function record(code: string): Promise<void> {
  const current = summary.value
  if (current == null || scanning.value) return
  scanning.value = true
  scanError.value = ''
  try {
    const result = await scanStocktakeItem(current.id, code)
    // 本地乐观计数（close 时以服务端冻结值刷新）
    if (!result.repeated) {
      summary.value = { ...current, scannedCount: current.scannedCount + 1 }
    }
    scannedCard.value = {
      code: result.item.itemCode,
      thumbUrl: result.thumbUrl,
      warningKey: warningKeyFor(result.item, current),
      repeated: result.repeated,
    }
  } catch (error) {
    scannedCard.value = null
    scanError.value = toDisplayMessage(error, t)
  } finally {
    scanning.value = false
  }
}

function onDetect(detectedCodes: DetectedBarcode[]): void {
  const code = normalizeItemCode(detectedCodes[0]?.rawValue ?? '')
  if (code === '' || !gate.accept(code)) return
  beep()
  vibrate()
  void record(code)
}

const manualInput = ref('')

async function onManualSubmit(): Promise<void> {
  const code = normalizeItemCode(manualInput.value)
  if (code === '' || scanning.value) return
  await record(code)
  if (scanError.value === '') {
    manualInput.value = ''
  }
}

// ------------------------------------------------------------- close / cancel 弹层

const dialog = ref<'close' | 'cancel' | null>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const cameraPaused = computed(() => dialog.value != null)

function openDialog(kind: 'close' | 'cancel'): void {
  dialogError.value = ''
  dialog.value = kind
}

function closeDialog(): void {
  if (dialogBusy.value) return
  dialog.value = null
}

async function onDialogConfirm(): Promise<void> {
  const current = summary.value
  const kind = dialog.value
  if (current == null || kind == null || dialogBusy.value) return
  dialogBusy.value = true
  dialogError.value = ''
  try {
    if (kind === 'close') {
      summary.value = await closeStocktake(current.id)
      dialog.value = null
      await router.push({ name: 'stocktake-diffs', params: { id: current.id } })
    } else {
      await cancelStocktake(current.id)
      dialog.value = null
      await router.push({ name: 'stocktake' })
    }
  } catch (error) {
    dialogError.value = toDisplayMessage(error, t)
  } finally {
    dialogBusy.value = false
  }
}

// ------------------------------------------------------------- 键盘收尾

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && dialog.value != null) {
    closeDialog()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  void loadSummary()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <section class="session-view">
    <h1 class="session-title">
      {{ isActive ? t('stocktake.scan.title') : t('stocktake.title') }}
    </h1>

    <div
      v-if="notFound"
      class="kcgl-info-box"
    >
      {{ t('stocktake.scan.notFound') }}
    </div>
    <div
      v-else-if="loadFailed"
      class="session-error"
    >
      <p class="kcgl-info-box">
        {{ t('stocktake.loadFailed') }}
      </p>
      <button
        type="button"
        class="kcgl-btn"
        @click="loadSummary"
      >
        {{ t('common.reload') }}
      </button>
    </div>

    <template v-if="summary">
      <div class="kcgl-card session-summary">
        <p class="session-summary-no">
          {{ summary.stocktakeNo }}
        </p>
        <p class="session-summary-meta">
          <span
            class="session-status"
            :class="`is-status-${summary.status}`"
          >{{ t(`stocktake.status.${summary.status}`) }}</span>
          <span class="session-summary-wh">{{ t(`common.warehouse.${summary.warehouse}`) }}</span>
        </p>
        <p
          v-if="isActive"
          class="session-summary-count"
        >
          {{ t('stocktake.scan.scanned', { n: summary.scannedCount }) }}
        </p>
        <p
          v-else-if="isClosed"
          class="session-summary-count"
        >
          {{ t('stocktake.counts', { expected: summary.expectedCount ?? 0, scanned: summary.scannedCount }) }}
          ・{{ t('stocktake.pendingDiff', { n: summary.pendingDiffCount ?? 0 }) }}
        </p>
        <p class="session-summary-by">
          {{ t('stocktake.createdBy', { name: summary.createdByName, date: formatJstDate(summary.createdAt.slice(0, 10)) }) }}
        </p>
      </div>

      <p
        v-if="isClosed"
        class="kcgl-info-box session-closed-note"
      >
        {{ t('stocktake.scan.closedNote') }}
      </p>
      <button
        v-if="isClosed"
        type="button"
        class="kcgl-btn kcgl-btn-primary"
        @click="router.push({ name: 'stocktake-diffs', params: { id: summary.id } })"
      >
        {{ t('stocktake.scan.viewDiffs') }}
      </button>

      <p
        v-if="canScan"
        class="session-guide"
      >
        {{ t('stocktake.scan.guide') }}
      </p>

      <div
        v-if="canScan"
        class="session-camera"
      >
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
          class="session-torch"
          :class="{ 'is-on': torchOn }"
          :aria-pressed="torchOn"
          @click="torchOn = !torchOn"
        >
          {{ t('scan.torch') }}
        </button>
      </div>

      <p
        v-if="cameraErrorMessage"
        class="kcgl-info-box"
      >
        {{ cameraErrorMessage }}
      </p>

      <form
        v-if="canScan"
        class="kcgl-card session-manual"
        @submit.prevent="onManualSubmit"
      >
        <label
          class="kcgl-label"
          for="stocktake-manual-input"
        >
          {{ t('scan.manualLabel') }}
        </label>
        <div class="session-manual-row">
          <input
            id="stocktake-manual-input"
            v-model="manualInput"
            class="kcgl-input"
            type="text"
            :placeholder="t('scan.manualPlaceholder')"
            autocomplete="off"
            :disabled="scanning"
          >
          <button
            type="submit"
            class="kcgl-btn kcgl-btn-primary"
            :disabled="scanning || normalizeItemCode(manualInput) === ''"
          >
            {{ scanning ? t('common.loading') : t('scan.manualSubmit') }}
          </button>
        </div>
      </form>

      <p
        v-if="scanError"
        class="session-error"
        role="alert"
      >
        {{ scanError }}
      </p>

      <div
        v-if="scannedCard"
        class="kcgl-card session-card"
      >
        <div class="session-card-head">
          <span class="session-thumb">
            <img
              v-if="scannedCard.thumbUrl"
              :src="scannedCard.thumbUrl"
              alt=""
            >
          </span>
          <p class="session-card-main">
            {{ scannedCard.code }}
          </p>
        </div>
        <p
          v-if="scannedCard.repeated"
          class="kcgl-info-box session-card-note"
        >
          {{ t('stocktake.scan.repeated') }}
        </p>
        <p
          v-else-if="scannedCard.warningKey"
          class="kcgl-info-box session-card-note is-warning"
        >
          {{ t(`stocktake.scan.${scannedCard.warningKey}`) }}
        </p>
      </div>

      <p
        v-if="!canAct && isActive"
        class="kcgl-info-box"
      >
        {{ t('stocktake.viewerNote') }}
      </p>

      <div
        v-if="canScan"
        class="session-actions"
      >
        <button
          v-if="summary.mine"
          type="button"
          class="kcgl-btn session-cancel-btn"
          :disabled="dialogBusy"
          @click="openDialog('cancel')"
        >
          {{ t('stocktake.scan.cancel') }}
        </button>
        <button
          type="button"
          class="kcgl-btn kcgl-btn-primary"
          :disabled="dialogBusy"
          @click="openDialog('close')"
        >
          {{ t('stocktake.scan.close') }}
        </button>
      </div>
    </template>

    <Transition name="session-fade">
      <div
        v-if="dialog != null"
        class="session-overlay"
      >
        <div
          class="kcgl-card session-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="stocktake-dialog-title"
        >
          <h2
            id="stocktake-dialog-title"
            class="session-dialog-title"
          >
            {{ dialog === 'close' ? t('stocktake.scan.closeTitle') : t('stocktake.scan.cancelTitle') }}
          </h2>
          <p class="session-dialog-note">
            {{ dialog === 'close' ? t('stocktake.scan.closeNote') : t('stocktake.scan.cancelNote') }}
          </p>
          <p
            v-if="dialogError"
            class="session-dialog-error"
            role="alert"
          >
            {{ dialogError }}
          </p>
          <div class="session-dialog-actions">
            <button
              type="button"
              class="kcgl-btn session-dialog-cancel"
              :disabled="dialogBusy"
              @click="closeDialog"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="kcgl-btn kcgl-btn-primary"
              :disabled="dialogBusy"
              @click="onDialogConfirm"
            >
              {{
                dialogBusy
                  ? t('stocktake.scan.closing')
                  : dialog === 'close'
                    ? t('stocktake.scan.closeConfirm')
                    : t('stocktake.scan.cancelConfirm')
              }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </section>
</template>

<style scoped>
.session-view {
  max-width: 560px;
  margin: 0 auto;
  display: grid;
  gap: 12px;
}

.session-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.session-error {
  display: grid;
  gap: 8px;
  justify-items: start;
}

.session-summary {
  display: grid;
  gap: 6px;
  padding: 16px;
}

.session-summary-no {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.session-summary-meta {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.session-status {
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.session-status.is-status-0 {
  border-color: var(--kcgl-color-info-border);
  color: var(--kcgl-color-primary);
}

.session-status.is-status-2 {
  border-color: var(--kcgl-color-success-border);
  color: var(--kcgl-color-success);
}

.session-summary-wh {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.session-summary-count {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.session-summary-by {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.session-closed-note {
  font-size: 0.85rem;
}

.session-guide {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.session-camera {
  position: relative;
  aspect-ratio: 4 / 3;
  max-height: 42vh;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.session-camera :deep(video) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.session-torch {
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
  font-size: 0.8rem;
  cursor: pointer;
}

.session-torch.is-on {
  background: var(--kcgl-color-primary);
  border-color: var(--kcgl-color-primary);
}

.session-manual {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
}

.session-manual-row {
  display: flex;
  gap: 8px;
}

.session-manual-row .kcgl-input {
  flex: 1;
  min-width: 0;
}

.session-view > .session-error[role='alert'] {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
}

.session-card {
  display: grid;
  gap: 10px;
  padding: 16px;
}

.session-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.session-thumb {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.session-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.session-card-main {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  word-break: break-all;
}

.session-card-note {
  margin: 0;
  font-size: 0.85rem;
}

.session-card-note.is-warning {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.session-actions {
  display: flex;
  gap: 8px;
}

.session-cancel-btn {
  flex: 1;
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-weight: 500;
}

.session-actions .kcgl-btn-primary {
  flex: 2;
}

.session-overlay {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(31, 35, 41, 0.45);
}

.session-dialog {
  display: grid;
  gap: 12px;
  width: 100%;
  max-width: 360px;
  padding: 20px;
}

.session-dialog-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.session-dialog-note {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.session-dialog-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
}

.session-dialog-actions {
  display: flex;
  gap: 8px;
}

.session-dialog-cancel {
  flex: 1;
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-weight: 500;
}

.session-dialog-actions .kcgl-btn-primary {
  flex: 2;
}

.session-fade-enter-active,
.session-fade-leave-active {
  transition: opacity 0.15s ease;
}

.session-fade-enter-from,
.session-fade-leave-to {
  opacity: 0;
}
</style>
