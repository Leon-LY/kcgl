import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { setUnauthorizedHandler } from '@/utils/api'

// M1 起路由按 meta.shell 双壳组织（mobile/desktop，UA 自动选择+手动切换，见 composables/useShell）。
declare module 'vue-router' {
  interface RouteMeta {
    /** 文档标题文案 key（App.vue 与语言联动渲染）；缺省为 common.appTitle（仅系统名） */
    titleKey?: string
    /** 可访问角色（1管理员/2编辑者/3查看者）；缺省=登录即可 */
    roles?: number[]
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/LoginView.vue'),
      meta: { titleKey: 'auth.login' },
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('@/views/account/ChangePasswordView.vue'),
      meta: { titleKey: 'changePwd.title' },
    },
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/entry',
      name: 'entry',
      component: () => import('@/views/mobile/entry/EntryView.vue'),
      meta: { titleKey: 'entry.title', roles: [1, 2] },
    },
    {
      // 到货核对（M2-8a）：在途清单全员可看；确认入库按钮仅编辑者以上（服务端 403 兜底）
      path: '/arrival',
      name: 'arrival',
      component: () => import('@/views/mobile/arrival/ArrivalView.vue'),
      meta: { titleKey: 'arrival.title' },
    },
    {
      // 本日录入会话（M2-8b）：个人当天清单（含作废），现场誊写与收工对数，全员可看
      path: '/today',
      name: 'today',
      component: () => import('@/views/mobile/today/TodayView.vue'),
      meta: { titleKey: 'today.title' },
    },
    {
      // 扫码操作（M3-④）：QR 定位+按状态渲染动作菜单，全员可看（操作仅编辑者以上，
      // 服务端 403 兜底）
      path: '/scan',
      name: 'scan',
      component: () => import('@/views/mobile/scan/ScanView.vue'),
      meta: { titleKey: 'scan.title' },
    },
    {
      // 盘点（M3-⑥）：列表/会话/差异确认全员可看（发起与裁决仅编辑者以上，
      // 服务端 403 兜底）
      path: '/stocktake',
      name: 'stocktake',
      component: () => import('@/views/mobile/stocktake/StocktakeListView.vue'),
      meta: { titleKey: 'stocktake.title' },
    },
    {
      // 出荷待ち（M4）：雅虎成交未出库的拣货队列，全员可看；卖出动作在扫码页
      // 执行（?code= 深链定位），服务端 403 兜底
      path: '/pending-shipments',
      name: 'pending-shipments',
      component: () => import('@/views/mobile/shipment/PendingShipmentsView.vue'),
      meta: { titleKey: 'yahoo.shipments.title' },
    },
    {
      path: '/stocktake/:id',
      name: 'stocktake-session',
      component: () => import('@/views/mobile/stocktake/StocktakeScanView.vue'),
      meta: { titleKey: 'stocktake.scan.title' },
    },
    {
      path: '/stocktake/:id/diffs',
      name: 'stocktake-diffs',
      component: () => import('@/views/mobile/stocktake/StocktakeDiffView.vue'),
      meta: { titleKey: 'stocktake.diff.title' },
    },
    {
      // 字典管理（M2-8b-2）：会场 E+（创建/改名现场自救，停用=管理员按钮内再收敛）
      path: '/admin/venues',
      name: 'admin-venues',
      component: () => import('@/views/desktop/admin/venue/VenueAdminView.vue'),
      meta: { titleKey: 'admin.venue.title', roles: [1, 2] },
    },
    {
      // 账号管理（M2-8b-3）：仅管理员（/api/users/** URL 级 RBAC 兜底）
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/desktop/admin/user/UserAdminView.vue'),
      meta: { titleKey: 'admin.user.title', roles: [1] },
    },
    {
      path: '/admin/price-bands',
      name: 'admin-price-bands',
      component: () => import('@/views/desktop/admin/priceband/PriceBandAdminView.vue'),
      meta: { titleKey: 'admin.band.title', roles: [1] },
    },
    {
      path: '/admin/year-codes',
      name: 'admin-year-codes',
      component: () => import('@/views/desktop/admin/yearcode/YearCodeAdminView.vue'),
      meta: { titleKey: 'admin.yearCode.title', roles: [1] },
    },
    {
      // 标签打印（M2-7）：桌面为主、全员可打印（录入手与贴标手常不同人）
      path: '/print',
      name: 'print',
      component: () => import('@/views/desktop/print/PrintView.vue'),
      meta: { titleKey: 'print.title' },
    },
    {
      // 雅虎联动（M4）：CSV 导入/出荷待ち/照合三视图，全员可读；上传仅编辑者
      // 以上（服务端 @PreAuthorize 兜底）
      path: '/yahoo',
      name: 'yahoo',
      component: () => import('@/views/desktop/yahoo/YahooView.vue'),
      meta: { titleKey: 'yahoo.title' },
    },
    {
      // エクセル連携（M4-⑤，D-058）：模板下载/双模式导入/帳票导出，全员可读；
      // 模板与上传仅编辑者以上（服务端 @PreAuthorize 兜底）
      path: '/excel',
      name: 'excel',
      component: () => import('@/views/desktop/excel/ExcelView.vue'),
      meta: { titleKey: 'excel.title' },
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
  // 角色受限页（meta.roles）：越权访问回首页（viewer 点「商品录入」入口被入口隐藏，
  // 直敲 URL 由此拦截；403 语义由服务端端点二次兜底）
  if (to.meta.roles && auth.me && !to.meta.roles.includes(auth.me.role)) {
    return { name: 'home' }
  }
  return true
})

// 会话中途失效（401，如管理员停用账号后 AccountStatusFilter 生效）→ 清态回登录页。
setUnauthorizedHandler(() => {
  useAuthStore().clearSession()
  router.push({ name: 'login' })
})

export default router
