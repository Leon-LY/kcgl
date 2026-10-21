import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchVenues: vi.fn(),
  createVenue: vi.fn(),
  updateVenue: vi.fn(),
  deleteVenue: vi.fn(),
  setVenueStatus: vi.fn(),
}))

// ApiError 保持真实实现（错误分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchVenues: apiMocks.fetchVenues,
    createVenue: apiMocks.createVenue,
    updateVenue: apiMocks.updateVenue,
    deleteVenue: apiMocks.deleteVenue,
    setVenueStatus: apiMocks.setVenueStatus,
  }
})

import VenueAdminView from './VenueAdminView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { MeResponse, Venue } from '@/utils/api'

/**
 * 会场管理页（M2-8b-2）：列表渲染/创建/改名改码（D-165/D-166）/
 * 删除（输码双重确认、有引用被拒）/停用启用（仅管理员）/服务端 409 就地展示。
 */

const meAdmin: MeResponse = {
  id: 1,
  username: 'boss',
  displayName: '管理者',
  role: 1,
  locale: 'ja-JP',
  mustChangePwd: false,
}

const meEditor: MeResponse = {
  id: 2,
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
  await wrapper.find('.page-header-actions button').trigger('click')
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

    // 非法 code（含符号/超 8 位）→ 就地报错不发请求
    await fillDialog(wrapper, 'AB-', '新しい会場')
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.admin-form-error').text()).toContain('会場コードは半角英数字1〜8桁')
    expect(apiMocks.createVenue).not.toHaveBeenCalled()

    // 小写输入合法（提交前统一转大写）→ 提交成功并重载
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0]!.setValue('tk01')
    apiMocks.createVenue.mockResolvedValue(venue(13, 'TK01', '新しい会場', true))
    apiMocks.fetchVenues.mockResolvedValue([...SEED, venue(13, 'TK01', '新しい会場', true)])
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()

    expect(apiMocks.createVenue).toHaveBeenCalledWith({ code: 'TK01', name: '新しい会場' })
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(2)
    expect(rows(wrapper)).toHaveLength(3)
  })

  it('edits name and code together', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await rows(wrapper)[0]!.findAll('button').filter((b) => b.text() === '編集')[0]!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('.el-dialog input')
    expect(inputs[0]!.attributes('disabled')).toBeUndefined()
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('HT')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('飛騨古民具市')

    await inputs[0]!.setValue('ht2')
    await inputs[1]!.setValue('飛騨古民具市（春）')
    apiMocks.updateVenue.mockResolvedValue(venue(11, 'HT2', '飛騨古民具市（春）', true))
    await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
    await flushPromises()

    expect(apiMocks.updateVenue).toHaveBeenCalledWith(11, { name: '飛騨古民具市（春）', code: 'HT2' })
  })

  it('deletes a venue only after the code is typed in', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await rows(wrapper)[0]!.findAll('button').filter((b) => b.text() === '削除')[0]!.trigger('click')
    await flushPromises()

    const dialog = wrapper.findAll('.el-dialog').at(-1)!
    const confirm = dialog.findAll('button').filter((b) => b.text() === '削除する')[0]!
    expect(confirm.attributes('disabled')).toBeDefined()

    // 输错码 → 确认键仍不可用
    await dialog.find('input').setValue('NG')
    await flushPromises()
    expect(confirm.attributes('disabled')).toBeDefined()

    // 输对码（大小写不敏感）→ 可确认
    await dialog.find('input').setValue('ht')
    apiMocks.deleteVenue.mockResolvedValue(undefined)
    apiMocks.fetchVenues.mockResolvedValue([SEED[1]!])
    await flushPromises()
    await confirm.trigger('click')
    await flushPromises()

    expect(apiMocks.deleteVenue).toHaveBeenCalledWith(11)
    expect(rows(wrapper)).toHaveLength(1)
  })

  it('keeps the venue and shows the in-use error when delete is rejected', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await rows(wrapper)[0]!.findAll('button').filter((b) => b.text() === '削除')[0]!.trigger('click')
    await flushPromises()

    const dialog = wrapper.findAll('.el-dialog').at(-1)!
    await dialog.find('input').setValue('HT')
    apiMocks.deleteVenue.mockRejectedValue(
      new ApiError(409017, 'この会場は商品で使われているため削除できません。'),
    )
    await flushPromises()
    await dialog.findAll('button').filter((b) => b.text() === '削除する')[0]!.trigger('click')
    await flushPromises()

    expect(dialog.find('.admin-form-error').text()).toContain('「停止」をご利用ください')
    expect(rows(wrapper)).toHaveLength(2)
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

  it('editor has no disable/enable/delete controls (create and edit only)', async () => {
    apiMocks.fetchVenues.mockResolvedValue(SEED)
    const wrapper = await mountView(2)

    const buttonsText = rows(wrapper)[0]!.findAll('button').map((b) => b.text())
    expect(buttonsText).toContain('編集')
    expect(buttonsText).not.toContain('停止する')
    expect(buttonsText).not.toContain('有効にする')
    expect(buttonsText).not.toContain('削除')
    expect(apiMocks.setVenueStatus).not.toHaveBeenCalled()
    expect(apiMocks.deleteVenue).not.toHaveBeenCalled()
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
