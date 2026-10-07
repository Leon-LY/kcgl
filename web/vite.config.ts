/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { VantResolver } from '@vant/auto-import-resolver'
import { VitePWA } from 'vite-plugin-pwa'

// 构建配置：Vant 随 M2 录入页接入、Element Plus 随 M2-7 打印页接入（均按需，D-027）；
// PWA（vite-plugin-pwa，M6-①）：injectManifest 自定义 sw.ts（Background Sync
// 回放需要自定义事件处理器，generateSW 模式做不到）。
// vitest 的 SSR 管道不处理 node_modules 的 css 导入（"Unknown file extension .css"），
// 测试模式下关掉按需样式注入与 PWA 插件——单测不依赖视觉样式，也不应触碰 SW 虚拟模块。
const isVitest = Boolean(process.env.VITEST)
export default defineConfig({
  plugins: [
    vue(),
    Components({
      resolvers: [
        VantResolver({ importStyle: !isVitest }),
        ElementPlusResolver({ importStyle: !isVitest }),
      ],
    }),
    ...(!isVitest
      ? [
          VitePWA({
            registerType: 'autoUpdate',
            // 自定义 SW 源（src/sw.ts）：预缓存 + 图片 CacheFirst + Background Sync 回放；
            // srcDir/filename 是插件顶层选项（非 injectManifest 内），后者只收 workbox-build
            // 的打包参数（injectionPoint/globPatterns 等）
            strategies: 'injectManifest',
            srcDir: 'src',
            filename: 'sw.ts',
            injectManifest: {
              injectionPoint: 'self.__WB_MANIFEST',
              // 预缓存范围：构建产物 + 图标/manifest（大文件不进预缓存）
              globPatterns: ['**/*.{js,css,html,svg,png,webmanifest,woff2}'],
              maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
            },
            manifest: {
              name: '在庫管理システム',
              short_name: '在庫管理',
              description: '骨董品の在庫管理——管理番号・QR・台帳・ヤフー連携',
              lang: 'ja',
              dir: 'ltr',
              // 带 shell 参数强制移动壳：iPad PWA 的 UA 呈现为桌面（Mac Safari 同款），
              // 不带参数会被路由进桌面壳（docs/01 iOS 优先节）
              start_url: '/?shell=mobile',
              scope: '/',
              display: 'standalone',
              orientation: 'portrait-primary',
              background_color: '#f5f6f7',
              theme_color: '#2062a6',
              icons: [
                { src: '/icons/pwa-192.png', sizes: '192x192', type: 'image/png' },
                { src: '/icons/pwa-512.png', sizes: '512x512', type: 'image/png' },
                { src: '/icons/pwa-512-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
              ],
            },
          }),
        ]
      : []),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  optimizeDeps: {
    // 双 UI 库按需解析器（unplugin-vue-components）在 SFC 变换时注入组件 JS 桶与
    // 逐组件样式子路径——启动依赖扫描器看不到它们。冷依赖缓存（CI 全新 checkout、
    // 增量依赖变化）下首次加载对应页面才被发现 → vite 运行时 re-optimize 触发
    // 整页 reload，与 SPA 导航竞态（E2E 曾在首跳 /admin/venues 时偶发白屏超时）。
    // 显式预打包消除该类竞态；新增 EP/Vant 组件后按报错提示把样式子路径补进来。
    // D-107：这份清单是**手工白名单**，缺项只在冷依赖缓存（CI 新 checkout、本机
    // 删掉 node_modules/.vite）下才现形——首跳对应页面时 Vite 打印
    // `dependency optimized: …` + `optimized dependencies changed. reloading` 整页
    // 重载，与 SPA 导航/点击竞态。补项时拿 `grep -rhoE '<(el|van)-[a-z0-9-]+' src`
    // 的标签清单整体对账，别只跟着那次报错补一条。
    include: [
      'element-plus/es',
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/button/style/css',
      // D-126：表格勾选列（type="selection"）内部渲染 ElCheckbox，它不经模板标签出现，
      // 解析器与上面的标签清单都看不见它——冷依赖缓存下首次进商品页才被发现
      'element-plus/es/components/checkbox/style/css',
      'element-plus/es/components/config-provider/style/css',
      'element-plus/es/components/date-picker/style/css',
      'element-plus/es/components/descriptions/style/css',
      'element-plus/es/components/descriptions-item/style/css',
      'element-plus/es/components/dialog/style/css',
      // D-107 补齐：el-dropdown*（D-106 商品页「一括入出力」首次使用）与
      // tab-pane / descriptions-item 一样，曾是清单里的缺项。
      'element-plus/es/components/dropdown/style/css',
      'element-plus/es/components/dropdown-item/style/css',
      'element-plus/es/components/dropdown-menu/style/css',
      'element-plus/es/components/image/style/css',
      'element-plus/es/components/input/style/css',
      'element-plus/es/components/input-number/style/css',
      'element-plus/es/components/loading/style/css',
      'element-plus/es/components/option/style/css',
      'element-plus/es/components/pagination/style/css',
      'element-plus/es/components/radio-button/style/css',
      'element-plus/es/components/radio-group/style/css',
      'element-plus/es/components/select/style/css',
      'element-plus/es/components/switch/style/css',
      'element-plus/es/components/tab-pane/style/css',
      'element-plus/es/components/table-column/style/css',
      'element-plus/es/components/table/style/css',
      'element-plus/es/components/tabs/style/css',
      'vant/es',
      'vant/es/button/style/index',
      'vant/es/cell-group/style/index',
      'vant/es/cell/style/index',
      'vant/es/date-picker/style/index',
      'vant/es/field/style/index',
      'vant/es/form/style/index',
      'vant/es/list/style/index',
      'vant/es/picker/style/index',
      'vant/es/popup/style/index',
      'vant/es/tabbar-item/style/index',
      'vant/es/tabbar/style/index',
    ],
  },
  server: {
    port: 5173,
    // 钉 IPv4：默认 localhost 在部分环境仅绑 ::1，导致 127.0.0.1 探活/访问失败
    host: '127.0.0.1',
    proxy: {
      '/api': {
        // 默认本地 compose app（8080）；E2E 由 Playwright 注入 KCGL_API_TARGET 指向一次性栈
        target: process.env.KCGL_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
      // 图片直出（StaticResourceConfig /img/thumb|orig）：dev/E2E 下缩略图随 API 同源代理，
      // 与生产 nginx 直出同路径（标签打印页/继承图片预览消费）
      '/img': {
        target: process.env.KCGL_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    setupFiles: ['src/test/setup.ts'],
    // 全测试套钉死 Asia/Shanghai（docs/01 7.8）：用非 JST 本地时区跑 JST 边界用例，
    // 本地解析类回归（dayjs(str) 误用）当场暴露而非到日本生产才「自愈」。
    env: {
      TZ: 'Asia/Shanghai',
    },
    coverage: {
      // 门禁口径（docs/01 十一节）：stores/composables/utils 行覆盖 ≥80%
      include: ['src/stores/**', 'src/composables/**', 'src/utils/**'],
      thresholds: { lines: 80 },
    },
  },
})
