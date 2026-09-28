import { onBeforeUnmount } from 'vue'
import { useSyncStore } from '@/stores/sync'

/**
 * 列表视图的 SSE 失效接线（docs/01 7.6 粗粒度失效）：他人操作触发的
 * 事件经 500ms 防抖后整页重取——「另一端已变」不再依赖手动刷新。
 * reload 由页面提供（通常=重置回第 1 页；不传增量补丁，重取成本低）。
 * 视图卸载自动注销；自己操作的广播回声会多触发一次无害重取。
 */
export function useSyncInvalidation(types: readonly string[], reload: () => void): void {
  const sync = useSyncStore()
  const off = sync.onInvalidate((invalidated) => {
    if ([...invalidated].some((type) => types.includes(type))) {
      reload()
    }
  })
  onBeforeUnmount(off)
}
