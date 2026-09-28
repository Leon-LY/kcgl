/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { VantResolver } from '@vant/auto-import-resolver'

// 构建配置：Vant 随 M2 录入页接入、Element Plus 随 M2-7 打印页接入（均按需，D-027）；
// PWA（vite-plugin-pwa）在 M6 接入。
// vitest 的 SSR 管道不处理 node_modules 的 css 导入（"Unknown file extension .css"），
// 测试模式下关掉按需样式注入——单测不依赖视觉样式。
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
    include: [
      'element-plus/es',
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/button/style/css',
      'element-plus/es/components/config-provider/style/css',
      'element-plus/es/components/date-picker/style/css',
      'element-plus/es/components/dialog/style/css',
      'element-plus/es/components/input/style/css',
      'element-plus/es/components/loading/style/css',
      'element-plus/es/components/option/style/css',
      'element-plus/es/components/radio-button/style/css',
      'element-plus/es/components/radio-group/style/css',
      'element-plus/es/components/select/style/css',
      'element-plus/es/components/switch/style/css',
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
