import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  api,
  ApiError,
  createItem,
  fetchPriceBands,
  fetchVenues,
  previewItemCode,
  setUnauthorizedHandler,
} from './api'

/** 仅实现包装层用到的 status/json 两个成员的最小 Response 替身。 */
function jsonResponse(body: unknown, status = 200): Response {
  return { status, json: async () => body } as unknown as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(null)
})

describe('request envelope parsing', () => {
  it('returns the data payload when code=0', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { value: 42 } })),
    )
    await expect(api.me()).resolves.toEqual({ value: 42 })
  })

  it('throws ApiError passing through code/message for non-zero business codes', async () => {
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

  it('passes errorId through on 500 (users can report it for troubleshooting)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ code: 500000, message: 'システムエラー', data: null, errorId: 'a1b2c3d4' }, 500),
      ),
    )
    const error = await api.me().catch((e: unknown) => e)
    expect((error as ApiError).errorId).toBe('a1b2c3d4')
  })

  it('triggers the global unauthorized handler on 401 from non-login endpoints', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ code: 401001, message: 'ログインが必要です', data: null }, 401)),
    )
    await expect(api.me()).rejects.toBeInstanceOf(ApiError)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('skips the global handler on 401 from the login endpoint (login failures surface inline on the page)', async () => {
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

  it('throws NETWORK_ERROR when the network is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = await api.me().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(0)
    expect((error as ApiError).message).toBe('NETWORK_ERROR')
  })

  it('throws INVALID_RESPONSE for non-JSON responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ status: 502, json: async () => { throw new Error('not json') } } as unknown as Response),
    )
    const error = await api.me().catch((e: unknown) => e)
    expect((error as ApiError).message).toBe('INVALID_RESPONSE')
  })
})

describe('api.login', () => {
  it('submits form-encoded credentials (framework formLogin contract, D-021)', async () => {
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

describe('remaining api endpoint contracts', () => {
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

  it('changePassword → PUTs a JSON payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.changePassword('old-pass', 'new-pass-123')
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/auth/me/password')
    expect(init.method).toBe('PUT')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({ oldPassword: 'old-pass', newPassword: 'new-pass-123' })
  })

  it('changeLocale → PUTs a JSON payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.changeLocale('zh-CN')
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/auth/me/locale')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({ locale: 'zh-CN' })
  })

  it('reportClientError → POSTs with fields passed through (client error reporting)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: null }))
    vi.stubGlobal('fetch', fetchMock)
    await api.reportClientError({ message: 'boom', errorId: 'a1b2c3d4', queuePending: 3 })
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/client-errors')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ message: 'boom', errorId: 'a1b2c3d4', queuePending: 3 })
  })
})

describe('dictionary and item entry endpoint contracts', () => {
  function lastCall(fetchMock: ReturnType<typeof vi.fn>): [string, RequestInit] {
    return fetchMock.mock.calls[0] as [string, RequestInit]
  }

  it('fetchVenues(true) → GET /api/venues?enabled=true', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: [{ id: 1, code: 'HT', name: '飛騨古民具市', enabled: true }] }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(fetchVenues(true)).resolves.toHaveLength(1)
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/venues?enabled=true')
    expect(init.method).toBe('GET')
  })

  it('fetchVenues(false) → GET /api/venues (no query string)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: [] }))
    vi.stubGlobal('fetch', fetchMock)
    await fetchVenues(false)
    expect(lastCall(fetchMock)[0]).toBe('/api/venues')
  })

  it('fetchPriceBands → GET /api/price-bands', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: [] }))
    vi.stubGlobal('fetch', fetchMock)
    await fetchPriceBands()
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/price-bands')
    expect(init.method).toBe('GET')
  })

  it('previewItemCode → GET with assembled preview query params', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { code: 'HTK9-A1X', bandCode: 'X', seqPrefix: 'A', seqNo: 1 } }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(previewItemCode(7, '2026-09-15', 1000)).resolves.toEqual({
      code: 'HTK9-A1X',
      bandCode: 'X',
      seqPrefix: 'A',
      seqNo: 1,
    })
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/item-codes/preview?venueId=7&buyDate=2026-09-15&price=1000')
    expect(init.method).toBe('GET')
  })

  it('createItem → POSTs a JSON payload (clientReqId idempotency key passed through)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { id: 1, itemCode: 'HTK9-A1X' } }))
    vi.stubGlobal('fetch', fetchMock)
    await createItem({
      clientReqId: 'req-abc',
      venueId: 7,
      buyDate: '2026-09-15',
      purchasePrice: 1000,
      warehouse: 1,
      fee: 300,
    })
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/items')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({
      clientReqId: 'req-abc',
      venueId: 7,
      buyDate: '2026-09-15',
      purchasePrice: 1000,
      warehouse: 1,
      fee: 300,
    })
  })
})
