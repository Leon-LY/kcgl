import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  fetchPendingShipments: vi.fn(),
}))

// ApiError 保持真实实现（错误文案分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchPendingShipments: apiMocks.fetchPendingShipments,
  }
})

import PendingShipmentsView from './PendingShipmentsView.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { YahooPendingShipment, YahooPendingShipmentList } from '@/utils/api'

/**
 * 出荷待ち（M4）：拣货队列渲染（货架/仓库/落札价/落札日时/滞留红标）/
 * 「この商品を売り上げる」深链扫码页 ?code= /加载失败重试/空态。
 */

function shipment(overrides: Partial<YahooPendingShipment> = {}): YahooPendingShipment {
  return {
    itemId: 601,
    itemCode: 'HT9-A1X',
    thumbUrl: '/img/thumb/2026/09/a.jpg',
    warehouse: 1,
    shelfNo: 'A-03',
    soldPrice: 12000,
    auctionId: 'auc-101',
    closedAt: '2026-09-20 21:05:33',
    delayed: true,
    ...overrides,
  }
}

function page(items: YahooPendingShipment[]): YahooPendingShipmentList {
  return { count: items.length, items }
}

function cards(wrapper: VueWrapper) {
  return wrapper.findAll('.shipment-card')
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'pending-shipments', component: { template: '<div />' } },
      { path: '/scan', name: 'scan', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'pending-shipments' })
  const wrapper = mount(PendingShipmentsView, {
    global: { plugins: [i18n, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('pending shipments view', () => {
  it('renders the picking queue with count, price, shelf, and delayed badge', async () => {
    apiMocks.fetchPendingShipments.mockResolvedValue(
      page([
        shipment(),
        shipment({
          itemId: 602,
          itemCode: 'HT9-A2X',
          thumbUrl: null,
          warehouse: 2,
          shelfNo: null,
          soldPrice: null,
          delayed: false,
          closedAt: '2026-09-27 22:00:00',
        }),
      ]),
    )
    const { wrapper } = await mountView()

    expect(wrapper.find('.shipment-count').text()).toBe('出荷待ち 2 件')
    expect(cards(wrapper)).toHaveLength(2)

    const first = cards(wrapper)[0]
    expect(first.find('.shipment-code').text()).toBe('HT9-A1X')
    expect(first.find('.shipment-thumb img').attributes('src')).toBe('/img/thumb/2026/09/a.jpg')
    const metas = first.findAll('.shipment-meta')
    expect(metas[0]!.text()).toBe('名古屋倉庫')
    expect(metas[1]!.text()).toBe('棚番号 A-03')
    expect(first.find('.shipment-price').text()).toBe('￥12,000')
    expect(first.find('.shipment-date').text()).toContain('2026/09/20 21:05')
    expect(first.find('.shipment-delayed').text()).toBe('出荷遅延')

    // 无缩略图/无货架/未滞留的行：占位与空缺不渲染红标
    const second = cards(wrapper)[1]
    expect(second.find('.shipment-thumb img').exists()).toBe(false)
    const secondMetas = second.findAll('.shipment-meta')
    expect(secondMetas).toHaveLength(1)
    expect(secondMetas[0]!.text()).toBe('福岡倉庫')
    expect(second.find('.shipment-price').text()).toBe('—')
    expect(second.find('.shipment-delayed').exists()).toBe(false)
  })

  it('goSell deep-links the scan page with the item code', async () => {
    apiMocks.fetchPendingShipments.mockResolvedValue(page([shipment()]))
    const { wrapper, router } = await mountView()

    await cards(wrapper)[0].find('.shipment-sell').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('scan')
    expect(router.currentRoute.value.query.code).toBe('HT9-A1X')
  })

  it('shows the empty state when nothing is pending', async () => {
    apiMocks.fetchPendingShipments.mockResolvedValue(page([]))
    const { wrapper } = await mountView()

    // 空态现在带一句「下一步」引导（共用基元 AppEmptyState），标题仍是原句：
    // 用 toContain 锚住标题文案，不再断言整段文本只有一个句子
    expect(wrapper.find('.shipment-empty').text()).toContain('出荷待ちの商品はありません。')
    expect(cards(wrapper)).toHaveLength(0)
  })

  it('shows a load error and retries on demand', async () => {
    apiMocks.fetchPendingShipments
      .mockRejectedValueOnce(new ApiError(0, 'network down'))
      .mockResolvedValueOnce(page([shipment()]))
    const { wrapper } = await mountView()

    expect(wrapper.find('.kcgl-error-box').text()).toContain('network down')

    await wrapper.find('.shipment-retry').trigger('click')
    await flushPromises()

    expect(apiMocks.fetchPendingShipments).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.kcgl-error-box').exists()).toBe(false)
    expect(cards(wrapper)).toHaveLength(1)
  })
})
