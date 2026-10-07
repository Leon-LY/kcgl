<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { toDisplayMessage } from '@/utils/errors'
import {
  createPriceBand,
  fetchPriceBands,
  setPriceBandStatus,
  updatePriceBand,
} from '@/utils/api'
import type { PriceBand } from '@/utils/api'

/**
 * 价格档位管理页（/admin/price-bands，M2-8b-2，仅管理员）：档位字母进管理号
 * 末位（左闭右开 [lower, upper)，NULL=无界端）。只停用不物理删、不回溯历史
 * （已录商品的档位快照不变）。区间重叠由服务层行锁内校验（409004 就地展示）。
 */

const { t } = useI18n()

// ------------------------------------------------------------- 列表

const bands = ref<PriceBand[]>([])
const loading = ref(true)
const loadError = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    bands.value = await fetchPriceBands()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

/** 展示口径=闭区间人读（[1000, 3000) → 1,000〜2,999円）；无界端用专用文案。 */
function rangeLabel(band: PriceBand): string {
  const lower = band.lowerBound
  const upper = band.upperBound
  if (lower === null && upper !== null) {
    return t('admin.band.rangeTo', { upper: formatYen(upper - 1) })
  }
  if (lower !== null && upper === null) {
    return t('admin.band.rangeFrom', { lower: formatYen(lower) })
  }
  if (lower !== null && upper !== null) {
    return t('admin.band.rangeBetween', {
      lower: formatYen(lower),
      upper: formatYen(upper - 1),
    })
  }
  return t('admin.band.rangeAll')
}

function formatYen(value: number): string {
  return value.toLocaleString('ja-JP')
}

// ------------------------------------------------------------- 创建/编辑弹层

const BAND_CODE_PATTERN = /^[A-Z]$/
const AMOUNT_MAX = 99_999_999

const dialogOpen = ref(false)
const editingBand = ref<PriceBand | null>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const formCode = ref('')
/** 空串=无界端（首档无下限/末档无上限）。 */
const formLower = ref('')
const formUpper = ref('')

function openCreate(): void {
  editingBand.value = null
  formCode.value = ''
  formLower.value = ''
  formUpper.value = ''
  dialogError.value = ''
  dialogOpen.value = true
}

function openEdit(band: PriceBand): void {
  editingBand.value = band
  formCode.value = band.code
  formLower.value = band.lowerBound === null ? '' : String(band.lowerBound)
  formUpper.value = band.upperBound === null ? '' : String(band.upperBound)
  dialogError.value = ''
  dialogOpen.value = true
}

/** 解析金额输入：空串→null（无界）；非法/超限→抛文案键。 */
function parseBound(raw: string, field: 'lower' | 'upper'): number | null {
  if (raw.trim() === '') return null
  const value = Number(raw.trim())
  if (!Number.isInteger(value) || value < 0 || value > AMOUNT_MAX) {
    throw new Error(t(field === 'lower' ? 'admin.band.lowerInvalid' : 'admin.band.upperInvalid'))
  }
  return value
}

async function onSubmit(): Promise<void> {
  if (dialogBusy.value) return
  dialogError.value = ''
  let lower: number | null
  let upper: number | null
  try {
    lower = parseBound(formLower.value, 'lower')
    upper = parseBound(formUpper.value, 'upper')
  } catch (error) {
    dialogError.value = error instanceof Error ? error.message : ''
    return
  }
  if (!BAND_CODE_PATTERN.test(formCode.value)) {
    dialogError.value = t('admin.band.codeInvalid')
    return
  }
  if (lower !== null && upper !== null && lower >= upper) {
    dialogError.value = t('admin.band.rangeInvalid')
    return
  }
  dialogBusy.value = true
  try {
    const payload = { code: formCode.value, lowerBound: lower, upperBound: upper }
    if (editingBand.value === null) {
      await createPriceBand(payload)
    } else {
      await updatePriceBand(editingBand.value.id, payload)
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    dialogError.value = toDisplayMessage(error, t)
  } finally {
    dialogBusy.value = false
  }
}

// ------------------------------------------------------------- 停用/启用

const togglingIds = ref<number[]>([])

function isToggling(id: number): boolean {
  return togglingIds.value.includes(id)
}

async function onToggle(band: PriceBand): Promise<void> {
  if (isToggling(band.id)) return
  togglingIds.value = [...togglingIds.value, band.id]
  try {
    await setPriceBandStatus(band.id, !band.enabled)
    await load()
  } finally {
    togglingIds.value = togglingIds.value.filter((x) => x !== band.id)
  }
}

const dialogTitle = computed(() =>
  editingBand.value === null ? t('admin.band.createTitle') : t('admin.band.editTitle'),
)
</script>

<template>
  <section class="band-admin">
    <AppPageHeader
      :title="t('admin.band.title')"
      :description="t('admin.band.note')"
    >
      <template #actions>
        <el-button
          type="primary"
          @click="openCreate"
        >
          {{ t('admin.band.createButton') }}
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
        :data="bands"
        class="admin-table"
      >
        <el-table-column
          prop="code"
          :label="t('admin.band.code')"
          width="100"
        />
        <el-table-column
          :label="t('admin.band.range')"
          min-width="200"
        >
          <template #default="{ row }">
            {{ rangeLabel(row as PriceBand) }}
          </template>
        </el-table-column>
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
          width="220"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openEdit(row as PriceBand)"
            >
              {{ t('admin.edit') }}
            </el-button>
            <el-button
              link
              :type="row.enabled ? 'danger' : 'primary'"
              :loading="isToggling(row.id)"
              @click="onToggle(row as PriceBand)"
            >
              {{ row.enabled ? t('admin.disable') : t('admin.enable') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          {{ t('admin.band.empty') }}
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
          <span class="admin-field-label">{{ t('admin.band.code') }}</span>
          <el-input
            v-model="formCode"
            :disabled="dialogBusy"
            maxlength="1"
            :placeholder="t('admin.band.codePlaceholder')"
          />
        </label>
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.band.lower') }}</span>
          <el-input
            v-model="formLower"
            :disabled="dialogBusy"
            :placeholder="t('admin.band.boundPlaceholder')"
          />
        </label>
        <label class="admin-field">
          <span class="admin-field-label">{{ t('admin.band.upper') }}</span>
          <el-input
            v-model="formUpper"
            :disabled="dialogBusy"
            :placeholder="t('admin.band.boundPlaceholder')"
          />
        </label>
        <p class="admin-form-hint">
          {{ t('admin.band.hint') }}
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
  </section>
</template>

<style scoped>
.band-admin {
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

.admin-form-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
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
