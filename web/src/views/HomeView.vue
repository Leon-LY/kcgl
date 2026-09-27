<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'

const { t } = useI18n()
const auth = useAuthStore()
const { shell, switchShell } = useShell()

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

.home-preparing {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}
</style>
