/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建配置：双 shell 路由级分包在 M1 落地（vant/element-plus 按需引入）；
// PWA（vite-plugin-pwa）在 M6 接入。
export default defineConfig({
  plugins: [vue()],
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
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
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
