<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { fetchChecklist, markChecklistPrintDone } from '@/utils/api'
import type { Checklist } from '@/utils/api'

/**
 * 首启引导卡片（M2，M5-③ 抽为共享组件）：空字典首件录入必 400（六轮旅程）——
 * 五步引导对管理员置顶，全部完成后整体隐藏。获取失败静默不显示
 * （引导是增强，不阻塞所在页面）。桌面大盘与移动首页共用（i18n 沿用 home.setup.*）。
 */

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const checklist = ref<Checklist | null>(null)

const isAdmin = computed(() => auth.me != null && auth.me.role === 1)

const setupSteps = computed(() => {
  const c = checklist.value
  if (c === null) {
    return []
  }
  return [
    { key: 'stepUsers', done: c.hasStaffUser, to: '/admin/users' },
    { key: 'stepVenue', done: c.hasVenue, to: '/admin/venues' },
    { key: 'stepBand', done: c.hasPriceBand, to: '/admin/price-bands' },
    { key: 'stepItem', done: c.hasItem, to: '/entry' },
    { key: 'stepPrint', done: c.printDone, to: '/print' },
  ]
})

const setupDone = computed(
  () => checklist.value !== null && setupSteps.value.every((step) => step.done),
)

/** 渲染开关：仅管理员、未全部完成、且获取成功（null=静默隐藏）。 */
const showCard = computed(() => isAdmin.value && checklist.value !== null && !setupDone.value)

const printMarking = ref(false)

async function onMarkPrintDone(): Promise<void> {
  if (printMarking.value) return
  printMarking.value = true
  try {
    checklist.value = await markChecklistPrintDone()
  } catch {
    // 幂等标记失败不打断页面——下次进来重试即可
  } finally {
    printMarking.value = false
  }
}

onMounted(async () => {
  if (!isAdmin.value) return
  try {
    checklist.value = await fetchChecklist()
  } catch {
    checklist.value = null
  }
})
</script>

<template>
  <div
    v-if="showCard"
    class="kcgl-card setup-card"
  >
    <h2 class="setup-title">
      {{ t('home.setup.title') }}
    </h2>
    <p class="setup-note">
      {{ t('home.setup.note') }}
    </p>
    <ol class="setup-steps">
      <li
        v-for="step in setupSteps"
        :key="step.key"
        class="setup-step"
        :class="{ 'is-done': step.done }"
      >
        <span
          class="setup-mark"
          :aria-hidden="true"
        >{{ step.done ? '✓' : '' }}</span>
        <button
          type="button"
          class="setup-link"
          @click="router.push(step.to)"
        >
          {{ t(`home.setup.${step.key}`) }}
        </button>
        <button
          v-if="step.key === 'stepPrint' && !step.done"
          type="button"
          class="setup-done-btn"
          :disabled="printMarking"
          @click="onMarkPrintDone"
        >
          {{ t('home.setup.printDone') }}
        </button>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.setup-card {
  display: grid;
  gap: 12px;
  padding: 20px;
  border-left: 3px solid var(--kcgl-color-primary);
}

.setup-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.setup-note {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.setup-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}

.setup-step {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
}

.setup-mark {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border: 1.5px solid var(--kcgl-color-border);
  border-radius: 50%;
  color: var(--kcgl-color-primary);
  font-size: 0.85rem;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.setup-step.is-done .setup-mark {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  font-weight: 700;
}

.setup-step.is-done .setup-link {
  color: var(--kcgl-color-text-sub);
}

.setup-link {
  border: none;
  background: none;
  padding: 0;
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.92rem;
  text-align: left;
  cursor: pointer;
}

.setup-link:hover {
  color: var(--kcgl-color-primary);
  text-decoration: underline;
}

.setup-done-btn {
  margin-left: auto;
  flex-shrink: 0;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text-sub);
  font-size: 0.8rem;
  cursor: pointer;
}

.setup-done-btn:hover {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}
</style>
