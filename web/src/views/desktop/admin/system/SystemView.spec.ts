import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchSystemStatus: vi.fn(),
  fetchAlerts: vi.fn(),
  markAlertRead: vi.fn(),
  runSelfCheck: vi.fn(),
  downloadDiagnosticsExport: vi.fn(),
}))

// ApiError/errors 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchSystemStatus: apiMocks.fetchSystemStatus,
    fetchAlerts: apiMocks.fetchAlerts,
    markAlertRead: apiMocks.markAlertRead,
    runSelfCheck: apiMocks.runSelfCheck,
    downloadDiagnosticsExport: apiMocks.downloadDiagnosticsExport,
  }
})

import SystemView from './SystemView.vue'
import { i18n } from '@/i18n'
import type { SelfCheckReport, SysAlert, SystemStatus } from '@/utils/api'

/**
 * システム状況（M5-④）：白名单字段速览卡（版本/资源/水位）+ 近 7 日错误条形 +
 * 告警已读（联动刷新）+ 手动自检报告（五节 OK/NG）+ 诊断包下载（blob 保存）。
 */

const STATUS: SystemStatus = {
  appVersion: '1.2.0',
  flywayVersion: '5',
  startedAt: '2026-09-28 04:00:00',
  uptimeSeconds: 3_730,
  heap: { usedBytes: 268_435_456, maxBytes: 4_294_967_296 },
  pool: { active: 1, idle: 2, total: 3, waiting: 0 },
  sseConnections: 2,
  disk: { path: '/data/images', totalBytes: 0, usableBytes: 0, usedPercent: 0 },
  volumes: { items: 1200, ledgers: 5600, operationLogs: 9000, clientErrors: 3 },
  codeEngine: { issued: 1200, skipped: 12 },
  excelBatches: {
    total: 4,
    done: 3,
    failed: 1,
    processing: 0,
    recent: [
      {
        id: 4,
        originalFilename: '在庫リスト9月.xlsx',
        status: 2,
        rowCount: 5,
        errorCount: 2,
        createdAt: '2026-09-02 11:00:00',
        finishedAt: '2026-09-02 11:05:00',
      },
    ],
  },
  clientErrors7d: [
    { date: '2026-09-24', count: 0 },
    { date: '2026-09-25', count: 1 },
    { date: '2026-09-26', count: 0 },
    { date: '2026-09-27', count: 0 },
    { date: '2026-09-28', count: 2 },
    { date: '2026-09-29', count: 0 },
    { date: '2026-09-30', count: 1 },
  ],
  openAlerts: 1,
  // backup=null（未配置/无状态文件）= 不可知占位——测试默认形态
  backup: null,
}

/** 新鲜备份（1 小时前成功）。 */
function freshBackup() {
  return {
    lastSuccessAt: '2026-09-30T02:17:00+09:00',
    lastSuccessAtJst: '2026-09-30 03:17:00',
    staleSeconds: 3600,
    detail: 'db 1.2MiB',
  }
}

function alert(overrides: Partial<SysAlert> = {}): SysAlert {
  return {
    id: 9,
    type: 'DISK_USAGE',
    dedupKey: 'disk-usage',
    level: 2,
    message: 'ディスク使用率が閾値を超えました',
    payload: null,
    status: 0,
    readBy: null,
    readAt: null,
    createdAt: '2026-09-28 04:17:00',
    ...overrides,
  }
}

const REPORT: SelfCheckReport = {
  ranAt: '2026-09-30 09:00:00',
  ok: true,
  ledger: { ok: true, balances: [], driftCount: 0, drifts: [] },
  counters: { ok: true, mismatches: [] },
  volumes: {
    ok: true,
    itemCount: 1200,
    ledgerCount: 5600,
    logCount: 9000,
    itemThreshold: 150_000,
    ledgerThreshold: 3_000_000,
    logThreshold: 3_000_000,
  },
  disk: { ok: true, path: '/data/images', totalBytes: 0, usableBytes: 0, usedPercent: 0 },
  imageAudit: { ok: true, orphanCount: 0, orphanSamples: [], missingCount: 0, missingSamples: [] },
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(SystemView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  apiMocks.fetchSystemStatus.mockResolvedValue(STATUS)
  apiMocks.fetchAlerts.mockResolvedValue({ list: [], total: 0, page: 1, size: 50 })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('system view (M5-4)', () => {
  it('renders overview, resource, and volume cards from GET /api/stats/system', async () => {
    const wrapper = await mountView()

    const overview = wrapper.find('.system-stat.is-version')
    expect(overview.text()).toContain('1.2.0')
    expect(wrapper.find('.system-stat.is-sse').text()).toContain('2')
    expect(wrapper.find('.system-stat.is-openAlerts').text()).toContain('1')
    // 堆 256MB/4GB；池 1 使用 / 2 待機；磁盘目录不可用 → 利用不可（不视为故障）
    expect(wrapper.find('.system-stat.is-heap').text()).toContain('256.0 MB')
    expect(wrapper.find('.system-stat.is-heap').text()).toContain('4.0 GB')
    expect(wrapper.find('.system-stat.is-pool').text()).toContain('1 使用 / 2 待機')
    expect(wrapper.find('.system-stat.is-disk').text()).toContain('利用不可')
    expect(wrapper.find('.system-stat.is-items').text()).toContain('1,200')
    expect(wrapper.find('.system-stat.is-codeSkipped').text()).toContain('12')
  })

  it('renders the fixed 7-slot client error bars and the recent batch table', async () => {
    const wrapper = await mountView()

    const bars = wrapper.findAll('.system-error-bar')
    expect(bars).toHaveLength(7)
    expect(bars[6]!.text()).toContain('09/30')
    expect(bars[6]!.text()).toContain('1')

    const batchRows = wrapper.findAll('.system-batch-table .el-table__row')
    expect(batchRows).toHaveLength(1)
    expect(batchRows[0]!.text()).toContain('在庫リスト9月.xlsx')
    expect(batchRows[0]!.text()).toContain('失敗')
    expect(batchRows[0]!.text()).toContain('2')
  })

  it('renders backup last-success time: placeholder when unknown, danger when stale over 25h', async () => {
    // ① null=不可知（未配置/无状态文件/坏 JSON）→ 占位 + 不标红（不当故障）
    let wrapper = await mountView()
    expect(wrapper.find('.system-stat.is-backup').text()).toContain('利用不可')
    expect(wrapper.find('.system-stat.is-backup dd').classes()).not.toContain('is-danger')

    // ② 新鲜（1 小时前）→ naive JST 墙钟展示 + 不标红
    apiMocks.fetchSystemStatus.mockResolvedValue({ ...STATUS, backup: freshBackup() })
    wrapper = await mountView()
    expect(wrapper.find('.system-stat.is-backup').text()).toContain('2026/09/30 03:17')
    expect(wrapper.find('.system-stat.is-backup dd').classes()).not.toContain('is-danger')

    // ③ 陈旧（26h > 25h 阈值，与 kcgl-doctor 检查 4 同口径）→ 红字（runbook D-3 首查项）
    apiMocks.fetchSystemStatus.mockResolvedValue({
      ...STATUS,
      backup: { ...freshBackup(), staleSeconds: 26 * 3600 },
    })
    wrapper = await mountView()
    expect(wrapper.find('.system-stat.is-backup dd').classes()).toContain('is-danger')
  })

  it('marks an open alert read and refreshes both alerts and status', async () => {
    apiMocks.fetchAlerts.mockResolvedValue({ list: [alert()], total: 1, page: 1, size: 50 })
    const wrapper = await mountView()

    const markButton = wrapper.findAll('button').find((b) => b.text() === '既読')!
    expect(markButton).toBeDefined()
    apiMocks.markAlertRead.mockResolvedValue(undefined)
    apiMocks.fetchAlerts.mockResolvedValue({ list: [alert({ status: 1 })], total: 1, page: 1, size: 50 })
    await markButton.trigger('click')
    await flushPromises()

    expect(apiMocks.markAlertRead).toHaveBeenCalledWith(9)
    // 联动刷新：告警列表 + 系统状况（开启计数）
    expect(apiMocks.fetchAlerts.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(apiMocks.fetchSystemStatus.mock.calls.length).toBeGreaterThanOrEqual(2)
    // 已读行不再出现既読按钮，显示既読済み
    expect(wrapper.findAll('button').some((b) => b.text() === '既読')).toBe(false)
    expect(wrapper.text()).toContain('既読済み')
  })

  it('runs the self-check and renders the five-section report with an all-ok summary', async () => {
    apiMocks.runSelfCheck.mockResolvedValue(REPORT)
    const wrapper = await mountView()

    const runButton = wrapper.findAll('button').find((b) => b.text() === '整合性チェックを実行')!
    await runButton.trigger('click')
    await flushPromises()

    expect(apiMocks.runSelfCheck).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.system-check-summary').text()).toBe('異常なし')
    const rows = wrapper.findAll('.system-check-row')
    expect(rows).toHaveLength(5)
    expect(wrapper.findAll('.system-check-badge.is-ok')).toHaveLength(5)
    expect(rows.some((r) => r.text().includes('台帳照合'))).toBe(true)
    expect(rows.some((r) => r.text().includes('画像ファイル'))).toBe(true)
  })

  it('downloads the diagnostics attachment via blob save', async () => {
    const createObjectUrl = vi
      .spyOn(URL, 'createObjectURL')
      .mockReturnValue('blob:diagnostics-mock')
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    apiMocks.downloadDiagnosticsExport.mockResolvedValue({
      blob: new Blob(['{}'], { type: 'application/json' }),
      filename: 'kcgl-diagnostics-20260930-090000.json',
    })
    const wrapper = await mountView()

    const exportButton = wrapper.findAll('button').find((b) => b.text() === '診断情報をエクスポート')!
    await exportButton.trigger('click')
    await flushPromises()

    expect(apiMocks.downloadDiagnosticsExport).toHaveBeenCalledTimes(1)
    expect(createObjectUrl).toHaveBeenCalledTimes(1)
    expect(revokeObjectUrl).toHaveBeenCalledTimes(1)
  })

  it('shows the load-failure state and recovers via retry', async () => {
    apiMocks.fetchSystemStatus.mockRejectedValue(new Error('network down'))
    const wrapper = await mountView()

    expect(wrapper.find('.system-error').text()).toBe('システム状況の読み込みに失敗しました')

    apiMocks.fetchSystemStatus.mockResolvedValue(STATUS)
    await wrapper.find('.system-card button').trigger('click')
    await flushPromises()
    expect(apiMocks.fetchSystemStatus).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.system-stat.is-version').text()).toContain('1.2.0')
  })
})
