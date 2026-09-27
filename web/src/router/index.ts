import { createRouter, createWebHistory } from 'vue-router'

// M1 起路由按 meta.shell 双壳组织（mobile/desktop，UA 自动选择+手动切换）；
// M0 仅骨架路由保证应用可运行。
const router = createRouter({
  history: createWebHistory(),
  routes: [
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

export default router
