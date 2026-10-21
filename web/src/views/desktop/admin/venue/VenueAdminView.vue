<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { toDisplayMessage } from '@/utils/errors'
import {
  createVenue,
  deleteVenue,
  fetchVenues,
  setVenueStatus,
  updateVenue,
} from '@/utils/api'
import type { Venue } from '@/utils/api'

/**
 * 会场管理页（/admin/venues，M2-8b-2）：列表全员相关页 E+ 可达——
 * 创建/改名改码=编辑者以上（现场遇到日历外新拍卖会自救），停用/启用/删除=仅管理员。
 * code 为半角英数字 1-8 位（D-165），嵌在管理号开头；改码只影响今后生成的管理号，
 * 已录入商品里是当时的快照（D-166）。删除不可逆故要求逐字输入会场码双重确认，
 * 且商品引用过的会场只能停用（后端 409017 拦，前端文案引导）。
 */

const { t } = useI18n()
const auth = useAuthStore()

const canToggle = computed(() => auth.me != null && auth.me.role === 1)

// ------------------------------------------------------------- 列表

const venues = ref<Venue[]>([])
const loading = ref(true)
const loadError = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    venues.value = await fetchVenues(false)
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

// ------------------------------------------------------------- 创建/编辑弹层

/** 半角英数字 1-8 位；i 标志让用户输小写也能过，提交前统一转大写。 */
const VENUE_CODE_PATTERN = /^[A-Z0-9]{1,8}$/i

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const dialogBusy = ref(false)
const dialogError = ref('')
const formCode = ref('')
const formName = ref('')
const editTarget = ref<Venue | null>(null)

function openCreate(): void {
  dialogMode.value = 'create'
  formCode.value = ''
  formName.value = ''
  editTarget.value = null
  dialogError.value = ''
  dialogOpen.value = true
}

function openEdit(venue: Venue): void {
  dialogMode.value = 'edit'
  formCode.value = venue.code
  formName.value = venue.name
  editTarget.value = venue
  dialogError.value = ''
  dialogOpen.value = true
}

/** 就地校验（两字段小表单不引 el-form 规则引擎，与录入页一致的手写校验）。 */
function validate(): string {
  if (!VENUE_CODE_PATTERN.test(formCode.value.trim())) {
    return t('admin.venue.codeInvalid')
  }
  if (formName.value.trim() === '') {
    return t('admin.venue.nameRequired')
  }
  return ''
}

async function onSubmit(): Promise<void> {
  if (dialogBusy.value) return
  dialogError.value = validate()
  if (dialogError.value !== '') return
  const code = formCode.value.trim().toUpperCase()
  const name = formName.value.trim()
  dialogBusy.value = true
  try {
    if (dialogMode.value === 'create') {
      await createVenue({ code, name })
    } else if (editTarget.value !== null) {
      await updateVenue(editTarget.value.id, { name, code })
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    // 409002 会场码重复等服务端校验结果就地展示
    dialogError.value = toDisplayMessage(error, t)
  } finally {
    dialogBusy.value = false
  }
}

// ------------------------------------------------------------- 删除（仅管理员，输码双重确认）

const deleteOpen = ref(false)
const deleteTarget = ref<Venue | null>(null)
const deleteTyped = ref('')
const deleteBusy = ref(false)
const deleteError = ref('')

/** 逐字输入会场码才点亮确认键（大小写不敏感，与提交归一一致）。 */
const deleteConfirmed = computed(
  () =>
    deleteTarget.value != null &&
    deleteTyped.value.trim().toUpperCase() === deleteTarget.value.code,
)

function openDelete(venue: Venue): void {
  if (!canToggle.value) return
  deleteTarget.value = venue
  deleteTyped.value = ''
  deleteError.value = ''
  deleteOpen.value = true
}

async function onDelete(): Promise<void> {
  if (deleteTarget.value == null || !deleteConfirmed.value || deleteBusy.value) return
  deleteBusy.value = true
  deleteError.value = ''
  try {
    await deleteVenue(deleteTarget.value.id)
    deleteOpen.value = false
    await load()
  } catch (error) {
    // 409017 有商品引用：提示改走停用
    deleteError.value = toDisplayMessage(error, t)
  } finally {
    deleteBusy.value = false
  }
}

// ------------------------------------------------------------- 停用/启用（仅管理员）

const togglingIds = ref<number[]>([])

function isToggling(id: number): boolean {
  return togglingIds.value.includes(id)
}

async function onToggle(venue: Venue): Promise<void> {
  if (!canToggle.value || isToggling(venue.id)) return
  togglingIds.value = [...togglingIds.value, venue.id]
  try {
    await setVenueStatus(venue.id, !venue.enabled)
    await load()
  } finally {
    togglingIds.value = togglingIds.value.filter((x) => x !== venue.id)
  }
}
</script>

<template>
  <section class="venue-admin">
    <AppPageHeader
      :title="t('admin.venue.title')"
      :description="t('admin.venue.note')"
    >
      <template #actions>
        <el-button
          type="primary"
          @click="openCreate"
        >
          {{ t('admin.venue.createButton') }}
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
        :data="venues"
        class="admin-table"
      >
        <el-table-column
          prop="code"
          :label="t('admin.venue.code')"
          width="100"
        />
        <el-table-column
          prop="name"
          :label="t('admin.venue.name')"
          min-width="200"
        />
        <el-table-column
          :label="t('admin.status')"
          width="120"
        >
          <template #default="{ row }">
            <span
              class="admin-tag"
              :class="row.enabled ? 'is-enabled' : 'is-disabled'"
            >{{ row.enabled ? t('admin.enabled') : t('admin.disabled') }}</span>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('admin.actions')"
          width="280"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openEdit(row as Venue)"
            >
              {{ t('admin.venue.editButton') }}
            </el-button>
            <el-button
              v-if="canToggle"
              link
              :type="row.enabled ? 'danger' : 'primary'"
              :loading="isToggling(row.id)"
              @click="onToggle(row as Venue)"
            >
              {{ row.enabled ? t('admin.disable') : t('admin.enable') }}
            </el-button>
            <el-button
              v-if="canToggle"
              link
              type="danger"
              @click="openDelete(row as Venue)"
            >
              {{ t('admin.venue.deleteButton') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          {{ t('admin.venue.empty') }}
        </template>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogOpen"
      :title="dialogMode === 'create' ? t('admin.venue.createTitle') : t('admin.venue.editTitle')"
      width="420px"
      :close-on-click-modal="!dialogBusy"
    >
      <div class="admin-form">
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.venue.code') }}</span>
          <el-input
            v-model="formCode"
            :disabled="dialogBusy"
            maxlength="8"
            :placeholder="t('admin.venue.codePlaceholder')"
          />
          <span class="admin-field-hint">{{ t('admin.venue.codeHint') }}</span>
        </label>
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.venue.name') }}</span>
          <el-input
            v-model="formName"
            :disabled="dialogBusy"
            maxlength="64"
            :placeholder="t('admin.venue.namePlaceholder')"
          />
        </label>
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
      v-model="deleteOpen"
      :title="t('admin.venue.deleteTitle')"
      width="440px"
      :close-on-click-modal="!deleteBusy"
    >
      <div class="admin-form">
        <p class="admin-delete-warning">
          {{ t('admin.venue.deleteWarning', { code: deleteTarget?.code ?? '', name: deleteTarget?.name ?? '' }) }}
        </p>
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.venue.deleteConfirmLabel', { code: deleteTarget?.code ?? '' }) }}</span>
          <el-input
            v-model="deleteTyped"
            :disabled="deleteBusy"
            maxlength="8"
            autocomplete="off"
            :placeholder="deleteTarget?.code ?? ''"
          />
        </label>
        <p
          v-if="deleteError"
          class="admin-form-error"
          role="alert"
        >
          {{ deleteError }}
        </p>
      </div>
      <template #footer>
        <el-button
          :disabled="deleteBusy"
          @click="deleteOpen = false"
        >
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="danger"
          :disabled="!deleteConfirmed"
          :loading="deleteBusy"
          @click="onDelete"
        >
          {{ t('admin.venue.deleteConfirmButton') }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.venue-admin {
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

.admin-tag {
  display: inline-block;
  padding: 1px 10px;
  border-radius: var(--kcgl-radius-s);
  font-size: 0.8rem;
  font-weight: 600;
}

.admin-tag.is-enabled {
  border: 1px solid var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.admin-tag.is-disabled {
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-bg);
  color: var(--kcgl-color-text-faint);
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
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}

.admin-delete-warning {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
  line-height: 1.6;
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
</style>
