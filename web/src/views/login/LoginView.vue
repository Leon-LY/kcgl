<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { toDisplayMessage } from '@/utils/errors'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit(): Promise<void> {
  if (submitting.value || !username.value.trim() || !password.value) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    if (auth.me?.mustChangePwd) {
      await router.push({ name: 'change-password' })
      return
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.push(redirect)
  } catch (error) {
    errorMessage.value = toDisplayMessage(error, t)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <form
      class="kcgl-card login-card"
      novalidate
      @submit.prevent="submit"
    >
      <h1 class="login-title">
        {{ t('common.appTitle') }}
      </h1>
      <p class="login-subtitle">
        {{ t('auth.loginTitle') }}
      </p>

      <div
        v-if="errorMessage"
        class="kcgl-error-box"
        role="alert"
      >
        {{ errorMessage }}
      </div>

      <div class="kcgl-field">
        <label
          class="kcgl-label"
          for="login-username"
        >{{ t('auth.username') }}</label>
        <input
          id="login-username"
          v-model="username"
          class="kcgl-input"
          type="text"
          name="username"
          autocomplete="username"
          autocapitalize="none"
          spellcheck="false"
          required
        >
      </div>

      <div class="kcgl-field">
        <label
          class="kcgl-label"
          for="login-password"
        >{{ t('auth.password') }}</label>
        <input
          id="login-password"
          v-model="password"
          class="kcgl-input"
          type="password"
          name="password"
          autocomplete="current-password"
          required
        >
      </div>

      <button
        class="kcgl-btn kcgl-btn-primary kcgl-btn-block"
        type="submit"
        :disabled="submitting"
      >
        {{ submitting ? t('common.loading') : t('auth.login') }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  min-height: calc(100vh - 52px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.login-card {
  width: min(380px, 100%);
  display: grid;
  gap: 16px;
}

.login-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.login-subtitle {
  margin: -8px 0 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}
</style>
