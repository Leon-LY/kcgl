import { vi } from 'vitest'

// jsdom 未实现 blob URL API：录入照片本地预览（URL.createObjectURL）依赖它，
// 提供稳定的假实现——每个 spec 文件环境独立注入，互不影响
let blobSeq = 0

URL.createObjectURL = vi.fn((): string => `blob:mock-${++blobSeq}`)
URL.revokeObjectURL = vi.fn()

// jsdom 未实现 matchMedia：usePwaInstall（standalone 检测）与依赖媒体查询的
// 组件需要稳定假实现——默认不匹配（浏览器标签页形态），个别用例按需覆写
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})
