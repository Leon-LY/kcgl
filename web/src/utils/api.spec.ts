import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setUnauthorizedHandler } from './api'

/** 仅实现包装层用到的 status/json 两个成员的最小 Response 替身。 */
function jsonResponse(body: unknown, status = 200): Response {
  return { status, json: async () => body } as unknown as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(null)
})

describe('request 信封解析', () => {
  it('code=0 返回 data 载荷', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { value: 42 } })),
    )
    await expect(api.me()).resolves.toEqual({ value: 42 })
  })

  it('非 0 业务码抛 ApiError 且透传 code/message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ code: 401002, message: 'ユーザー名またはパスワードが正しくありません', data: null }, 401),
      ),
    )
    const error = await api.me().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(401002)
    expect((error as ApiError).message).toContain('パスワード')
  })

  it('500 带 errorId 时透传（用户可报此 ID 排障）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ code: 500000, message: 'システムエラー', data: null, errorId: 'a1b2c3d4' }, 500),
      ),
    )
    const error = await api.me().catch((e: unknown) => e)
    expect((error as ApiError).errorId).toBe('a1b2c3d4')
  })

  it('401 非登录端点触发全局登出处理', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ code: 401001, message: 'ログインが必要です', data: null }, 401)),
    )
    await expect(api.me()).rejects.toBeInstanceOf(ApiError)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('401 登录端点不触发全局处理（登录失败由页面就地提示）', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ code: 401002, message: '認証失敗', data: null }, 401),
      ),
    )
    await expect(api.login('taro', 'wrong')).rejects.toBeInstanceOf(ApiError)
    expect(handler).not.toHaveBeenCalled()
  })

  it('网络不可达抛 NETWORK_ERROR', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = await api.me().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(0)
    expect((error as ApiError).message).toBe('NETWORK_ERROR')
  })

  it('非 JSON 响应抛 INVALID_RESPONSE', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ status: 502, json: async () => { throw new Error('not json') } } as unknown as Response),
    )
    const error = await api.me().catch((e: unknown) => e)
    expect((error as ApiError).message).toBe('INVALID_RESPONSE')
  })
})

describe('api.login', () => {
  it('走表单编码提交（框架 formLogin 契约，D-021）', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { username: 'taro', displayName: '太郎', role: 2, locale: 'ja-JP', mustChangePwd: false } }))
    vi.stubGlobal('fetch', fetchMock)

    await api.login('taro', 'pass-word-123')

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/auth/login')
    expect(init.method).toBe('POST')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/x-www-form-urlencoded',
    )
    expect(init.body).toBeInstanceOf(URLSearchParams)
    expect((init.body as URLSearchParams).get('username')).toBe('taro')
  })
})

describe('api 其余端点契约', () => {
  function lastCall(fetchMock: ReturnType<typeof vi.fn>): [string, RequestInit] {
    return fetchMock.mock.calls[0] as [string, RequestInit]
  }

  it('logout → POST /api/auth/logout', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.logout()
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/auth/logout')
    expect(init.method).toBe('POST')
  })

  it('changePassword → PUT JSON 载荷', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.changePassword('old-pass', 'new-pass-123')
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/auth/me/password')
    expect(init.method).toBe('PUT')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({ oldPassword: 'old-pass', newPassword: 'new-pass-123' })
  })

  it('changeLocale → PUT JSON 载荷', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.changeLocale('zh-CN')
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/auth/me/locale')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({ locale: 'zh-CN' })
  })

  it('reportClientError → POST 字段透传（前端错误上报）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.reportClientError({ message: 'boom', errorId: 'a1b2c3d4', queuePending: 3 })
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/client-errors')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ message: 'boom', errorId: 'a1b2c3d4', queuePending: 3 })
  })
})
