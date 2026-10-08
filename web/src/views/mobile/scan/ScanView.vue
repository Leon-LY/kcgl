<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { QrcodeStream } from 'vue-qrcode-reader'
import type { BarcodeFormat, DetectedBarcode } from 'vue-qrcode-reader'
import { useAuthStore } from '@/stores/auth'
import { beep, createScanGate, vibrate } from '@/composables/useScan'
import { useItemActions } from '@/composables/useItemActions'
import { availableActions } from '@/utils/inventoryActions'
import type { ScanAction } from '@/utils/inventoryActions'
import { formatJstDate } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { normalizeItemCode } from '@/utils/normalize'
import { fetchItemByCode } from '@/utils/api'
import type { ItemByCode } from '@/utils/api'
import { ApiError } from '@/utils/api'

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
const CAMERA_CONSTRAINTS = { facingMode: 'environment' }
const FORMATS: BarcodeFormat[] = ['qr_code']

const { t } = useI18n()
const auth = useAuthStore()

const canAct = computed(() => auth.me != null && auth.me.role <= 2)

// ------------------------------------------------------------- 摄像头（QR 连续取流）

const cameraReady = ref(false)
const torchOn = ref(false)
const torchSupported = ref(false)
const cameraErrorName = ref('')

/**
 * camera-on 载荷为 Partial<MediaTrackCapabilities>；TS DOM lib 未收录非标准的
 * torch 能力位，且 .vue script 下 no-undef 不识别纯类型名——按 object 收参
 * （对组件事件签名逆变兼容）后结构化收窄读取（真机由 vue-qrcode-reader 回填）。
 */
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

// ------------------------------------------------------------- 动作菜单与弹层

const actions = computed(() => {
  const current = item.value
  if (current == null || current.item.voided || current.item.deleted || !canAct.value) {
    return []
  }
  return availableActions(current.item.stockStatus, current.item.saleStatus)
})

function actionLabel(action: ScanAction): string {
  return t(`scan.action.${action}`)
}

/**
 * 七动作的载荷/校验/幂等全部由 useItemActions 承担（与商品一覧行内动作同一份，
 * D-129）；本页只管「谁可作为目标」（扫码定位到的卡）与成功后的横幅+卡片重读。
 */
const {
  activeAction,
  busy: actionBusy,
  error: actionError,
  soldPriceInput,
  scrapReason,
  transferTo,
  returnNote,
  titleKey: dialogTitleKey,
  confirmKey: dialogConfirmKey,
  open: openDialog,
  close: closeAction,
  confirm: onActionConfirm,
} = useItemActions({
  onDone: async (action, result) => {
    showDone(action)
    await refreshCard(result.itemCode)
  },
})

/** 弹层打开时暂停取流：对话框操作中不换目标件。 */
const cameraPaused = computed(() => activeAction.value != null)

function openAction(action: ScanAction): void {
  const current = item.value
  if (current == null) {
    return
  }
  openDialog(action, current.item)
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

// ------------------------------------------------------------- 成功横幅与键盘收尾

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

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && activeAction.value != null) {
    closeAction()
  }
}

const route = useRoute()

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // 深链定位（?code=）：出荷待ち「この商品を売り上げる」等入口跳转进来
  // 即定位该件，动作菜单直出（与手动输入同一路径，仅免敲码）
  const linked = typeof route.query.code === 'string' ? normalizeItemCode(route.query.code) : ''
  if (linked !== '') {
    void locate(linked)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
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
        @click="torchOn = !torchOn"
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

    <div
      v-if="actions.length > 0"
      class="scan-actions"
    >
      <button
        v-for="action in actions"
        :key="action"
        type="button"
        class="scan-action"
        @click="openAction(action)"
      >
        {{ actionLabel(action) }}
      </button>
    </div>
    <p
      v-else-if="item && canAct && !item.item.voided && !item.item.deleted"
      class="scan-no-actions"
    >
      {{ t('scan.noActions') }}
    </p>

    <Transition name="kcgl-sheet">
      <div
        v-if="activeAction"
        class="kcgl-sheet-overlay scan-overlay"
      >
        <div
          class="kcgl-card kcgl-sheet scan-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="scan-dialog-title"
        >
          <h2
            id="scan-dialog-title"
            class="scan-dialog-title"
          >
            {{ t(dialogTitleKey) }}
          </h2>
          <p class="scan-dialog-target">
            {{ item?.item.itemCode }}
          </p>

          <div
            v-if="activeAction === 'sell'"
            class="kcgl-field"
          >
            <label
              class="kcgl-label"
              for="scan-sold-price"
            >
              {{ t('scan.sell.price') }}
            </label>
            <input
              id="scan-sold-price"
              v-model="soldPriceInput"
              class="kcgl-input"
              type="text"
              inputmode="numeric"
              :placeholder="t('scan.sell.pricePlaceholder')"
              :disabled="actionBusy"
            >
            <p class="scan-dialog-hint">
              {{ t('scan.sell.priceHint') }}
            </p>
          </div>

          <div
            v-if="activeAction === 'scrap'"
            class="kcgl-field"
          >
            <label
              class="kcgl-label"
              for="scan-scrap-reason"
            >
              {{ t('scan.scrap.reason') }}
            </label>
            <input
              id="scan-scrap-reason"
              v-model="scrapReason"
              class="kcgl-input"
              type="text"
              maxlength="255"
              :placeholder="t('scan.scrap.reasonPlaceholder')"
              :disabled="actionBusy"
            >
          </div>

          <div
            v-if="activeAction === 'transfer'"
            class="kcgl-field"
          >
            <span class="kcgl-label">{{ t('scan.transfer.to') }}</span>
            <div class="scan-wh-options">
              <label
                v-for="warehouse in [1, 2]"
                :key="warehouse"
                class="scan-wh-option"
                :class="{ 'is-active': transferTo === warehouse }"
              >
                <input
                  v-model="transferTo"
                  type="radio"
                  name="scan-transfer-to"
                  :value="warehouse"
                  :disabled="actionBusy"
                >
                {{ t(`common.warehouse.${warehouse}`) }}
              </label>
            </div>
          </div>

          <div
            v-if="activeAction === 'returnCustomer' || activeAction === 'returnVenue'"
            class="kcgl-field"
          >
            <label
              class="kcgl-label"
              for="scan-return-note"
            >
              {{ t('scan.return.note') }}
            </label>
            <input
              id="scan-return-note"
              v-model="returnNote"
              class="kcgl-input"
              type="text"
              maxlength="255"
              :disabled="actionBusy"
            >
            <p class="scan-dialog-hint">
              {{
                activeAction === 'returnCustomer'
                  ? t('scan.return.customerHint')
                  : t('scan.return.venueHint')
              }}
            </p>
          </div>

          <p
            v-if="activeAction === 'markListed'"
            class="scan-dialog-hint"
          >
            {{ t('scan.listed.note') }}
          </p>

          <p
            v-if="activeAction === 'markCanceled'"
            class="scan-dialog-hint"
          >
            {{ t('scan.canceled.note') }}
          </p>

          <p
            v-if="actionError"
            class="scan-dialog-error"
            role="alert"
          >
            {{ actionError }}
          </p>

          <div class="scan-dialog-actions">
            <button
              type="button"
              class="kcgl-btn scan-dialog-cancel"
              :disabled="actionBusy"
              @click="closeAction"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="kcgl-btn kcgl-btn-primary scan-dialog-ok"
              :disabled="actionBusy"
              @click="onActionConfirm"
            >
              {{ actionBusy ? t('common.saving') : t(dialogConfirmKey) }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
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

.scan-actions {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.scan-action {
  min-height: 48px;
  padding: 6px 10px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-card);
  box-shadow: var(--kcgl-shadow-card);
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 悬停只在真有指针的设备上生效：触屏点一下后 :hover 会粘在最后点的按钮上。 */
@media (hover: hover) {
  .scan-action:hover {
    border-color: var(--kcgl-color-primary);
    color: var(--kcgl-color-primary);
  }
}

/* 触屏按压态：无 hover 时这是唯一反馈，1px 下沉对齐 .kcgl-btn-primary:active。 */
.scan-action:active {
  transform: translateY(1px);
}

.scan-no-actions {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  color: var(--kcgl-color-text-faint);
  font-size: 0.9rem;
  text-align: center;
}

/* 弹层的几何与升起动效由共用基元给（brand.css ⑨ .kcgl-sheet-overlay /
   .kcgl-sheet）。.scan-overlay / .scan-dialog 这两个类名留在标签上只是给测试定位用
   （e2e 与单测按它取弹层），本身不再压样式。 */

.scan-dialog-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.scan-dialog-target {
  margin: 0;
  padding: 6px 10px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.scan-dialog-hint {
  margin: 0;
  font-size: 1rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-faint);
}

.scan-dialog-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.9rem;
}

.scan-wh-options {
  display: flex;
  gap: 8px;
}

.scan-wh-option {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  padding: 4px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
  cursor: pointer;
  transition: transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 触屏按压态（无 hover）：1px 下沉。 */
.scan-wh-option:active {
  transform: translateY(1px);
}

.scan-wh-option.is-active {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.scan-wh-option input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.scan-dialog-actions {
  display: flex;
  gap: 8px;
}

.scan-dialog-cancel {
  flex: 1;
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-weight: 500;
}

/* 非主色 .kcgl-btn 没有基元级按压态（brand.css 只给了 .kcgl-btn-primary），
   这里补 1px 下沉，保证弹层里取消键与确认键手感一致。 */
.scan-dialog-cancel:active:not(:disabled) {
  transform: translateY(1px);
}

.scan-dialog-ok {
  flex: 2;
}
</style>
