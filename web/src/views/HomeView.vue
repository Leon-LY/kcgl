<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import { fetchChecklist, markChecklistPrintDone } from '@/utils/api'
import type { Checklist } from '@/utils/api'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const { shell, switchShell } = useShell()

const canEntry = computed(() => auth.me != null && auth.me.role <= 2)

// ------------------------------------------------------------- 首启 checklist（管理员）

/**
 * 空字典首件录入必 400（六轮旅程 M2）——五步引导卡片对管理员置顶，
 * 全部完成后隐藏。获取失败静默不显示（引导是增强，不阻塞首页）。
 */
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

const showSetupCard = computed(() => isAdmin.value && checklist.value !== null && !setupDone.value)

const printMarking = ref(false)

async function onMarkPrintDone(): Promise<void> {
  if (printMarking.value) return
  printMarking.value = true
  try {
    checklist.value = await markChecklistPrintDone()
  } catch {
    // 幂等标记失败不打断首页——下次进来重试即可
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

const shellOptions = [
  { value: 'mobile', labelKey: 'common.shellMobile' },
  { value: 'desktop', labelKey: 'common.shellDesktop' },
] as const
</script>

<template>
  <section
    v-if="auth.me"
    class="home"
  >
    <h1 class="home-welcome">
      {{ t('home.welcome', { name: auth.me.displayName }) }}
    </h1>

    <div
      v-if="showSetupCard"
      class="kcgl-card home-card home-setup"
    >
      <h2 class="home-card-title">
        {{ t('home.setup.title') }}
      </h2>
      <p class="home-setup-note">
        {{ t('home.setup.note') }}
      </p>
      <ol class="home-setup-steps">
        <li
          v-for="step in setupSteps"
          :key="step.key"
          class="home-setup-step"
          :class="{ 'is-done': step.done }"
        >
          <span
            class="home-setup-mark"
            :aria-hidden="true"
          >{{ step.done ? '✓' : '' }}</span>
          <button
            type="button"
            class="home-setup-link"
            @click="router.push(step.to)"
          >
            {{ t(`home.setup.${step.key}`) }}
          </button>
          <button
            v-if="step.key === 'stepPrint' && !step.done"
            type="button"
            class="home-setup-done"
            :disabled="printMarking"
            @click="onMarkPrintDone"
          >
            {{ t('home.setup.printDone') }}
          </button>
        </li>
      </ol>
    </div>

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('home.accountInfo') }}
      </h2>
      <dl class="home-info">
        <div class="home-info-row">
          <dt>{{ t('home.username') }}</dt>
          <dd>{{ auth.me.username }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.displayName') }}</dt>
          <dd>{{ auth.me.displayName }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.role') }}</dt>
          <dd>{{ t(`auth.role.${auth.me.role}`) }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.locale') }}</dt>
          <dd>{{ auth.me.locale }}</dd>
        </div>
      </dl>
    </div>

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('common.shellSwitch') }}
      </h2>
      <div class="home-shell-options">
        <button
          v-for="option in shellOptions"
          :key="option.value"
          type="button"
          class="home-shell-option"
          :class="{ 'is-active': shell === option.value }"
          :aria-pressed="shell === option.value"
          @click="switchShell(option.value)"
        >
          {{ t(option.labelKey) }}
        </button>
      </div>
    </div>

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('home.quickActions') }}
      </h2>
      <button
        v-if="canEntry"
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'entry' })"
      >
        {{ t('home.goEntry') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'arrival' })"
      >
        {{ t('home.goArrival') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'scan' })"
      >
        {{ t('home.goScan') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'today' })"
      >
        {{ t('home.goToday') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'print' })"
      >
        {{ t('home.goPrint') }}
      </button>
    </div>

    <p class="home-preparing">
      {{ t('home.preparing') }}
    </p>
  </section>
</template>

<style scoped>
.home {
  display: grid;
  gap: 16px;
  max-width: 520px;
  margin: 0 auto;
}

.home-welcome {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.home-card {
  display: grid;
  gap: 12px;
  padding: 20px;
}

.home-card-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.home-info {
  margin: 0;
  display: grid;
  gap: 8px;
}

.home-info-row {
  display: grid;
  grid-template-columns: 9em 1fr;
  gap: 8px;
  align-items: baseline;
}

.home-info-row dt {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.home-info-row dd {
  margin: 0;
}

.home-shell-options {
  display: flex;
  gap: 8px;
}

.home-shell-option {
  flex: 1;
  height: 40px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
}

.home-shell-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.home-entry-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.95rem;
  cursor: pointer;
}

.home-entry-link::after {
  content: '›';
  color: var(--kcgl-color-text-sub);
  font-size: 1.3rem;
  line-height: 1;
}

.home-entry-link:hover {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

.home-preparing {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.home-setup {
  border-left: 3px solid var(--kcgl-color-primary);
}

.home-setup-note {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.home-setup-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}

.home-setup-step {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
}

.home-setup-mark {
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

.home-setup-step.is-done .home-setup-mark {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  font-weight: 700;
}

.home-setup-step.is-done .home-setup-link {
  color: var(--kcgl-color-text-sub);
}

.home-setup-link {
  border: none;
  background: none;
  padding: 0;
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.92rem;
  text-align: left;
  cursor: pointer;
}

.home-setup-link:hover {
  color: var(--kcgl-color-primary);
  text-decoration: underline;
}

.home-setup-done {
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

.home-setup-done:hover {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}
</style>
