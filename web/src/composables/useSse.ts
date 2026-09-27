import { useSyncStore } from '@/stores/sync'

/**
 * SSE 挂载点（App.vue 调用一次，docs/01 7.6）：连接/重建/失效分发
 * 全部在 sync store 单例内闭环，本函数仅负责唯一初始化。
 */
export function useSse(): void {
  useSyncStore().init()
}
