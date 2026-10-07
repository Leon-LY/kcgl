<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { formatJstDateTime } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { fetchOperationLogs } from '@/utils/api'
import type { OperationLogRow } from '@/utils/api'

/**
 * 操作ログ（/admin/logs，M5-④，仅管理员）：全系统操作留痕查询——
 * 动作/实体类型/操作人/时间窗筛选 + id 倒序分页。日志不可删改（验收 9）：
 * 无任何写按钮是刻意设计。detail 为原始 JSON 文本，展开行原样呈现。
 */

const PAGE_SIZE = 20

const { t } = useI18n()

const action = ref('')
const entityType = ref('')
const operatorName = ref('')
const dateFrom = ref<string | null>(null)
const dateTo = ref<string | null>(null)

const rows = ref<OperationLogRow[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)
const error = ref('')
let loadSeq = 0

async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const data = await fetchOperationLogs({
      action: action.value.trim() === '' ? undefined : action.value.trim(),
      entityType: entityType.value.trim() === '' ? undefined : entityType.value.trim(),
      operatorName: operatorName.value.trim() === '' ? undefined : operatorName.value.trim(),
      dateFrom: dateFrom.value ?? undefined,
      dateTo: dateTo.value ?? undefined,
      page: page.value,
      size: PAGE_SIZE,
    })
    if (seq !== loadSeq) {
      return
    }
    rows.value = data.rows
    total.value = data.total
  } catch (e) {
    if (seq !== loadSeq) {
      return
    }
    error.value = toDisplayMessage(e, t)
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

function onSearch(): void {
  page.value = 1
  void load()
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

function onClearFilters(): void {
  action.value = ''
  entityType.value = ''
  operatorName.value = ''
  dateFrom.value = null
  dateTo.value = null
  onSearch()
}

/** detail 留痕原样呈现：能美化缩进则缩进（JSON.parse 失败=非 JSON 文本，原样）。 */
function prettyDetail(row: OperationLogRow): string {
  if (row.detail == null || row.detail === '') {
    return '—'
  }
  try {
    return JSON.stringify(JSON.parse(row.detail), null, 2)
  } catch {
    return row.detail
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="oplogs-view">
    <AppPageHeader :title="t('oplogs.title')" />

    <div class="kcgl-card oplogs-body">
      <div class="oplogs-toolbar">
        <div class="oplogs-filters">
          <el-input
            v-model="action"
            :placeholder="t('oplogs.actionPlaceholder')"
            clearable
            class="oplogs-filter-action"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-input
            v-model="entityType"
            :placeholder="t('oplogs.entityPlaceholder')"
            clearable
            class="oplogs-filter-entity"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-input
            v-model="operatorName"
            :placeholder="t('ledgers.operatorPlaceholder')"
            clearable
            class="oplogs-filter-operator"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-date-picker
            v-model="dateFrom"
            type="date"
            :placeholder="t('ledgers.dateFrom')"
            value-format="YYYY-MM-DD"
            class="oplogs-filter-date"
            @change="onSearch"
          />
          <span class="oplogs-range-sep">〜</span>
          <el-date-picker
            v-model="dateTo"
            type="date"
            :placeholder="t('ledgers.dateTo')"
            value-format="YYYY-MM-DD"
            class="oplogs-filter-date"
            @change="onSearch"
          />
          <el-button
            link
            type="primary"
            @click="onClearFilters"
          >
            {{ t('items.clear') }}
          </el-button>
        </div>
        <p class="oplogs-hint">
          {{ t('oplogs.hint') }}
        </p>
      </div>

      <p
        v-if="error"
        class="kcgl-error-box"
        role="alert"
      >
        {{ error }}
        <el-button
          link
          type="primary"
          @click="load"
        >
          {{ t('common.reload') }}
        </el-button>
      </p>
      <template v-else>
        <p class="oplogs-count">
          {{ t('ledgers.totalCount', { n: total }) }}
        </p>
        <el-table
          v-loading="loading"
          :data="rows"
          row-key="id"
          class="oplogs-table"
        >
          <el-table-column
            type="expand"
          >
            <template #default="{ row }">
              <pre class="oplogs-detail">{{ prettyDetail(row as OperationLogRow) }}</pre>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.at')"
            width="150"
          >
            <template #default="{ row }">
              {{ formatJstDateTime((row as OperationLogRow).createdAt) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('oplogs.column.action')"
            min-width="150"
          >
            <template #default="{ row }">
              <span class="oplogs-action">{{ (row as OperationLogRow).action }}</span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('oplogs.column.entity')"
            min-width="110"
          >
            <template #default="{ row }">
              {{ (row as OperationLogRow).entityType
                + ((row as OperationLogRow).entityId != null ? ` #${(row as OperationLogRow).entityId}` : '') }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('ledgers.column.operator')"
            width="110"
          >
            <template #default="{ row }">
              {{ (row as OperationLogRow).operatorName }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('oplogs.column.ip')"
            width="130"
          >
            <template #default="{ row }">
              {{ (row as OperationLogRow).ip ?? '—' }}
            </template>
          </el-table-column>
          <template #empty>
            {{ t('oplogs.empty') }}
          </template>
        </el-table>
        <el-pagination
          v-if="total > PAGE_SIZE"
          layout="prev, pager, next"
          :total="total"
          :page-size="PAGE_SIZE"
          :current-page="page"
          class="oplogs-pagination"
          @current-change="onPageChange"
        />
      </template>
    </div>
  </section>
</template>

<style scoped>
.oplogs-view {
  display: grid;
  gap: 16px;
}

.oplogs-body {
  display: grid;
  gap: 12px;
  padding: 20px;
}

.oplogs-toolbar {
  display: grid;
  gap: 8px;
}

.oplogs-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.oplogs-filter-action {
  width: 160px;
}

.oplogs-filter-entity {
  width: 130px;
}

.oplogs-filter-operator {
  width: 130px;
}

.oplogs-filter-date {
  width: 140px;
}

.oplogs-range-sep {
  color: var(--kcgl-color-text-sub);
}

.oplogs-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.oplogs-count {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.oplogs-action {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.85rem;
}

.oplogs-detail {
  margin: 0;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--kcgl-color-bg);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.8rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.oplogs-pagination {
  justify-self: end;
}
</style>
