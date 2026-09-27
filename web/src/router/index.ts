import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { setUnauthorizedHandler } from '@/utils/api'

// M1 起路由按 meta.shell 双壳组织（mobile/desktop，UA 自动选择+手动切换，见 composables/useShell）。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/LoginView.vue'),
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('@/views/account/ChangePasswordView.vue'),
    },
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

// 首次导航前恢复会话；此后按会话态与 mustChangePwd 引导。
// 未登录访问受保护页 → 登录页（带 redirect 回跳）；首登改密用户一律拦在改密页。
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    await auth.initialize()
  }
  const isLogin = to.name === 'login'
  if (isLogin && auth.me) {
    return auth.me.mustChangePwd ? { name: 'change-password' } : { name: 'home' }
  }
  if (!isLogin && !auth.me) {
    return {
      name: 'login',
      query: to.fullPath === '/' ? {} : { redirect: to.fullPath },
    }
  }
  if (auth.me?.mustChangePwd && to.name !== 'change-password') {
    return { name: 'change-password' }
  }
  return true
})

// 会话中途失效（401，如管理员停用账号后 AccountStatusFilter 生效）→ 清态回登录页。
setUnauthorizedHandler(() => {
  useAuthStore().clearSession()
  router.push({ name: 'login' })
})

export default router
