import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  api,
  ApiError,
  cancelStocktake,
  closeStocktake,
  confirmArrivals,
  createItem,
  createStocktake,
  deleteItem,
  downloadExcelExport,
  downloadExcelTemplate,
  fetchChecklist,
  fetchExcelBatches,
  fetchExcelBatch,
  fetchItemByCode,
  fetchItemImages,
  fetchItemLedgers,
  fetchItemYahooListings,
  fetchItemsForPrint,
  fetchPendingArrivals,
  fetchPendingShipments,
  fetchPriceBands,
  fetchRecycleBin,
  fetchStocktake,
  fetchStocktakeDiffs,
  fetchStocktakes,
  fetchTodaySession,
  fetchYahooBatches,
  fetchYahooBatch,
  fetchYahooReconcile,
  fetchVenues,
  markChecklistPrintDone,
  markListedItem,
  previewItemCode,
  resolveStocktakeDiff,
  restoreItem,
  returnItem,
  scanStocktakeItem,
  scrapItem,
  searchItems,
  sellItem,
  setUnauthorizedHandler,
  transferItem,
  updateItem,
  uploadExcelWorkbook,
  uploadImage,
  uploadYahooCsv,
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
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { id: 101, username: 'taro', displayName: '太郎', role: 2, locale: 'ja-JP', mustChangePwd: false } }))
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
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { code: 'HT9-A1X', bandCode: 'X', seqPrefix: 'A', seqNo: 1 } }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(previewItemCode(7, '2026-09-15', 1000)).resolves.toEqual({
      code: 'HT9-A1X',
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
      .mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: { id: 1, itemCode: 'HT9-A1X' } }))
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

describe('item search, edit, recycle bin, and history endpoint contracts (M5-1)', () => {
  function lastCall(fetchMock: ReturnType<typeof vi.fn>): [string, RequestInit] {
    return fetchMock.mock.calls[0] as [string, RequestInit]
  }

  function stubOk(data: unknown): ReturnType<typeof vi.fn> {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data }))
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('searchItems assembles the full query in canonical order', async () => {
    const fetchMock = stubOk({ total: 0, page: 3, size: 50, rows: [] })
    await searchItems({
      kw: 'HT9',
      warehouse: 2,
      stockStatus: 1,
      saleStatus: 2,
      venueId: 5,
      buyDateFrom: '2026-01-01',
      buyDateTo: '2026-01-31',
      warnLevel: 2,
      page: 3,
      size: 50,
    })
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe(
      '/api/items/search?kw=HT9&warehouse=2&stockStatus=1&saleStatus=2&venueId=5'
      + '&warnLevel=2&buyDateFrom=2026-01-01&buyDateTo=2026-01-31&page=3&size=50',
    )
    expect(init.method).toBe('GET')
  })

  it('searchItems omits undefined and empty-string filters entirely', async () => {
    const fetchMock = stubOk({ total: 0, page: 1, size: 20, rows: [] })
    await searchItems({ kw: '', buyDateFrom: '', warehouse: 1 })
    const [path] = lastCall(fetchMock)
    expect(path).toBe('/api/items/search?warehouse=1')
  })

  it('updateItem → PUTs the full-payload edit body with version (D-066 snapshot edit)', async () => {
    const fetchMock = stubOk({ id: 7, itemCode: 'HT9-A1X' })
    await updateItem(7, {
      version: 3,
      venueId: 1,
      buyDate: '2026-01-15',
      purchasePrice: 2000,
      warehouse: 1,
      shelfNo: null,
      remark: 'E2E',
    })
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/items/7')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({
      version: 3,
      venueId: 1,
      buyDate: '2026-01-15',
      purchasePrice: 2000,
      warehouse: 1,
      shelfNo: null,
      remark: 'E2E',
    })
  })

  it('deleteItem → DELETEs with clientReqId and null reason when omitted', async () => {
    const fetchMock = stubOk({ id: 9 })
    await deleteItem(9, 'req-del')
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/items/9')
    expect(init.method).toBe('DELETE')
    expect(JSON.parse(init.body as string)).toEqual({ clientReqId: 'req-del', reason: null })
  })

  it('deleteItem passes an explicit reason through for the audit trail', async () => {
    const fetchMock = stubOk({ id: 9 })
    await deleteItem(9, 'req-del', '整理')
    expect(JSON.parse(lastCall(fetchMock)[1].body as string)).toEqual({
      clientReqId: 'req-del',
      reason: '整理',
    })
  })

  it('restoreItem → POSTs the idempotency key to /restore', async () => {
    const fetchMock = stubOk({ id: 9 })
    await restoreItem(9, 'req-res')
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/items/9/restore')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ clientReqId: 'req-res' })
  })

  it('fetchRecycleBin defaults to page 1 size 20 and accepts explicit paging', async () => {
    const fetchMock = stubOk({ total: 0, page: 1, size: 20, rows: [] })
    await fetchRecycleBin()
    expect(lastCall(fetchMock)[0]).toBe('/api/items/recycle-bin?page=1&size=20')
    await fetchRecycleBin(3, 50)
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/items/recycle-bin?page=3&size=50')
  })

  it('fetchItemLedgers / fetchItemYahooListings → GET the per-item history endpoints', async () => {
    const fetchMock = stubOk({ rows: [] })
    await fetchItemLedgers(5)
    expect(lastCall(fetchMock)[0]).toBe('/api/items/5/ledgers')
    await fetchItemYahooListings(5)
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/items/5/yahoo-listings')
  })
})

describe('module endpoint contracts (print / image / arrival / scan actions / stocktake / yahoo / excel)', () => {
  function lastCall(fetchMock: ReturnType<typeof vi.fn>): [string, RequestInit] {
    return fetchMock.mock.calls[0] as [string, RequestInit]
  }

  function stubOk(data: unknown): ReturnType<typeof vi.fn> {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data }))
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  function bodyOf(init: RequestInit): Record<string, unknown> {
    return JSON.parse(init.body as string) as Record<string, unknown>
  }

  it('fetchItemsForPrint assembles date range plus optional filters', async () => {
    const fetchMock = stubOk({ total: 0, page: 2, size: 100, rows: [] })
    await fetchItemsForPrint({ createdFrom: '2026-09-01', createdTo: '2026-09-30', venueId: 1, code: 'HT9-A1X', page: 2, size: 100 })
    expect(lastCall(fetchMock)[0]).toBe(
      '/api/items?createdFrom=2026-09-01&createdTo=2026-09-30&venueId=1&code=HT9-A1X&page=2&size=100',
    )
    await fetchItemsForPrint({ createdFrom: '2026-09-01', createdTo: '2026-09-30', code: '' })
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/items?createdFrom=2026-09-01&createdTo=2026-09-30')
  })

  it('uploadImage posts FormData without a manual Content-Type (browser sets the boundary)', async () => {
    const fetchMock = stubOk({ id: 1 })
    const form = new FormData()
    await uploadImage(form)
    const [path, init] = lastCall(fetchMock)
    expect(path).toBe('/api/images')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(form)
    expect((init.headers as Record<string, string> | undefined)?.['Content-Type']).toBeUndefined()
  })

  it('fetchItemImages → GET the per-item image list', async () => {
    const fetchMock = stubOk([])
    await fetchItemImages(3)
    expect(lastCall(fetchMock)[0]).toBe('/api/items/3/images')
  })

  it('fetchPendingArrivals keeps warehouse optional and pages', async () => {
    const fetchMock = stubOk({ total: 0, page: 1, size: 20, rows: [] })
    await fetchPendingArrivals(null, 2)
    expect(lastCall(fetchMock)[0]).toBe('/api/inventory/arrivals/pending?page=2&size=20')
    await fetchPendingArrivals(1, 2, 50)
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/inventory/arrivals/pending?page=2&size=50&warehouse=1')
  })

  it('confirmArrivals omits warehouseInDate when not supplied (server defaults to today JST)', async () => {
    const fetchMock = stubOk({ arrivedCount: 0, items: [] })
    const items = [{ itemId: 1, clientReqId: 'req-a' }]
    await confirmArrivals(items)
    expect(bodyOf(lastCall(fetchMock)[1])).toEqual({ items })
    await confirmArrivals(items, '2026-09-28')
    expect(bodyOf(fetchMock.mock.calls[1]![1] as RequestInit)).toEqual({ items, warehouseInDate: '2026-09-28' })
  })

  it('session, checklist, and by-code locate endpoints hit their paths', async () => {
    const fetchMock = stubOk(null)
    await fetchTodaySession()
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/items/today-session')
    await fetchChecklist()
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/checklist')
    await markChecklistPrintDone()
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/checklist/print-done')
    await fetchItemByCode('HT9-A1X')
    expect(fetchMock.mock.calls[3]![0]).toBe('/api/items/by-code/HT9-A1X')
  })

  it('inventory action wrappers pass idempotency keys and action payloads', async () => {
    const fetchMock = stubOk({ itemId: 1, itemCode: 'HT9-A1X', stockStatus: 1, saleStatus: 0, warehouse: 1 })
    await sellItem(1, 'req-s')
    expect(lastCall(fetchMock)[0]).toBe('/api/inventory/sell')
    expect(bodyOf(lastCall(fetchMock)[1])).toEqual({ itemId: 1, clientReqId: 'req-s' })
    await sellItem(1, 'req-s2', 8000)
    expect(bodyOf(fetchMock.mock.calls[1]![1] as RequestInit)).toEqual({ itemId: 1, clientReqId: 'req-s2', soldPrice: 8000 })

    await scrapItem(1, 'req-x', '破損')
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/inventory/scrap')
    expect(bodyOf(fetchMock.mock.calls[2]![1] as RequestInit)).toEqual({ itemId: 1, clientReqId: 'req-x', reason: '破損' })

    await transferItem(1, 'req-t', 2)
    expect(fetchMock.mock.calls[3]![0]).toBe('/api/inventory/transfer')
    expect(bodyOf(fetchMock.mock.calls[3]![1] as RequestInit)).toEqual({ itemId: 1, clientReqId: 'req-t', toWarehouse: 2 })

    await returnItem(1, 'req-r', 2)
    expect(fetchMock.mock.calls[4]![0]).toBe('/api/inventory/return')
    expect(bodyOf(fetchMock.mock.calls[4]![1] as RequestInit)).toEqual({ itemId: 1, clientReqId: 'req-r', direction: 2 })
    await returnItem(1, 'req-r2', 1, '開封済み')
    expect(bodyOf(fetchMock.mock.calls[5]![1] as RequestInit)).toEqual({ itemId: 1, clientReqId: 'req-r2', direction: 1, note: '開封済み' })

    await markListedItem(1, 'req-m')
    expect(fetchMock.mock.calls[6]![0]).toBe('/api/inventory/mark-listed')
    expect(bodyOf(fetchMock.mock.calls[6]![1] as RequestInit)).toEqual({ itemId: 1, clientReqId: 'req-m' })
  })

  it('stocktake lifecycle endpoints assemble queries and payloads', async () => {
    const summary = { id: 4, stocktakeNo: 'PD20260929-1', warehouse: 1, status: 0, expectedCount: null, scannedCount: 0, diffCount: null, pendingDiffCount: null, createdAt: '2026-09-29 09:00:00', closedAt: null, createdByName: 'admin', closedByName: null, mine: true }
    const fetchMock = stubOk(summary)

    await createStocktake(1)
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/stocktakes')
    expect(bodyOf(fetchMock.mock.calls[0]![1] as RequestInit)).toEqual({ warehouse: 1 })

    await fetchStocktakes(1)
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/stocktakes?page=1&size=20')
    await fetchStocktakes(1, 50, 0)
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/stocktakes?page=1&size=50&status=0')

    await fetchStocktake(4)
    expect(fetchMock.mock.calls[3]![0]).toBe('/api/stocktakes/4')

    await scanStocktakeItem(4, 'HT9-A1X')
    expect(fetchMock.mock.calls[4]![0]).toBe('/api/stocktakes/4/scans')
    expect(bodyOf(fetchMock.mock.calls[4]![1] as RequestInit)).toEqual({ code: 'HT9-A1X' })

    await closeStocktake(4)
    expect(fetchMock.mock.calls[5]![0]).toBe('/api/stocktakes/4/close')
    await cancelStocktake(4)
    expect(fetchMock.mock.calls[6]![0]).toBe('/api/stocktakes/4/cancel')

    await fetchStocktakeDiffs(4, 1)
    expect(fetchMock.mock.calls[7]![0]).toBe('/api/stocktakes/4/diffs?page=1&size=20')
    await fetchStocktakeDiffs(4, 1, 50, 0)
    expect(fetchMock.mock.calls[8]![0]).toBe('/api/stocktakes/4/diffs?page=1&size=50&confirmStatus=0')

    await resolveStocktakeDiff(4, 9, 'CONFIRM', 'req-c')
    expect(fetchMock.mock.calls[9]![0]).toBe('/api/stocktakes/4/diffs/9')
    expect(bodyOf(fetchMock.mock.calls[9]![1] as RequestInit)).toEqual({ action: 'CONFIRM', clientReqId: 'req-c' })
  })

  it('yahoo import, reconcile, and shipment endpoints hit their paths', async () => {
    const fetchMock = stubOk(null)
    const form = new FormData()
    await uploadYahooCsv(form)
    expect(lastCall(fetchMock)).toEqual(['/api/yahoo/imports', { method: 'POST', body: form }])
    await fetchYahooBatches()
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/yahoo/imports')
    await fetchYahooBatch(7)
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/yahoo/imports/7')
    await fetchYahooReconcile()
    expect(fetchMock.mock.calls[3]![0]).toBe('/api/yahoo/reconcile')
    await fetchPendingShipments()
    expect(fetchMock.mock.calls[4]![0]).toBe('/api/yahoo/pending-shipments')
  })

  it('excel upload and batch endpoints hit their paths', async () => {
    const fetchMock = stubOk(null)
    const form = new FormData()
    await uploadExcelWorkbook(form)
    expect(lastCall(fetchMock)).toEqual(['/api/excel/items/import', { method: 'POST', body: form }])
    await fetchExcelBatches()
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/excel/items/imports')
    await fetchExcelBatch(7)
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/excel/items/imports/7')
  })
})

describe('binary downloads (requestBlob: template and export)', () => {
  function blobResponse(headers: Record<string, string>): Response {
    return {
      status: 200,
      ok: true,
      headers: new Headers(headers),
      blob: async () => new Blob(['xlsx'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
    } as unknown as Response
  }

  it('downloadExcelTemplate decodes an RFC 5987 filename and falls back when absent', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      blobResponse({ 'Content-Disposition': "attachment; filename*=UTF-8''%E5%95%86%E5%93%81%E7%99%BB%E9%8C%B2%E3%83%86%E3%83%B3%E3%83%97%E3%83%AC%E3%83%BC%E3%83%88.xlsx" }),
    ))
    await expect(downloadExcelTemplate()).resolves.toMatchObject({ filename: '商品登録テンプレート.xlsx' })

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(blobResponse({})))
    await expect(downloadExcelTemplate()).resolves.toMatchObject({ filename: '商品登録テンプレート.xlsx' })

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      blobResponse({ 'Content-Disposition': 'attachment; filename="legacy.xlsx"' }),
    ))
    await expect(downloadExcelTemplate()).resolves.toMatchObject({ filename: 'legacy.xlsx' })
  })

  it('downloadExcelExport assembles the filter query and trims the code', async () => {
    const fetchMock = vi.fn().mockResolvedValue(blobResponse({}))
    vi.stubGlobal('fetch', fetchMock)
    await downloadExcelExport({ createdFrom: '2026-09-01', createdTo: '2026-09-30', venueId: 2, code: '  HT9-A1X  ' })
    expect(fetchMock.mock.calls[0]![0]).toBe(
      '/api/excel/items/export?createdFrom=2026-09-01&createdTo=2026-09-30&venueId=2&code=HT9-A1X',
    )
    await downloadExcelExport({ createdFrom: '2026-09-01', createdTo: '2026-09-30', code: '' })
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/excel/items/export?createdFrom=2026-09-01&createdTo=2026-09-30')
  })

  it('requestBlob normalizes non-OK responses into ApiError via the JSON envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ code: 400001, message: '期間が逆です', data: null }, 400),
    ))
    const error = await downloadExcelTemplate().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(400001)
  })

  it('requestBlob triggers the unauthorized handler on 401', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ code: 401001, message: 'unauthorized', data: null }, 401)))
    await expect(downloadExcelTemplate()).rejects.toBeInstanceOf(ApiError)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('requestBlob throws NETWORK_ERROR when fetch fails outright', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = await downloadExcelTemplate().catch((e: unknown) => e)
    expect((error as ApiError).message).toBe('NETWORK_ERROR')
  })
})
