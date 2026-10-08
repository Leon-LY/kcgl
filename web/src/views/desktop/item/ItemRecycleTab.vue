<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppEmptyState from '@/components/AppEmptyState.vue'
import { toDisplayMessage } from '@/utils/errors'
import { formatJstDateTime } from '@/utils/format'
import { fetchRecycleBin, restoreItem, restoreItemsBatch } from '@/utils/api'
import type { RecycleBinRow } from '@/utils/api'
import { ITEM_LIST_PAGE_SIZE, batchEntriesOf, errorText, itemListDisplay } from './itemListShared'
import { newClientId } from '@/utils/id'

/**
 * 削除済み商品（回收站）页签：软删件列表、单件復元、勾选一括復元。
 *
 * 从 ItemsView 抽出来的（D-141）。之所以能整块搬走，是因为它与主列表之间只有
 * **一条**真依赖：復元会把件送回主列表，主列表得重取——这条用 emit('changed')
 * 表达，父组件收到后刷自己那份数据。除此之外两边各持一套 ref、各查各的接口。
 *
 * 拆之前批删与復元共用父组件的一个 batchResult（再各自 filter kind），拆开后
 * 各持一份自己的，反而把那份"共享再过滤"绕开了。
 */
const emit = defineEmits<{ changed: [] }>()

const { t, te } = useI18n()
const { warehouseOf, stockText, saleText, stockTagClass, saleTagClass } = itemListDisplay(t)

const rows = ref<RecycleBinRow[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const restoringIds = ref<number[]>([])
/** 勾选行（一括復元的对象）。 */
const selected = ref<RecycleBinRow[]>([])
let seq = 0

const busy = ref(false)
/** 復元回执只存**数据**不存文案：全站切语言是运行时行为（D-029），存字符串的话
 *  切到中文后这条提示会留在日文。 */
const result = ref<{ ok: number; failures: { itemId: number; code: number }[] } | null>(null)

const message = computed(() => {
  const current = result.value
  if (current == null) {
    return ''
  }
  return current.failures.length === 0
    ? t('items.batch.restoreDone', { n: current.ok })
    : t('items.batch.restorePartial', { ok: current.ok, ng: current.failures.length })
})

/** 失败行要报管理番号而非内部 id——管理番号才是现场认得出的东西。 */
const failLines = computed(() =>
  (result.value?.failures ?? []).map((failure) =>
    t('items.batch.failLine', {
      code: itemCodeOf(failure.itemId) ?? `#${failure.itemId}`,
      message: errorText(t, te, failure.code),
    }),
  ),
)

function itemCodeOf(id: number): string | undefined {
  return rows.value.find((row) => row.id === id)?.itemCode
}

/** 取当前页。序号守卫：连点换页/连点復元时只认最后一次响应，旧响应丢弃。 */
async function load(): Promise<void> {
  const current = ++seq
  loading.value = true
  error.value = ''
  try {
    const data = await fetchRecycleBin(page.value, ITEM_LIST_PAGE_SIZE)
    if (current !== seq) {
      return
    }
    rows.value = data.rows
    total.value = data.total
  } catch (caught) {
    if (current !== seq) {
      return
    }
    error.value = toDisplayMessage(caught, t)
  } finally {
    if (current === seq) {
      loading.value = false
    }
  }
}

/**
 * 回首页并重取。父组件在两种时机调它（经 defineExpose）：一括削除之后（删掉的件
 * 进了这一侧，页数可能已经不够）、以及 SSE 失效整页重取时。首次载入也由父组件触发。
 */
async function reload(): Promise<void> {
  page.value = 1
  await load()
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

function onSelectionChange(selection: RecycleBinRow[]): void {
  selected.value = selection
}

function isRestoring(id: number): boolean {
  return restoringIds.value.includes(id)
}

async function onRestore(row: RecycleBinRow): Promise<void> {
  if (isRestoring(row.id)) {
    return
  }
  restoringIds.value = [...restoringIds.value, row.id]
  error.value = ''
  try {
    // 同 ItemDeleteDialog：crypto.randomUUID 仅安全上下文可用，http 部署下必抛 TypeError
    await restoreItem(row.id, newClientId())
    // 復元把件送回主列表那一侧，主列表不刷就会一直显示"这件已经不在了"
    await load()
    emit('changed')
  } catch (caught) {
    error.value = toDisplayMessage(caught, t)
  } finally {
    restoringIds.value = restoringIds.value.filter((x) => x !== row.id)
  }
}

async function onBatchRestore(): Promise<void> {
  if (busy.value || selected.value.length === 0) {
    return
  }
  busy.value = true
  try {
    const data = await restoreItemsBatch(batchEntriesOf(selected.value))
    result.value = { ok: data.succeeded, failures: data.failures }
    await load()
    emit('changed')
  } catch (caught) {
    error.value = toDisplayMessage(caught, t)
  } finally {
    busy.value = false
  }
}

defineExpose({ reload })
</script>

<template>
  <div class="items-recycle">
    <p class="items-note">
      {{ t('items.recycle.note') }}
    </p>
    <p
      v-if="error"
      class="kcgl-error-box"
      role="alert"
    >
      {{ error }}
      <el-button
        link
        type="primary"
        @click="reload"
      >
        {{ t('common.reload') }}
      </el-button>
    </p>
    <template v-else>
      <div
        v-if="rows.length > 0"
        class="items-recycle-actions"
      >
        <el-button
          :disabled="selected.length === 0"
          :loading="busy"
          @click="onBatchRestore"
        >
          {{ t('items.batch.restore') }}
        </el-button>
      </div>
      <div class="kcgl-list-count-row">
        <p class="kcgl-list-count">
          {{ t('items.totalCount', { n: total }) }}
        </p>
        <p
          v-if="selected.length > 0"
          class="kcgl-list-count is-selected"
        >
          {{ t('items.batch.selected', { n: selected.length }) }}
        </p>
      </div>
      <div
        v-if="result"
        class="kcgl-batch-result"
        :class="result.failures.length === 0 ? 'is-ok' : 'is-warn'"
        role="status"
      >
        <p class="kcgl-batch-result-line">
          {{ message }}
        </p>
        <ul
          v-if="failLines.length > 0"
          class="kcgl-batch-result-list"
        >
          <li
            v-for="line in failLines"
            :key="line"
          >
            {{ line }}
          </li>
        </ul>
        <el-button
          link
          type="primary"
          @click="result = null"
        >
          {{ t('common.close') }}
        </el-button>
      </div>
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        class="kcgl-list-table"
        @selection-change="onSelectionChange"
      >
        <el-table-column
          type="selection"
          width="44"
          fixed="left"
        />
        <el-table-column
          :label="t('items.recycle.column.item')"
          min-width="168"
          fixed="left"
        >
          <template #default="{ row }">
            <div class="kcgl-list-item">
              <span class="kcgl-thumb">
                <img
                  v-if="(row as RecycleBinRow).thumbUrl"
                  :src="(row as RecycleBinRow).thumbUrl ?? undefined"
                  alt=""
                  loading="lazy"
                >
              </span>
              <span class="kcgl-list-code">{{ (row as RecycleBinRow).itemCode }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.recycle.column.itemName')"
          min-width="130"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ (row as RecycleBinRow).itemName ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.recycle.column.venue')"
          min-width="116"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ (row as RecycleBinRow).venueName ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.recycle.column.warehouse')"
          width="100"
        >
          <template #default="{ row }">
            {{ warehouseOf((row as RecycleBinRow).warehouse) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.recycle.column.status')"
          width="164"
        >
          <template #default="{ row }">
            <div class="kcgl-tags">
              <span
                class="kcgl-tag"
                :class="stockTagClass((row as RecycleBinRow).stockStatus)"
              >{{ stockText((row as RecycleBinRow).stockStatus) }}</span>
              <span
                class="kcgl-tag"
                :class="saleTagClass((row as RecycleBinRow).saleStatus)"
              >{{ saleText((row as RecycleBinRow).saleStatus) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.recycle.column.deletedAt')"
          width="150"
        >
          <template #default="{ row }">
            {{ formatJstDateTime((row as RecycleBinRow).deletedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.recycle.column.reason')"
          min-width="140"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ (row as RecycleBinRow).reason ?? '—' }}
          </template>
        </el-table-column>
        <!-- 右钉「操作」；回收站的「状態」不钉——它与本列之间还隔着削除日時与
             削除理由，钉在中间会被截成孤立的一条（详见 ItemsView 文件头列宽注释） -->
        <el-table-column
          :label="t('admin.actions')"
          width="110"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="isRestoring((row as RecycleBinRow).id)"
              @click="onRestore(row as RecycleBinRow)"
            >
              {{ t('items.recycle.restore') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <AppEmptyState
            compact
            :title="t('items.recycle.empty')"
          />
        </template>
      </el-table>
      <el-pagination
        v-if="total > ITEM_LIST_PAGE_SIZE"
        layout="prev, pager, next"
        :total="total"
        :page-size="ITEM_LIST_PAGE_SIZE"
        :current-page="page"
        class="kcgl-list-pagination"
        @current-change="onPageChange"
      />
    </template>
  </div>
</template>

<style scoped>
/* 只有一个透明包裹层，纯粹是给这个片段一个单根 */
.items-recycle {
  display: block;
}

.items-note {
  margin: 0 0 var(--kcgl-space-3);
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

/* 回收站的一括復元：与主列表的一括削除同位（表格上方右对齐前的动作区） */
.items-recycle-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--kcgl-space-2);
}
</style>
