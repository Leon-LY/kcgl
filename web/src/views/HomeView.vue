<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import SetupChecklistCard from '@/components/SetupChecklistCard.vue'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const { shell, switchShell } = useShell()

const canEntry = computed(() => auth.me != null && auth.me.role <= 2)

const shellOptions = [
  { value: 'mobile', labelKey: 'common.shellMobile' },
  { value: 'desktop', labelKey: 'common.shellDesktop' },
] as const

/**
 * 切到桌面壳后 '/' 会重定向到大盘（M5-③ D-072：桌面落地页=dashboard），
 * 这里主动带上目标页，避免「切了壳还停在移动首页」的一跳。
 */
function onSwitchShell(target: 'mobile' | 'desktop'): void {
  switchShell(target)
  if (target === 'desktop') {
    void router.push({ name: 'dashboard' })
  }
}
</script>

<template>
  <section
    v-if="auth.me"
    class="home"
  >
    <h1 class="home-welcome">
      {{ t('home.welcome', { name: auth.me.displayName }) }}
    </h1>

    <SetupChecklistCard />

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
          @click="onSwitchShell(option.value)"
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
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'stocktake' })"
      >
        {{ t('home.goStocktake') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'pending-shipments' })"
      >
        {{ t('home.goPendingShipments') }}
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
  font-size: 1rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.home-info {
  margin: 0;
  display: grid;
  gap: 8px;
}

/* 手机档：账号字段改为「标签在上、值在下」的单列（原生移动版式）；
   600px 起才恢复桌面两列定义表——移动端三档同源，见 docs/07 §1。 */
.home-info-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--kcgl-space-1);
  align-items: baseline;
}

@media (min-width: 600px) {
  .home-info-row {
    grid-template-columns: 9em 1fr;
    gap: var(--kcgl-space-2);
  }
}

.home-info-row dt {
  font-size: 0.9rem;
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
  height: 44px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.home-shell-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 触屏无 hover，按压态是唯一反馈 */
.home-shell-option:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

.home-entry-link {
  display: flex;
  align-items: center;
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
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 行尾尖括号 = 「点了会去另一个页面」的唯一提示。这排行只有文字和描边，
   拿掉它整行看着就是个普通按钮，用户不下手点。
   （原写法靠父容器 justify-content: space-between 把伪元素顶到右边——flex 里
   裸文本会变成一个匿名盒，碰巧生效；改成 margin-left: auto 是同一效果但读得出意图。） */
.home-entry-link::after {
  content: '›';
  margin-left: auto;
  color: var(--kcgl-color-text-sub);
  font-size: 1.3rem;
  line-height: 1;
}

/* 悬停高亮只在真有指针的设备生效（触屏 tap 会把 :hover 卡在最后点过的元素上） */
@media (hover: hover) {
  .home-entry-link:hover {
    border-color: var(--kcgl-color-primary);
    color: var(--kcgl-color-primary);
  }
}

/* 触屏无 hover，按压态是唯一反馈：边框变主色 + 1px 下沉 */
.home-entry-link:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  transform: translateY(1px);
}

.home-preparing {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}
</style>
