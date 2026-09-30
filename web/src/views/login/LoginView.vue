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
      <!-- 品牌标识 + 标题一行：登录页是全站唯一没有导航骨架的页面，缺一个视觉锚点时
           整页读起来像"没加载完"（实测反馈）。图形用内联描边 SVG（docs/07 §1：不用
           emoji 充图标），与空态基元同一套"实物"语汇 -->
      <div class="login-brand">
        <span
          class="login-mark"
          aria-hidden="true"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M3.5 8.5h17v10.4a1.6 1.6 0 0 1-1.6 1.6H5.1a1.6 1.6 0 0 1-1.6-1.6z" />
            <path d="M2.5 4.5h19v4h-19z" />
            <path d="M9.7 12.6h4.6" />
          </svg>
        </span>
        <h1 class="login-title">
          {{ t('common.appTitle') }}
        </h1>
      </div>
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
/* 占满壳内剩余高度（壳的 main 是列向 flex）——v1 用 calc(100vh - 52px) 硬编码顶栏
   高度，PWA 安装引导条一出现（移动端首访必现）就算错，卡片被顶到视口下方只露一半 */
.login-page {
  flex: 1;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--kcgl-space-4) 0;
}

.login-card {
  width: min(400px, 100%);
  display: grid;
  gap: var(--kcgl-space-4);
  padding: var(--kcgl-space-6) var(--kcgl-space-5);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: var(--kcgl-space-3);
}

.login-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-primary-soft);
  color: var(--kcgl-color-primary);
}

.login-mark svg {
  width: 22px;
  height: 22px;
}

.login-title {
  margin: 0;
  font-size: 1.35rem;
  font-weight: 600;
  line-height: 1.3;
}

.login-subtitle {
  margin: calc(var(--kcgl-space-2) * -1) 0 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.login-card .kcgl-btn {
  margin-top: var(--kcgl-space-1);
}
</style>
