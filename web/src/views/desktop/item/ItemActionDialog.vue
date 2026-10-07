<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useItemActions } from '@/composables/useItemActions'
import type { ScanAction } from '@/utils/inventoryActions'
import type { ActionResult, ItemSearchRow } from '@/utils/api'

/**
 * 商品一覧的行内状态动作弹层（D-129）。
 *
 * 载荷、校验与幂等全在 useItemActions（与扫码页同一份）；本组件只负责桌面端的外壳：
 * 移动端是手写全屏覆层，桌面端用 el-dialog，与 ItemAdjustDialog / ItemBatchDeleteDialog
 * 同形。文案键沿用 `scan.*`——同一动作在两壳必须说同一句话，各存一份就是给「同一次
 * 报废在两端叫法不同」留口子。
 *
 * 打开/关闭由父组件用 item+action 两个 prop 驱动（与扫码页的 openAction 同语义）：
 * 两个都非空即打开，父组件在 close/done 后清空，再次点击同一行同一动作时 prop 才
 * 会真的变化、watch 才会重新触发。
 */

const props = defineProps<{
  item: ItemSearchRow | null
  action: ScanAction | null
}>()

const emit = defineEmits<{
  close: []
  done: [result: ActionResult, action: ScanAction]
}>()

const { t } = useI18n()

const {
  activeAction,
  busy,
  error,
  soldPriceInput,
  scrapReason,
  transferTo,
  returnNote,
  titleKey,
  confirmKey,
  open,
  close,
  confirm,
} = useItemActions({
  onDone: (action, result) => emit('done', result, action),
})

watch(
  () => [props.item, props.action] as const,
  ([item, action]) => {
    // 两个都齐才开：父组件清 props 时这里收到 null，close() 幂等
    if (item != null && action != null) {
      open(action, item)
      return
    }
    close()
  },
  { immediate: true },
)

/**
 * 关闭态**不求值文案键**：块名由动作派生，没有动作时是空串，直拼会得到
 * `scan..title` 并触发 vue-i18n 的缺键告警——一个没人打开过的弹层不该刷缺键日志
 * （真缺键时会被这行噪声淹没）。el-dialog 是懒渲染的，但 title 这个 prop 在声明处
 * 就会求值，所以必须在这里挡住。
 */
const dialogTitle = computed(() => (activeAction.value == null ? '' : t(titleKey.value)))
const confirmText = computed(() => {
  if (busy.value) {
    return t('common.saving')
  }
  return activeAction.value == null ? '' : t(confirmKey.value)
})

function onCancel(): void {
  close()
  // 父组件的 action prop 必须跟着清：否则再次点同一行的同一动作时 prop 不变，
  // watch 不触发，弹层就打不开了
  emit('close')
}
</script>

<template>
  <el-dialog
    :model-value="activeAction != null"
    :title="dialogTitle"
    width="460px"
    :close-on-click-modal="!busy"
    @close="onCancel"
  >
    <div class="itemact-form">
      <p class="itemact-target">
        {{ props.item?.itemCode }}
      </p>

      <div
        v-if="activeAction === 'sell'"
        class="kcgl-field"
      >
        <span class="kcgl-label">{{ t('scan.sell.price') }}</span>
        <el-input
          v-model="soldPriceInput"
          inputmode="numeric"
          :placeholder="t('scan.sell.pricePlaceholder')"
          :disabled="busy"
        />
        <p class="itemact-hint">
          {{ t('scan.sell.priceHint') }}
        </p>
      </div>

      <div
        v-if="activeAction === 'scrap'"
        class="kcgl-field"
      >
        <span class="kcgl-label">{{ t('scan.scrap.reason') }}</span>
        <el-input
          v-model="scrapReason"
          type="textarea"
          :rows="2"
          maxlength="255"
          :placeholder="t('scan.scrap.reasonPlaceholder')"
          :disabled="busy"
        />
      </div>

      <div
        v-if="activeAction === 'transfer'"
        class="kcgl-field"
      >
        <span class="kcgl-label">{{ t('scan.transfer.to') }}</span>
        <el-radio-group
          v-model="transferTo"
          :disabled="busy"
        >
          <el-radio
            v-for="warehouse in [1, 2]"
            :key="warehouse"
            :value="warehouse"
          >
            {{ t(`common.warehouse.${warehouse}`) }}
          </el-radio>
        </el-radio-group>
      </div>

      <div
        v-if="activeAction === 'returnCustomer' || activeAction === 'returnVenue'"
        class="kcgl-field"
      >
        <span class="kcgl-label">{{ t('scan.return.note') }}</span>
        <el-input
          v-model="returnNote"
          type="textarea"
          :rows="2"
          maxlength="255"
          :disabled="busy"
        />
        <p class="itemact-hint">
          {{
            activeAction === 'returnCustomer'
              ? t('scan.return.customerHint')
              : t('scan.return.venueHint')
          }}
        </p>
      </div>

      <p
        v-if="activeAction === 'markListed'"
        class="itemact-hint"
      >
        {{ t('scan.listed.note') }}
      </p>

      <p
        v-if="activeAction === 'markCanceled'"
        class="itemact-hint"
      >
        {{ t('scan.canceled.note') }}
      </p>

      <p
        v-if="error"
        class="kcgl-error-box"
        role="alert"
      >
        {{ error }}
      </p>
    </div>
    <template #footer>
      <el-button
        :disabled="busy"
        @click="onCancel"
      >
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        :loading="busy"
        @click="confirm"
      >
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 仅本弹层自有类；字段复用全局 .kcgl-field/.kcgl-label/.kcgl-error-box
   （styles/brand.css，跨组件共享——scoped 样式不能跨组件边界）。 */
.itemact-form {
  display: grid;
  gap: var(--kcgl-space-3);
}

.itemact-target {
  margin: 0;
  padding: 6px 10px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.itemact-hint {
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-faint);
}
</style>
