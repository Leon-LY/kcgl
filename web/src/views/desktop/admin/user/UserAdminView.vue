<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { toDisplayMessage } from '@/utils/errors'
import { useAuthStore } from '@/stores/auth'
import {
  createUser,
  fetchUsers,
  resetUserPassword,
  setUserStatus,
  unlockUser,
  updateUser,
} from '@/utils/api'
import type { AdminUser } from '@/utils/api'

/**
 * 账号管理页（/admin/users，M2-8b-3，仅管理员）：列表/创建/编辑（username 锁定）/
 * 停用启用/手动解锁/密码重置（一次性初始密码弹层展示）。
 * 自停用后端已护栏（唯一管理员锁死无人恢复），前端对自身行直接隐藏停用按钮。
 */

const { t } = useI18n()
const auth = useAuthStore()

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,32}$/
const PASSWORD_MIN = 10
/** 语言名固定用各自母语显示（与 LangSwitch 同原则），不随界面语言翻译。 */
const LOCALE_OPTIONS = [
  { value: 'ja-JP', label: '日本語' },
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: 'English' },
]
const ROLE_OPTIONS = [1, 2, 3]

// ------------------------------------------------------------- 列表

const users = ref<AdminUser[]>([])
const loading = ref(true)
const loadError = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    users.value = (await fetchUsers()).list
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

function roleLabel(role: number): string {
  // element-plus 列注册时会用空哑行 { row: {} } 探测一次 default 插槽
  // （TableColumnRenderer），此刻 role=undefined——返回占位避免每次进页
  // 都刷一条 auth.role.undefined 的 intlify 缺键警告（渲染结果本就被丢弃）
  return typeof role === 'number' ? t(`auth.role.${role}`) : '—'
}

function isSelf(row: AdminUser): boolean {
  return auth.me !== null && row.username === auth.me.username
}

function lastLoginLabel(row: AdminUser): string {
  if (row.lastLoginAt === null) {
    return '—'
  }
  return row.lastLoginAt.replace('T', ' ').slice(0, 16)
}

// ------------------------------------------------------------- 创建/编辑弹层

const dialogOpen = ref(false)
const editing = ref<AdminUser | null>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const formUsername = ref('')
const formDisplayName = ref('')
const formRole = ref(2)
const formLocale = ref('ja-JP')
const formPassword = ref('')

function openCreate(): void {
  editing.value = null
  formUsername.value = ''
  formDisplayName.value = ''
  formRole.value = 2
  formLocale.value = 'ja-JP'
  formPassword.value = ''
  dialogError.value = ''
  dialogOpen.value = true
}

function openEdit(row: AdminUser): void {
  editing.value = row
  formUsername.value = row.username
  formDisplayName.value = row.displayName
  formRole.value = row.role
  formLocale.value = row.locale
  formPassword.value = ''
  dialogError.value = ''
  dialogOpen.value = true
}

const dialogTitle = computed(() =>
  editing.value === null ? t('admin.user.createTitle') : t('admin.user.editTitle'),
)

async function onSubmit(): Promise<void> {
  if (dialogBusy.value) return
  if (editing.value === null && !USERNAME_PATTERN.test(formUsername.value)) {
    dialogError.value = t('admin.user.usernameInvalid')
    return
  }
  if (formDisplayName.value.trim() === '') {
    dialogError.value = t('admin.user.displayNameRequired')
    return
  }
  if (editing.value === null && formPassword.value.length < PASSWORD_MIN) {
    dialogError.value = t('admin.user.passwordInvalid')
    return
  }
  dialogError.value = ''
  dialogBusy.value = true
  try {
    if (editing.value === null) {
      await createUser({
        username: formUsername.value,
        displayName: formDisplayName.value.trim(),
        role: formRole.value,
        locale: formLocale.value,
        password: formPassword.value,
      })
    } else {
      await updateUser(editing.value.id, {
        displayName: formDisplayName.value.trim(),
        role: formRole.value,
        locale: formLocale.value,
      })
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    dialogError.value = toDisplayMessage(error, t)
  } finally {
    dialogBusy.value = false
  }
}

// ------------------------------------------------------------- 行内操作

async function onToggle(row: AdminUser): Promise<void> {
  await setUserStatus(row.id, !row.enabled).catch(() => undefined)
  await load()
}

async function onUnlock(row: AdminUser): Promise<void> {
  await unlockUser(row.id).catch(() => undefined)
  await load()
}

// ------------------------------------------------------------- 密码重置弹层

const resetOpen = ref(false)
const resetBusy = ref(false)
const resetPassword = ref('')

async function onResetPassword(row: AdminUser): Promise<void> {
  if (resetBusy.value) return
  resetBusy.value = true
  try {
    const result = await resetUserPassword(row.id)
    resetPassword.value = result.initialPassword
    resetOpen.value = true
  } catch {
    resetPassword.value = ''
  } finally {
    resetBusy.value = false
  }
}
</script>

<template>
  <section class="user-admin">
    <AppPageHeader
      :title="t('admin.user.title')"
      :description="t('admin.user.note')"
    >
      <template #actions>
        <el-button
          type="primary"
          @click="openCreate"
        >
          {{ t('admin.user.createButton') }}
        </el-button>
      </template>
    </AppPageHeader>

    <div class="kcgl-card admin-body">
      <p
        v-if="loadError"
        class="admin-error"
      >
        {{ t('admin.loadFailed') }}
        <el-button
          link
          type="primary"
          @click="load"
        >
          {{ t('common.reload') }}
        </el-button>
      </p>
      <el-table
        v-else
        v-loading="loading"
        :data="users"
        class="admin-table"
      >
        <el-table-column
          prop="username"
          :label="t('admin.user.username')"
          width="140"
        />
        <el-table-column
          prop="displayName"
          :label="t('admin.user.displayName')"
          min-width="120"
        />
        <el-table-column
          :label="t('admin.user.role')"
          width="110"
        >
          <template #default="{ row }">
            {{ roleLabel((row as AdminUser).role) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('admin.user.status')"
          width="120"
        >
          <template #default="{ row }">
            <span
              class="admin-tag"
              :class="(row as AdminUser).enabled ? 'is-enabled' : 'is-disabled'"
            >{{ (row as AdminUser).enabled ? t('admin.enabled') : t('admin.disabled') }}</span>
            <span
              v-if="(row as AdminUser).locked"
              class="admin-tag is-locked"
            >{{ t('admin.user.lockedTag') }}</span>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('admin.user.lastLogin')"
          width="150"
        >
          <template #default="{ row }">
            {{ lastLoginLabel(row as AdminUser) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('admin.actions')"
          min-width="280"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openEdit(row as AdminUser)"
            >
              {{ t('admin.edit') }}
            </el-button>
            <el-button
              v-if="!isSelf(row as AdminUser)"
              link
              :type="(row as AdminUser).enabled ? 'danger' : 'primary'"
              @click="onToggle(row as AdminUser)"
            >
              {{ (row as AdminUser).enabled ? t('admin.disable') : t('admin.enable') }}
            </el-button>
            <el-button
              v-if="(row as AdminUser).locked"
              link
              type="primary"
              @click="onUnlock(row as AdminUser)"
            >
              {{ t('admin.user.unlock') }}
            </el-button>
            <el-button
              link
              type="primary"
              @click="onResetPassword(row as AdminUser)"
            >
              {{ t('admin.user.resetPwd') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          {{ t('admin.user.empty') }}
        </template>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="440px"
      :close-on-click-modal="!dialogBusy"
    >
      <div class="admin-form">
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.user.username') }}</span>
          <el-input
            v-model="formUsername"
            :disabled="dialogBusy || editing !== null"
            :placeholder="t('admin.user.usernamePlaceholder')"
          />
        </label>
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.user.displayName') }}</span>
          <el-input
            v-model="formDisplayName"
            :disabled="dialogBusy"
            maxlength="64"
          />
        </label>
        <div class="admin-field">
          <span class="admin-field-label">{{ t('admin.user.role') }}</span>
          <el-radio-group
            v-model="formRole"
            :disabled="dialogBusy"
          >
            <el-radio-button
              v-for="role in ROLE_OPTIONS"
              :key="role"
              :value="role"
            >
              {{ roleLabel(role) }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <div class="admin-field">
          <span class="admin-field-label">{{ t('admin.user.locale') }}</span>
          <el-radio-group
            v-model="formLocale"
            :disabled="dialogBusy"
          >
            <el-radio-button
              v-for="option in LOCALE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <label
          v-if="editing === null"
          class="admin-field"
        >
          <span class="admin-field-label">{{ t('admin.user.initialPassword') }}</span>
          <el-input
            v-model="formPassword"
            type="password"
            show-password
            :disabled="dialogBusy"
            :placeholder="t('admin.user.passwordHint')"
          />
        </label>
        <p
          v-if="editing === null"
          class="admin-field-hint"
        >
          {{ t('admin.user.passwordHint') }}
        </p>
        <p
          v-if="dialogError"
          class="admin-form-error"
          role="alert"
        >
          {{ dialogError }}
        </p>
      </div>
      <template #footer>
        <el-button
          :disabled="dialogBusy"
          @click="dialogOpen = false"
        >
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="dialogBusy"
          @click="onSubmit"
        >
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="resetOpen"
      :title="t('admin.user.resetTitle')"
      width="440px"
    >
      <p class="admin-field-hint">
        {{ t('admin.user.resetNote') }}
      </p>
      <p class="user-reset-password">
        {{ resetPassword }}
      </p>
      <template #footer>
        <el-button
          type="primary"
          @click="resetOpen = false"
        >
          {{ t('admin.user.close') }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.user-admin {
  display: grid;
  gap: 12px;
}

.admin-body {
  padding: 8px 16px 16px;
}

.admin-error {
  margin: 0;
  padding: 16px 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

.admin-form {
  display: grid;
  gap: 12px;
}

.admin-field {
  display: grid;
  gap: 6px;
}

.admin-field-label {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.admin-field-hint {
  margin: -6px 0 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.admin-form-error {
  margin: 0;
  padding: 8px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
}

.admin-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 0.78rem;
}

.admin-tag.is-enabled {
  background: var(--kcgl-color-primary-bg);
  color: var(--kcgl-color-primary);
}

.admin-tag.is-disabled {
  background: var(--kcgl-color-bg);
  color: var(--kcgl-color-text-sub);
}

.admin-tag.is-locked {
  margin-left: 6px;
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
}

.user-reset-password {
  margin: 12px 0;
  padding: 10px 14px;
  border: 1px dashed var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-family: monospace;
  font-size: 1.05rem;
  letter-spacing: 0.06em;
  text-align: center;
  user-select: all;
}
</style>
