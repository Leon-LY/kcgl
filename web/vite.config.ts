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
