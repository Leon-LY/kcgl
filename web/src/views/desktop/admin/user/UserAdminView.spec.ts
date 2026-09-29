import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchUsers: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  setUserStatus: vi.fn(),
  unlockUser: vi.fn(),
  resetUserPassword: vi.fn(),
}))

// ApiError 保持真实实现（错误分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchUsers: apiMocks.fetchUsers,
    createUser: apiMocks.createUser,
    updateUser: apiMocks.updateUser,
    setUserStatus: apiMocks.setUserStatus,
    unlockUser: apiMocks.unlockUser,
    resetUserPassword: apiMocks.resetUserPassword,
  }
})

import UserAdminView from './UserAdminView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { AdminUser, MeResponse } from '@/utils/api'

/**
 * 账号管理页（M2-8b-3）：列表渲染（角色文案/状态/锁定标记）/创建（username+密码校验）/
 * 编辑（username 锁定）/自身行无停用按钮/解锁/密码重置一次性展示/服务端 409 就地展示。
 */

const meAdmin: MeResponse = {
  id: 1,
  username: 'boss',
  displayName: '管理者',
  role: 1,
  locale: 'ja-JP',
  mustChangePwd: false,
}

function user(
  id: number,
  username: string,
  displayName: string,
  role: number,
  enabled: boolean,
  locked = false,
): AdminUser {
  return {
    id,
    username,
    displayName,
    role,
    locale: 'ja-JP',
    enabled,
    mustChangePwd: false,
    locked,
    lockedUntil: locked ? '2099-01-01T00:00:00' : null,
    failedAttempts: 0,
    lastLoginAt: username === 'boss' ? '2026-09-27T10:00:00' : null,
  }
}

const SEED: AdminUser[] = [
  user(1, 'boss', '管理者', 1, true),
  user(5, 'taro', '太郎', 3, true),
  user(6, 'hanako', '花子', 2, false, true),
]

function rows(wrapper: VueWrapper) {
  return wrapper.findAll('.el-table__row')
}

async function mountView(): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = meAdmin
  const wrapper = mount(UserAdminView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

/** 打开新增弹层并填 username/displayName/密码（角色与语言用默认值）。 */
async function fillCreateDialog(
  wrapper: VueWrapper,
  username: string,
  displayName: string,
  password: string,
): Promise<void> {
  await wrapper.find('.admin-header button').trigger('click')
  await flushPromises()
  const inputs = wrapper.findAll('.el-dialog input')
  await inputs[0]!.setValue(username)
  await inputs[1]!.setValue(displayName)
  await inputs[inputs.length - 1]!.setValue(password)
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
  await flushPromises()
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

describe('user admin (M2-8b-3)', () => {
  it('renders users with role labels, status tags, and the locked marker', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    expect(rows(wrapper)).toHaveLength(3)
    const selfRow = rows(wrapper)[0]!
    expect(selfRow.text()).toContain('boss')
    expect(selfRow.text()).toContain('管理者')
    expect(selfRow.text()).toContain('2026-09-27 10:00')
    expect(rows(wrapper)[1]!.text()).toContain('閲覧者')
    const lockedRow = rows(wrapper)[2]!
    expect(lockedRow.text()).toContain('停止')
    expect(lockedRow.text()).toContain('ロック中')
    expect(lockedRow.text()).toContain('—')
  })

  it('creates a user after inline validation rejects a short password', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    // 密码过短 → 就地报错不发请求
    await fillCreateDialog(wrapper, 'jiro', '次郎', 'short')
    await submit(wrapper)
    expect(wrapper.find('.admin-form-error').text()).toContain('10文字以上')
    expect(apiMocks.createUser).not.toHaveBeenCalled()

    // 合法值 → 以默认角色（編集者）与默认语言（ja-JP）提交
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[inputs.length - 1]!.setValue('Valid-12345678')
    apiMocks.createUser.mockResolvedValue(user(7, 'jiro', '次郎', 2, true))
    apiMocks.fetchUsers.mockResolvedValue({
      list: [...SEED, user(7, 'jiro', '次郎', 2, true)],
      total: 4,
      page: 1,
      size: 50,
    })
    await submit(wrapper)

    expect(apiMocks.createUser).toHaveBeenCalledWith({
      username: 'jiro',
      displayName: '次郎',
      role: 2,
      locale: 'ja-JP',
      password: 'Valid-12345678',
    })
    expect(apiMocks.fetchUsers).toHaveBeenCalledTimes(2)
    expect(rows(wrapper)).toHaveLength(4)
  })

  it('edits a user with the username field locked', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    // 閲覧者行（taro, role=3）打开编辑
    await rows(wrapper)[1]!.findAll('button').filter((b) => b.text() === '編集')[0]!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('.el-dialog input')
    expect(inputs[0]!.attributes('disabled')).toBeDefined()
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('taro')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('太郎')

    // 角色单选预填：role=3 已选中
    const roleValues = inputs
      .slice(2, 5)
      .map((i) => (i.element as HTMLInputElement).value)
    expect(roleValues).toEqual(['1', '2', '3'])
    const checkedRole = inputs
      .slice(2, 5)
      .find((i) => (i.element as HTMLInputElement).checked)
    expect((checkedRole!.element as HTMLInputElement).value).toBe('3')

    await inputs[1]!.setValue('太郎（改）')
    apiMocks.updateUser.mockResolvedValue(user(5, 'taro', '太郎（改）', 3, true))
    await submit(wrapper)

    expect(apiMocks.updateUser).toHaveBeenCalledWith(5, {
      displayName: '太郎（改）',
      role: 3,
      locale: 'ja-JP',
    })
  })

  it('hides the disable button on the own row (self-disable guard)', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    const selfButtons = rows(wrapper)[0]!.findAll('button').map((b) => b.text())
    expect(selfButtons).not.toContain('停止する')
    expect(selfButtons).toContain('パスワード再発行')

    const otherButtons = rows(wrapper)[1]!.findAll('button').map((b) => b.text())
    expect(otherButtons).toContain('停止する')
  })

  it('unlocks a locked user and reloads', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    const lockedRow = rows(wrapper)[2]!
    apiMocks.unlockUser.mockResolvedValue(user(6, 'hanako', '花子', 2, false))
    await lockedRow.findAll('button').filter((b) => b.text() === 'ロック解除')[0]!.trigger('click')
    await flushPromises()

    expect(apiMocks.unlockUser).toHaveBeenCalledWith(6)
    expect(apiMocks.fetchUsers).toHaveBeenCalledTimes(2)
  })

  it('shows the one-time initial password after a reset', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    apiMocks.resetUserPassword.mockResolvedValue({ initialPassword: 'Xy-Init-123456' })
    await rows(wrapper)[1]!.findAll('button')
      .filter((b) => b.text() === 'パスワード再発行')[0]!
      .trigger('click')
    await flushPromises()

    expect(apiMocks.resetUserPassword).toHaveBeenCalledWith(5)
    expect(wrapper.find('.user-reset-password').text()).toBe('Xy-Init-123456')
    expect(wrapper.text()).toContain('一度だけ表示')
  })

  it('shows the server duplicate-username error inside the dialog', async () => {
    apiMocks.fetchUsers.mockResolvedValue({ list: SEED, total: 3, page: 1, size: 50 })
    const wrapper = await mountView()

    await fillCreateDialog(wrapper, 'taro', '重複太郎', 'Valid-12345678')
    apiMocks.createUser.mockRejectedValue(new ApiError(409001, '同じユーザー名が既に存在します'))
    await submit(wrapper)

    expect(wrapper.find('.admin-form-error').text()).toContain('既に存在します')
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
  })
})
