<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { cancelStocktake, closeStocktake } from '@/utils/api'
import type { StocktakeSummary } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'

/**
 * 会话收尾确认弹层（締め / 撤单，M3-⑥）：从 StocktakeScanView 抽出来的（D-149）。
 * 两个动作都不可逆，故一律先确认再打端点；失败就地报错、弹层不关。
 *
 * 本件自持 busy / error 与 Esc 键监听：只有它知道「确认在途，此刻不该被撤掉」
 * （这段守卫原先在页面里；busy 搬进本件后页面看不见它，也就守不住）。动作成功后
 * 只把结果报回页面——换 summary 与跳哪一页是页面的事，本件不认路由。
 */

/** 当前要确认的动作；null = 弹层未升起（由父组件经 v-model:kind 持有）。 */
const props = defineProps<{ kind: 'close' | 'cancel' | null; stocktakeId: number }>()

const emit = defineEmits<{
  'update:kind': [kind: null]
  /** 締め成功：带上服务端返回的会话（status=1）。 */
  closed: [summary: StocktakeSummary]
  /** 撤单成功。 */
  cancelled: []
}>()

const { t } = useI18n()

const busy = ref(false)
const error = ref('')

/** 撤掉弹层（取消键 / Esc）：确认在途不许撤——请求已在路上，此刻关掉连报错都看不见。 */
function requestDismiss(): void {
  if (busy.value) {
    return
  }
  emit('update:kind', null)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    requestDismiss()
  }
}

// Esc 只在弹层升起时挂，不留常驻的全局键监听（挂载时 kind 必为 null，immediate 只是清场）
watch(
  () => props.kind,
  (kind) => {
    if (kind != null) {
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

async function onConfirm(): Promise<void> {
  const kind = props.kind
  if (kind == null || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    if (kind === 'close') {
      emit('closed', await closeStocktake(props.stocktakeId))
    } else {
      await cancelStocktake(props.stocktakeId)
      emit('cancelled')
    }
  } catch (failure) {
    error.value = toDisplayMessage(failure, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Transition name="kcgl-sheet">
    <div
      v-if="kind != null"
      class="kcgl-sheet-overlay session-overlay"
    >
      <div
        class="kcgl-card kcgl-sheet session-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="stocktake-dialog-title"
      >
        <h2
          id="stocktake-dialog-title"
          class="session-dialog-title"
        >
          {{ kind === 'close' ? t('stocktake.scan.closeTitle') : t('stocktake.scan.cancelTitle') }}
        </h2>
        <p class="session-dialog-note">
          {{ kind === 'close' ? t('stocktake.scan.closeNote') : t('stocktake.scan.cancelNote') }}
        </p>
        <p
          v-if="error"
          class="session-dialog-error"
          role="alert"
        >
          {{ error }}
        </p>
        <div class="session-dialog-actions">
          <button
            type="button"
            class="kcgl-btn session-dialog-cancel"
            :disabled="busy"
            @click="requestDismiss"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="kcgl-btn kcgl-btn-primary"
            :disabled="busy"
            @click="onConfirm"
          >
            {{
              busy
                ? t('stocktake.scan.closing')
                : kind === 'close'
                  ? t('stocktake.scan.closeConfirm')
                  : t('stocktake.scan.cancelConfirm')
            }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
/* 弹层的几何与升起动效由共用基元给（brand.css ⑨ .kcgl-sheet-overlay /
   .kcgl-sheet）。.session-overlay / .session-dialog 这两个类名留在标签上只是给测试定位用
   （e2e 与单测按它取弹层），本身不再压样式。 */

.session-dialog-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.session-dialog-note {
  margin: 0;
  font-size: 1rem;
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
  font-size: 0.9rem;
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

/* 非主色 .kcgl-btn 没有基元级按压态（brand.css 只给了 .kcgl-btn-primary），
   这里补 1px 下沉，保证弹层里取消键与确认键手感一致。 */
.session-dialog-cancel:active:not(:disabled) {
  transform: translateY(1px);
}

.session-dialog-actions .kcgl-btn-primary {
  flex: 2;
}
</style>
