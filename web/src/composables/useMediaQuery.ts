import { onScopeDispose, ref, type Ref } from 'vue'

/**
 * 媒体查询唯一出口（响应式断点的公共实现）。
 *
 * 用途：表格列宽放不下时按屏宽分档收列（商品一覧，见 D-120）。断点判定必须
 * 走 CSS 媒体查询而不是量元素宽度——jsdom 无布局（元素宽度恒 0），ResizeObserver
 * 也没有原生实现，只有 matchMedia 有稳定桩（src/test/setup.ts），测量式方案在
 * 单测里根本跑不起来。
 *
 * 初始值取 `media.matches` 而不是「等事件」：桩的 addEventListener 是空实现，
 * 永不派发 change；真实浏览器里也要首帧就正确，否则首屏会闪一下错误档位。
 */
export function useMediaQuery(query: string): Ref<boolean> {
  const matches = ref(false)

  // 防御：非浏览器环境（构建期预渲染/测试未装桩）直接返回恒 false，不抛
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return matches
  }

  const media = window.matchMedia(query)
  matches.value = media.matches

  const onChange = (event: MediaQueryListEvent): void => {
    matches.value = event.matches
  }
  media.addEventListener('change', onChange)
  onScopeDispose(() => media.removeEventListener('change', onChange))

  return matches
}
