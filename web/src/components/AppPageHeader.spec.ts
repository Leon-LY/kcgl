import { describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'

import AppPageHeader from './AppPageHeader.vue'

/**
 * 页头基元（D-121）：桌面 10 个视图共用。此 spec 只钉**接口契约**——标题/说明各有
 * 「属性、插槽」两条通路且插槽优先、返回行只认回调、操作区插槽落位。字号、间距、
 * 返回行文案等外观层由各视图的既有断言与真实浏览器核验覆盖，不在此重复。
 *
 * 曾有一个 `backTo` 具名路由返回属性，全仓 0 引用且与 D-112/C1（返回须带 query）
 * 相悖，已删；本 spec 的第 3 例即用来钉住「返回只走回调」这条约束。
 */

enableAutoUnmount(() => {})

describe('AppPageHeader', () => {
  it('renders the title and description given as props', () => {
    const wrapper = mount(AppPageHeader, {
      props: { title: '商品一覧', description: '在庫と出品の状態を確認します' },
    })

    expect(wrapper.find('header.page-header').exists()).toBe(true)
    expect(wrapper.find('.page-header-title').text()).toBe('商品一覧')
    expect(wrapper.find('.page-header-desc').text()).toBe('在庫と出品の状態を確認します')
  })

  it('lets the title and description slots replace the prop text', () => {
    const wrapper = mount(AppPageHeader, {
      props: { title: 'prop の見出し', description: 'prop の説明' },
      slots: {
        title: '<span class="probe-title">AA12-AAA9Z</span>',
        description: '<p class="probe-desc">発行時と差異があります</p>',
      },
    })

    expect(wrapper.find('.page-header-title .probe-title').exists()).toBe(true)
    // 插槽接管后属性文本不再出现（详情页靠这条走「管理号 + 状态片」）
    expect(wrapper.find('.page-header-title').text()).not.toContain('prop の見出し')
    expect(wrapper.find('.page-header-text .probe-desc').exists()).toBe(true)
    // 走 description 插槽时不再渲染默认的说明元素（警示色自持）
    expect(wrapper.find('.page-header-desc').exists()).toBe(false)
  })

  it('renders no back row without a callback, then fires the callback on click', async () => {
    const onBack = vi.fn()
    const wrapper = mount(AppPageHeader, {
      props: { title: '商品詳細', backLabel: '一覧に戻る' },
    })

    // 只给文案不给动作不渲染返回行：返回行只认回调，无回调即无可返回之处
    expect(wrapper.find('.page-header-back').exists()).toBe(false)

    await wrapper.setProps({ onBack })
    const back = wrapper.find('.page-header-back')
    expect(back.exists()).toBe(true)
    expect(back.text()).toBe('← 一覧に戻る')

    await back.trigger('click')
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('renders the actions slot into the actions container', () => {
    const withActions = mount(AppPageHeader, {
      props: { title: '会場' },
      slots: { actions: '<button class="probe-act">新規作成</button>' },
    })
    expect(withActions.find('.page-header-actions .probe-act').exists()).toBe(true)

    // 不给插槽时容器不留元素（:empty 规则据此收起占位）
    const withoutActions = mount(AppPageHeader, { props: { title: '会場' } })
    expect(withoutActions.find('.page-header-actions').element.children.length).toBe(0)
  })
})
