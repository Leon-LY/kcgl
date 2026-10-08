import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import { setUnauthorizedHandler } from '@/utils/api'

// M1 起路由按 meta.shell 双壳组织（mobile/desktop，UA 自动选择+手动切换，见 composables/useShell）。
declare module 'vue-router' {
  interface RouteMeta {
    /** 文档标题文案 key（App.vue 与语言联动渲染）；缺省为 common.appTitle（仅系统名） */
    titleKey?: string
    /** 可访问角色（1管理员/2编辑者/3查看者）；缺省=登录即可 */
    roles?: number[]
    /**
     * 本页归属哪个壳。App.vue 只按 useShell 选壳，不会替路由改壳，因此桌面专属页
     * 一旦在移动壳里被访问，就会把为 24 吋屏排的版面塞进手机渲染——「手机上的 PC
     * 缩小版」从此处来。守卫按本字段拦截（见 router.beforeEach）；
     * 缺省=双壳通用（登录页/改密页这类壳外页面不加标注）。
     */
    shell?: 'mobile' | 'desktop'
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
      meta: { shell: 'mobile' },
    },
    {
      // 桌面大盘（M5-③，D-072）：桌面壳的落地页（'/' 重定向至此）——
      // 首启引导+全队/两仓指标+雅虎待办；全员可读（指标聚合无敏感面）
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/views/desktop/dashboard/DashboardView.vue'),
      meta: { shell: 'desktop', titleKey: 'dashboard.title' },
    },
    {
      path: '/entry',
      name: 'entry',
      component: () => import('@/views/mobile/entry/EntryView.vue'),
      meta: { shell: 'mobile', titleKey: 'entry.title', roles: [1, 2] },
    },
    {
      // 到货核对（M2-8a）：在途清单全员可看；确认入库按钮仅编辑者以上（服务端 403 兜底）
      path: '/arrival',
      name: 'arrival',
      component: () => import('@/views/mobile/arrival/ArrivalView.vue'),
      meta: { shell: 'mobile', titleKey: 'arrival.title' },
    },
    {
      // 本日录入会话（M2-8b）：个人当天清单（含作废），现场誊写与收工对数，全员可看
      path: '/today',
      name: 'today',
      component: () => import('@/views/mobile/today/TodayView.vue'),
      meta: { shell: 'mobile', titleKey: 'today.title' },
    },
    {
      // 扫码操作（M3-④）：QR 定位+按状态渲染动作菜单，全员可看（操作仅编辑者以上，
      // 服务端 403 兜底）
      path: '/scan',
      name: 'scan',
      component: () => import('@/views/mobile/scan/ScanView.vue'),
      meta: { shell: 'mobile', titleKey: 'scan.title' },
    },
    {
      // 盘点（M3-⑥）：列表/会话/差异确认全员可看（发起与裁决仅编辑者以上，
      // 服务端 403 兜底）
      path: '/stocktake',
      name: 'stocktake',
      component: () => import('@/views/mobile/stocktake/StocktakeListView.vue'),
      meta: { shell: 'mobile', titleKey: 'stocktake.title' },
    },
    {
      // 出荷待ち（M4）：雅虎成交未出库的拣货队列，全员可看；卖出动作在扫码页
      // 执行（?code= 深链定位），服务端 403 兜底
      path: '/pending-shipments',
      name: 'pending-shipments',
      component: () => import('@/views/mobile/shipment/PendingShipmentsView.vue'),
      meta: { shell: 'mobile', titleKey: 'yahoo.shipments.title' },
    },
    {
      // 商品列表（M5-①，D-061）：全局搜索+筛选+滞销徽标，全员可读；编辑按钮
      // 仅编辑者以上（服务端 @PreAuthorize 兜底）
      path: '/items',
      name: 'items',
      component: () => import('@/views/desktop/item/ItemsView.vue'),
      meta: { shell: 'desktop', titleKey: 'items.title' },
    },
    {
      // 商品详情（M5-①）：全字段+分歧徽标+流水/出品历史；软删件 404 回列表
      path: '/items/:id',
      name: 'item-detail',
      component: () => import('@/views/desktop/item/ItemDetailView.vue'),
      meta: { shell: 'desktop', titleKey: 'items.detail.title' },
    },
    {
      path: '/stocktake/:id',
      name: 'stocktake-session',
      component: () => import('@/views/mobile/stocktake/StocktakeScanView.vue'),
      meta: { shell: 'mobile', titleKey: 'stocktake.scan.title' },
    },
    {
      path: '/stocktake/:id/diffs',
      name: 'stocktake-diffs',
      component: () => import('@/views/mobile/stocktake/StocktakeDiffView.vue'),
      meta: { shell: 'mobile', titleKey: 'stocktake.diff.title' },
    },
    {
      // 字典管理（M2-8b-2）：会场 E+（创建/改名现场自救，停用=管理员按钮内再收敛）
      path: '/admin/venues',
      name: 'admin-venues',
      component: () => import('@/views/desktop/admin/venue/VenueAdminView.vue'),
      meta: { shell: 'desktop', titleKey: 'admin.venue.title', roles: [1, 2] },
    },
    {
      // 账号管理（M2-8b-3）：仅管理员（/api/users/** URL 级 RBAC 兜底）
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/desktop/admin/user/UserAdminView.vue'),
      meta: { shell: 'desktop', titleKey: 'admin.user.title', roles: [1] },
    },
    {
      path: '/admin/price-bands',
      name: 'admin-price-bands',
      component: () => import('@/views/desktop/admin/priceband/PriceBandAdminView.vue'),
      meta: { shell: 'desktop', titleKey: 'admin.band.title', roles: [1] },
    },
    {
      // 系统设置（M5-③）：滞销阈值/标签规格，仅管理员
      // （GET 全员可读是打印页/列表的口径，本页管理面收敛在 A；PUT /api/settings/** URL 级 RBAC 兜底）
      path: '/admin/settings',
      name: 'admin-settings',
      component: () => import('@/views/desktop/admin/settings/SettingsView.vue'),
      meta: { shell: 'desktop', titleKey: 'settings.title', roles: [1] },
    },
    {
      // 台帳ブラウズ（M5-④）：全库流水治理翻查，仅管理员
      // （流水不可删改=查询是唯一面；GET /api/inventory/ledgers URL 级 RBAC 兜底）
      path: '/ledgers',
      name: 'ledgers',
      component: () => import('@/views/desktop/admin/ledger/LedgersView.vue'),
      meta: { shell: 'desktop', titleKey: 'ledgers.title', roles: [1] },
    },
    {
      // 操作ログ（M5-④）：全系统操作留痕查询，仅管理员（验收 9：日志不可删改仅可查）
      path: '/admin/logs',
      name: 'admin-logs',
      component: () => import('@/views/desktop/admin/log/OperationLogsView.vue'),
      meta: { shell: 'desktop', titleKey: 'oplogs.title', roles: [1] },
    },
    {
      // システム状況（M5-④）：排障速览+告警+自检+诊断导出，仅管理员
      // （含池/磁盘等运行时内部信息，GET /api/stats/system URL 级 RBAC 兜底）
      path: '/admin/system',
      name: 'admin-system',
      component: () => import('@/views/desktop/admin/system/SystemView.vue'),
      meta: { shell: 'desktop', titleKey: 'system.title', roles: [1] },
    },
    {
      // 标签打印（M2-7）：桌面为主、全员可打印（录入手与贴标手常不同人）
      path: '/print',
      name: 'print',
      component: () => import('@/views/desktop/print/PrintView.vue'),
      meta: { shell: 'desktop', titleKey: 'print.title' },
    },
    {
      // 雅虎联动（M4）：CSV 导入/出荷待ち/照合三视图，全员可读；上传仅编辑者
      // 以上（服务端 @PreAuthorize 兜底）
      path: '/yahoo',
      name: 'yahoo',
      component: () => import('@/views/desktop/yahoo/YahooView.vue'),
      meta: { shell: 'desktop', titleKey: 'yahoo.title' },
    },
    {
      // エクセル連携（M4-⑤，D-058）：模板下载/双模式导入/帳票导出，全员可读；
      // 模板与上传仅编辑者以上（服务端 @PreAuthorize 兜底）
      path: '/excel',
      name: 'excel',
      component: () => import('@/views/desktop/excel/ExcelView.vue'),
      meta: { shell: 'desktop', titleKey: 'excel.title' },
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
  // 桌面壳下 '/' 落地大盘（D-072）：HomeView 归移动壳（欢迎语/账号/快捷入口），
  // 桌面首屏直接进指标速览；移动壳不受影响。useShell 返回 ref——守卫在
  // setup 外运行无自动解包，必须 .value（漏写时重定向静默失效，E2E 12 败全因它）
  if (to.name === 'home' && useShell().shell.value === 'desktop') {
    return { name: 'dashboard' }
  }
  // 桌面壳专属页在移动壳里不渲染（meta.shell）。App.vue 只认 useShell 选壳、
  // 不替路由改壳，所以不拦的话 24 吋屏的版面会原样塞进手机——「PC 缩小版」的
  // 源头之一。拦回移动首页并带上来路：首页用一句白话说明 + 一键切到电脑版再
  // 打开（壳切换可逆，桌面壳侧栏有切回手机版的入口）。
  // 带 fullPath 而非 titleKey：刷新后仍能复原，且不把 i18n 键写进地址栏。
  if (to.meta.shell === 'desktop' && useShell().shell.value === 'mobile') {
    return { name: 'home', query: { desktopOnly: to.fullPath } }
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
