<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { createYearCode, fetchYearCodes, updateYearCode } from '@/utils/api'
import type { YearCode } from '@/utils/api'

/**
 * 年代号管理页（/admin/year-codes，M2-8b-2，仅管理员）：年份↔代号双向唯一，
 * 代号进管理号第 3 位。V1 已种子化 2016=A〜2041=Z；本页供 Z 用尽后扩展与
 * 个别修正（增改=管理员）。年份与代号都可改（无锁定语义，改前提示会影响
 * 未来取号——note 文案承载）。
 */

const { t } = useI18n()

const YEAR_MIN = 2016
const YEAR_MAX = 2999
const CODE_PATTERN = /^[A-Z]$/

// ------------------------------------------------------------- 列表

const yearCodes = ref<YearCode[]>([])
const loading = ref(true)
const loadError = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    yearCodes.value = await fetchYearCodes()
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

const dialogOpen = ref(false)
const editing = ref<YearCode | null>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const formYear = ref('')
const formCode = ref('')

function openCreate(): void {
  editing.value = null
  formYear.value = ''
  formCode.value = ''
  dialogError.value = ''
  dialogOpen.value = true
}

function openEdit(row: YearCode): void {
  editing.value = row
  formYear.value = String(row.year)
  formCode.value = row.code
  dialogError.value = ''
  dialogOpen.value = true
}

async function onSubmit(): Promise<void> {
  if (dialogBusy.value) return
  const year = Number(formYear.value.trim())
  if (!Number.isInteger(year) || year < YEAR_MIN || year > YEAR_MAX) {
    dialogError.value = t('admin.yearCode.yearInvalid')
    return
  }
  if (!CODE_PATTERN.test(formCode.value)) {
    dialogError.value = t('admin.yearCode.codeInvalid')
    return
  }
  dialogError.value = ''
  dialogBusy.value = true
  try {
    const payload = { year, code: formCode.value }
    if (editing.value === null) {
      await createYearCode(payload)
    } else {
      await updateYearCode(editing.value.id, payload)
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    dialogError.value = toDisplayMessage(error, t)
  } finally {
    dialogBusy.value = false
  }
}

const dialogTitle = computed(() =>
  editing.value === null ? t('admin.yearCode.createTitle') : t('admin.yearCode.editTitle'),
)
</script>

<template>
  <section class="year-admin">
    <div class="admin-header">
      <div>
        <h1 class="admin-title">
          {{ t('admin.yearCode.title') }}
        </h1>
        <p class="admin-note">
          {{ t('admin.yearCode.note') }}
        </p>
      </div>
      <el-button
        type="primary"
        @click="openCreate"
      >
        {{ t('admin.yearCode.createButton') }}
      </el-button>
    </div>

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
        :data="yearCodes"
        class="admin-table"
      >
        <el-table-column
          prop="year"
          :label="t('admin.yearCode.year')"
          width="140"
        />
        <el-table-column
          prop="code"
          :label="t('admin.yearCode.code')"
          width="140"
        />
        <el-table-column
          :label="t('admin.actions')"
          width="160"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openEdit(row as YearCode)"
            >
              {{ t('admin.edit') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          {{ t('admin.yearCode.empty') }}
        </template>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="420px"
      :close-on-click-modal="!dialogBusy"
    >
      <div class="admin-form">
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.yearCode.year') }}</span>
          <el-input
            v-model="formYear"
            :disabled="dialogBusy"
            :placeholder="t('admin.yearCode.yearPlaceholder')"
          />
        </label>
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.yearCode.code') }}</span>
          <el-input
            v-model="formCode"
            :disabled="dialogBusy"
            maxlength="1"
            :placeholder="t('admin.yearCode.codePlaceholder')"
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
  </section>
</template>

<style scoped>
.year-admin {
  display: grid;
  gap: 12px;
}

.admin-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.admin-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.admin-note {
  margin: 4px 0 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
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
