import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchOperationLogs: vi.fn(),
}))

// ApiError/errors 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchOperationLogs: apiMocks.fetchOperationLogs,
  }
})

import OperationLogsView from './OperationLogsView.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { OperationLogResult, OperationLogRow } from '@/utils/api'

/**
 * 操作ログ（M5-④）：管理员操作留痕查询——动作/对象/操作人筛选 → 参数组装 →
 * 行渲染（action/entityId#id/ip）→ 加载失败重取。展开行的 JSON 美化在
 * prettyDetail（原样留痕，parse 失败回退原文）。
 */

function row(overrides: Partial<OperationLogRow> = {}): OperationLogRow {
  return {
    id: 3,
    action: 'ITEM_TRANSFER',
    entityType: 'item',
    entityId: 42,
    detail: '{"whFrom":1,"whTo":2}',
    operatorName: '編集者',
    ip: '192.168.1.20',
    ua: 'test-ua',
    createdAt: '2026-09-28 10:02:00',
    ...overrides,
  }
}

function result(overrides: Partial<OperationLogResult> = {}): OperationLogResult {
  return { total: 0, page: 1, size: 20, rows: [], ...overrides }
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(OperationLogsView, {
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
  apiMocks.fetchOperationLogs.mockResolvedValue(result())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('operation logs view (M5-4)', () => {
  it('loads unfiltered and renders rows with action, entity id, and operator', async () => {
    apiMocks.fetchOperationLogs.mockResolvedValue(
      result({
        total: 2,
        rows: [
          row({ id: 3, action: 'SETTING_UPDATE', entityType: 'sys_setting', entityId: null }),
          row({ id: 2, action: 'ITEM_VOID', entityId: 7, operatorName: '管理者' }),
        ],
      }),
    )
    const wrapper = await mountView()

    expect(apiMocks.fetchOperationLogs).toHaveBeenCalledWith({
      action: undefined,
      entityType: undefined,
      operatorName: undefined,
      dateFrom: undefined,
      dateTo: undefined,
      page: 1,
      size: 20,
    })
    const rows = wrapper.findAll('.el-table__row')
    expect(rows).toHaveLength(2)
    expect(wrapper.find('.oplogs-count').text()).toBe('2 件')
    // entityId null → 不带 #后缀；有值 → #42 拼接
    expect(rows[0]!.text()).toContain('SETTING_UPDATE')
    expect(rows[0]!.text()).toContain('sys_setting')
    expect(rows[0]!.text()).not.toContain('#')
    expect(rows[1]!.text()).toContain('#7')
    expect(rows[1]!.text()).toContain('管理者')
    expect(rows[1]!.text()).toContain('192.168.1.20')
  })

  it('narrows by action and operator inputs on enter', async () => {
    const wrapper = await mountView()

    const inputs = wrapper.findAll('input')
    // 三个文本输入：action / entityType / operatorName
    await inputs[0]!.setValue('ITEM_')
    await inputs[0]!.trigger('keyup.enter')
    await flushPromises()
    expect(apiMocks.fetchOperationLogs).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: 'ITEM_', page: 1 }),
    )

    await inputs[2]!.setValue('管理者')
    await inputs[2]!.trigger('keyup.enter')
    await flushPromises()
    expect(apiMocks.fetchOperationLogs).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: 'ITEM_', operatorName: '管理者' }),
    )
  })

  it('pretty-prints the detail JSON in the expanded row and falls back to raw text', async () => {
    apiMocks.fetchOperationLogs.mockResolvedValue(
      result({
        rows: [
          row(),
          row({ id: 2, detail: 'not-json' }),
        ],
      }),
    )
    const wrapper = await mountView()

    const expandIcons = wrapper.findAll('.el-table__expand-icon')
    expect(expandIcons.length).toBeGreaterThan(0)
    await expandIcons[0]!.trigger('click')
    await expandIcons[1]!.trigger('click')
    await flushPromises()

    const details = wrapper.findAll('.oplogs-detail')
    // 合法 JSON → 缩进美化；非法 → 原样
    expect(details[0]!.text()).toContain('"whFrom": 1')
    expect(details[1]!.text()).toBe('not-json')
  })

  it('shows the error state and recovers via retry', async () => {
    apiMocks.fetchOperationLogs.mockRejectedValue(new ApiError(0, 'network down'))
    const wrapper = await mountView()

    expect(wrapper.find('.kcgl-error-box').text()).toContain('network down')

    apiMocks.fetchOperationLogs.mockResolvedValue(result({ total: 1, rows: [row()] }))
    await wrapper.find('.kcgl-error-box button').trigger('click')
    await flushPromises()
    expect(apiMocks.fetchOperationLogs).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
  })
})
