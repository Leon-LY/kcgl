import { vi } from 'vitest'

// jsdom 未实现 blob URL API：录入照片本地预览（URL.createObjectURL）依赖它，
// 提供稳定的假实现——每个 spec 文件环境独立注入，互不影响
let blobSeq = 0

URL.createObjectURL = vi.fn((): string => `blob:mock-${++blobSeq}`)
URL.revokeObjectURL = vi.fn()
