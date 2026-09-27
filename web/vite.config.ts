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
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    coverage: {
      // 门禁口径（docs/01 十一节）：stores/composables/utils 行覆盖 ≥80%
      include: ['src/stores/**', 'src/composables/**', 'src/utils/**'],
      thresholds: { lines: 80 },
    },
  },
})
