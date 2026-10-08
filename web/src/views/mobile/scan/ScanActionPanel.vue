<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useItemActions } from '@/composables/useItemActions'
import { availableActions } from '@/utils/inventoryActions'
import type { ScanAction } from '@/utils/inventoryActions'
import type { ItemByCode } from '@/utils/api'

/**
 * 扫码页的动作菜单与动作弹层（M3-④）：从 ScanView 抽出来的（D-151）。
 *
 * 本件自持 useItemActions 的全部状态与 Esc 键监听，对外只有两件事：告诉页面弹层开没开
 * （页面据此暂停取流——对话框操作中不换目标件），以及动作成功后把动作名与件号报回页面
 * （亮横幅、重读定位卡都归页面，本件不认它们）。模板与样式逐字来自原页面，类名与 id
 * 一个未改——它们是单测与 e2e 的定位锚点。
 */

/** 定位到的商品卡（可空：未定位时菜单与弹层都该是空的）。 */
const props = defineProps<{ item: ItemByCode | null; canAct: boolean }>()

const emit = defineEmits<{
  /** 弹层开合：页面据此暂停/恢复取流。 */
  'update:open': [open: boolean]
  /** 动作成功：动作名 + 服务端返回的件号（页面据此出横幅并重读卡片）。 */
  done: [action: ScanAction, itemCode: string]
}>()

const { t } = useI18n()

const actions = computed(() => {
  const current = props.item
  if (current == null || current.item.voided || current.item.deleted || !props.canAct) {
    return []
  }
  return availableActions(current.item.stockStatus, current.item.saleStatus)
})

function actionLabel(action: ScanAction): string {
  return t(`scan.action.${action}`)
}

/**
 * 七动作的载荷/校验/幂等全部由 useItemActions 承担（与商品一覧行内动作同一份，
 * D-129）；本件只管「谁可作为目标」（页面扫码定位到的卡）与把成功报回页面。
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
  onDone: (action, result) => {
    emit('done', action, result.itemCode)
  },
})

/** 弹层开合回报页面：原页面的 cameraPaused 就取这个信号（开着时暂停取流）。 */
watch(activeAction, (current) => {
  emit('update:open', current != null)
})

function openAction(action: ScanAction): void {
  const current = props.item
  if (current == null) {
    return
  }
  openDialog(action, current.item)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && activeAction.value != null) {
    closeAction()
  }
}

// 常驻键监听（与拆分前一致）：不进弹层不做事，不必随开合挂卸
onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
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
</template>

<style scoped>
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
