import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchVenues: vi.fn(),
  createVenue: vi.fn(),
  renameVenue: vi.fn(),
  setVenueStatus: vi.fn(),
}))

// ApiError 保持真实实现（错误分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchVenues: apiMocks.fetchVenues,
    createVenue: apiMocks.createVenue,
    renameVenue: apiMocks.renameVenue,
    setVenueStatus: apiMocks.setVenueStatus,
  }
})

import VenueAdminView from './VenueAdminView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { MeResponse, Venue } from '@/utils/api'

/**
 * 会场管理页（M2-8b-2）：列表渲染/创建/改名（code 锁定不可改）/
 * 停用启用（仅管理员）/服务端 409 就地展示/编辑者无停用按钮。
 */

const meAdmin: MeResponse = {
  username: 'boss',
  displayName: '管理者',
  role: 1,
  locale: 'ja-JP',
  mustChangePwd: false,
}

const meEditor: MeResponse = {
  username: 'eichi',
  displayName: '編集者',
  role: 2,
  locale: 'ja-JP',
  mustChangePwd: false,
}

function venue(id: number, code: string, name: string, enabled: boolean): Venue {
  return { id, code, name, enabled }
}

const SEED: Venue[] = [
  venue(11, 'HT', '飛騨古民具市', true),
  venue(12, 'NG', '名古屋骨董市', false),
]

function rows(wrapper: VueWrapper) {
  return wrapper.findAll('.el-table__row')
}

async function mountView(role: 1 | 2 = 1): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : meEditor
  const wrapper = mount(VenueAdminView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

/** 打开弹层并填表（fields 按 DOM 顺序：code, name）。 */
async function fillDialog(
  wrapper: VueWrapper,
  code: string,
  name: string,
): Promise<void> {
  await wrapper.find('.admin-header button').trigger('click')
  await flushPromises()
  const inputs = wrapper.findAll('.el-dialog input')
  await inputs[0]!.setValue(code)
  await inputs[1]!.setValue(name)
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

describe('venue admin (M2-8b-2)', () => {
  it('renders venues with code, name, and status tags', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    expect(rows(wrapper)).toHaveLength(2)
    expect(wrapper.text()).toContain('HT')
    expect(wrapper.text()).toContain('飛騨古民具市')
    expect(wrapper.text()).toContain('名古屋骨董市')
    const tags = wrapper.findAll('.admin-tag')
    expect(tags[0]!.text()).toBe('有効')
    expect(tags[1]!.text()).toBe('停止')
  })

  it('creates a venue after inline validation rejects an invalid code', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    // 非法 code（小写/位数不足）→ 就地报错不发请求
    await fillDialog(wrapper, 'h', '新しい会場')
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.admin-form-error').text()).toContain('会場コードは半角英字2桁')
    expect(apiMocks.createVenue).not.toHaveBeenCalled()

    // 改为合法值 → 提交成功并重载
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0]!.setValue('AB')
    apiMocks.createVenue.mockResolvedValue(venue(13, 'AB', '新しい会場', true))
    apiMocks.fetchVenues.mockResolvedValue([...SEED, venue(13, 'AB', '新しい会場', true)])
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()

    expect(apiMocks.createVenue).toHaveBeenCalledWith({ code: 'AB', name: '新しい会場' })
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(2)
    expect(rows(wrapper)).toHaveLength(3)
  })

  it('renames a venue with the code field locked', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await rows(wrapper)[0]!.findAll('button').filter((b) => b.text() === '名前を変更')[0]!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('.el-dialog input')
    expect(inputs[0]!.attributes('disabled')).toBeDefined()
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('飛騨古民具市')

    await inputs[1]!.setValue('飛騨古民具市（春）')
    apiMocks.renameVenue.mockResolvedValue(venue(11, 'HT', '飛騨古民具市（春）', true))
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()

    expect(apiMocks.renameVenue).toHaveBeenCalledWith(11, '飛騨古民具市（春）')
  })

  it('admin can disable and re-enable a venue (reversible, no confirm)', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    const disableButton = rows(wrapper)[0]!.findAll('button').filter((b) => b.text() === '停止する')[0]!
    apiMocks.setVenueStatus.mockResolvedValue(venue(11, 'HT', '飛騨古民具市', false))
    await disableButton.trigger('click')
    await flushPromises()

    expect(apiMocks.setVenueStatus).toHaveBeenCalledWith(11, false)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(2)
  })

  it('editor has no disable/enable controls (create and rename only)', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView(2)

    const buttonsText = rows(wrapper)[0]!.findAll('button').map((b) => b.text())
    expect(buttonsText).toContain('名前を変更')
    expect(buttonsText).not.toContain('停止する')
    expect(buttonsText).not.toContain('有効にする')
    expect(apiMocks.setVenueStatus).not.toHaveBeenCalled()
  })

  it('shows the server duplicate-code error inside the dialog', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await fillDialog(wrapper, 'HT', '重複会場')
    apiMocks.createVenue.mockRejectedValue(new ApiError(409002, 'この会場コードは既に登録されています'))
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.admin-form-error').text()).toContain('この会場コードは既に登録されています')
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
  })

  it('shows a retry hint when the list fails to load', async () => {
    apiMocks.fetchVenues.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR'))
    const wrapper = await mountView()

    expect(wrapper.find('.admin-error').text()).toContain('読み込みに失敗しました')

    apiMocks.fetchVenues.mockResolvedValue(SEED)
    await wrapper.find('.admin-error button').trigger('click')
    await flushPromises()
    expect(rows(wrapper)).toHaveLength(2)
  })
})
