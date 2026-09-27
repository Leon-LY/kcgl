<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { api } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { useAuthStore } from '@/stores/auth'

const MIN_PASSWORD_LENGTH = 10

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit(): Promise<void> {
  errorMessage.value = ''
  if (submitting.value || !currentPassword.value || !newPassword.value || !confirmPassword.value) {
    return
  }
  if (newPassword.value.length < MIN_PASSWORD_LENGTH) {
    errorMessage.value = t('changePwd.policyHint')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    errorMessage.value = t('changePwd.mismatch')
    return
  }
  submitting.value = true
  try {
    await api.changePassword(currentPassword.value, newPassword.value)
    await auth.initialize() // 重新拉取 me，mustChangePwd 归零
    await router.push({ name: 'home' })
  } catch (error) {
    errorMessage.value = toDisplayMessage(error, t)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="change-pwd-page">
    <form
      class="kcgl-card change-pwd-card"
      novalidate
      @submit.prevent="submit"
    >
      <h1 class="change-pwd-title">
        {{ t('changePwd.title') }}
      </h1>

      <div
        v-if="auth.me?.mustChangePwd"
        class="kcgl-info-box"
      >
        {{ t('auth.mustChangePwd') }}
      </div>
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
          for="pwd-current"
        >{{ t('changePwd.current') }}</label>
        <input
          id="pwd-current"
          v-model="currentPassword"
          class="kcgl-input"
          type="password"
          autocomplete="current-password"
          required
        >
      </div>

      <div class="kcgl-field">
        <label
          class="kcgl-label"
          for="pwd-new"
        >{{ t('changePwd.new') }}</label>
        <input
          id="pwd-new"
          v-model="newPassword"
          class="kcgl-input"
          type="password"
          autocomplete="new-password"
          required
        >
      </div>

      <div class="kcgl-field">
        <label
          class="kcgl-label"
          for="pwd-confirm"
        >{{ t('changePwd.confirm') }}</label>
        <input
          id="pwd-confirm"
          v-model="confirmPassword"
          class="kcgl-input"
          type="password"
          autocomplete="new-password"
          required
        >
      </div>

      <button
        class="kcgl-btn kcgl-btn-primary kcgl-btn-block"
        type="submit"
        :disabled="submitting"
      >
        {{ submitting ? t('common.loading') : t('changePwd.submit') }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.change-pwd-page {
  display: flex;
  justify-content: center;
  padding: 16px;
}

.change-pwd-card {
  width: min(420px, 100%);
  display: grid;
  gap: 16px;
}

.change-pwd-title {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 600;
}
</style>
