<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppEmptyState from '@/components/AppEmptyState.vue'
import { formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { fetchYahooReconcile } from '@/utils/api'
import type { YahooReconcile, YahooReconcileRow } from '@/utils/api'
import { yahooDisplay } from './yahooShared'

/**
 * 照合页签（M5-②b）：三个活视图（落札済み・未出庫 / 落札なし・再出品待ち /
 * 出庫済み・ヤフー出品中），滞留红标、近期同步降灰。
 *
 * 从 YahooView 抽出来的（D-144）。与另两个页签无依赖，只由父组件在初始载入与
 * SSE 失效时调 reload()（本组件不自载，避免父组件装配顺序影响加载次数）。
 */

const { t } = useI18n()

const { warehouseOf } = yahooDisplay(t)

const reconcileData = ref<YahooReconcile | null>(null)
const reconcileError = ref('')
let reconcileSeq = 0

async function loadReconcile(): Promise<void> {
  const seq = ++reconcileSeq
  reconcileError.value = ''
  try {
    const data = await fetchYahooReconcile()
    if (seq !== reconcileSeq) {
      return
    }
    reconcileData.value = data
  } catch (error) {
    if (seq !== reconcileSeq) {
      return
    }
    reconcileError.value = toDisplayMessage(error, t)
  }
}

/** 父组件调它：初始载入与 SSE 失效整页重取（首载由父组件的 onMounted 触发，本组件不自载）。 */
function reload(): Promise<void> {
  return loadReconcile()
}

defineExpose({ reload })
</script>

<template>
  <p
    v-if="reconcileError"
    class="kcgl-error-box"
    role="alert"
  >
    {{ reconcileError }}
    <el-button
      link
      type="primary"
      @click="loadReconcile"
    >
      {{ t('common.reload') }}
    </el-button>
  </p>
  <template v-else-if="reconcileData">
    <h3 class="yahoo-section-subtitle">
      {{ t('yahoo.reconcile.soldNotShipped') }}（{{ reconcileData.soldNotShipped.length }}）
    </h3>
    <el-table
      :data="reconcileData.soldNotShipped"
      row-key="itemId"
      class="kcgl-yahoo-table"
    >
      <el-table-column
        prop="itemCode"
        :label="t('yahoo.itemCode')"
        min-width="130"
      >
        <template #default="{ row }">
          <span class="kcgl-yahoo-code">{{ (row as YahooReconcileRow).itemCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        width="140"
      >
        <template #default="{ row }">
          {{ warehouseOf((row as YahooReconcileRow).warehouse) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.shelfNo')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).shelfNo ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.reconcile.soldPrice')"
        width="110"
        align="right"
      >
        <template #default="{ row }">
          {{ formatYen((row as YahooReconcileRow).soldPrice) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.orderId')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).orderId ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.auctionId')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).auctionId ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.reconcile.closedAt')"
        width="150"
      >
        <template #default="{ row }">
          {{ formatJstDateTime((row as YahooReconcileRow).closedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        width="100"
      >
        <template #default="{ row }">
          <span
            v-if="(row as YahooReconcileRow).delayed"
            class="kcgl-yahoo-tag is-failed"
          >{{ t('yahoo.reconcile.delayed') }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <AppEmptyState
          compact
          :title="t('yahoo.reconcile.empty')"
          :description="t('yahoo.reconcile.emptyHint')"
        />
      </template>
    </el-table>

    <h3 class="yahoo-section-subtitle">
      {{ t('yahoo.reconcile.canceledNotRelisted') }}（{{ reconcileData.canceledNotRelisted.length }}）
    </h3>
    <el-table
      :data="reconcileData.canceledNotRelisted"
      row-key="itemId"
      class="kcgl-yahoo-table"
    >
      <el-table-column
        prop="itemCode"
        :label="t('yahoo.itemCode')"
        min-width="130"
      >
        <template #default="{ row }">
          <span class="kcgl-yahoo-code">{{ (row as YahooReconcileRow).itemCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        width="140"
      >
        <template #default="{ row }">
          {{ warehouseOf((row as YahooReconcileRow).warehouse) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.shelfNo')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).shelfNo ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.auctionId')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).auctionId ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.reconcile.closedAt')"
        width="150"
      >
        <template #default="{ row }">
          {{ formatJstDateTime((row as YahooReconcileRow).closedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        width="100"
      >
        <template #default="{ row }">
          <span
            v-if="(row as YahooReconcileRow).delayed"
            class="kcgl-yahoo-tag is-failed"
          >{{ t('yahoo.reconcile.delayed') }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <AppEmptyState
          compact
          :title="t('yahoo.reconcile.empty')"
          :description="t('yahoo.reconcile.emptyHint')"
        />
      </template>
    </el-table>

    <h3 class="yahoo-section-subtitle">
      {{ t('yahoo.reconcile.withdrawNeeded') }}（{{ reconcileData.withdrawNeeded.length }}）
    </h3>
    <el-table
      :data="reconcileData.withdrawNeeded"
      row-key="itemId"
      class="kcgl-yahoo-table"
    >
      <el-table-column
        prop="itemCode"
        :label="t('yahoo.itemCode')"
        min-width="130"
      >
        <template #default="{ row }">
          <span class="kcgl-yahoo-code">{{ (row as YahooReconcileRow).itemCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        width="140"
      >
        <template #default="{ row }">
          {{ warehouseOf((row as YahooReconcileRow).warehouse) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.shelfNo')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).shelfNo ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.auctionId')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooReconcileRow).auctionId ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.reconcile.closedAt')"
        width="150"
      >
        <template #default="{ row }">
          {{ formatJstDateTime((row as YahooReconcileRow).closedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.reconcile.lastSyncedAt')"
        width="150"
      >
        <template #default="{ row }">
          {{ formatJstDateTime((row as YahooReconcileRow).lastSyncedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        width="150"
      >
        <template #default="{ row }">
          <span
            v-if="(row as YahooReconcileRow).recentlySynced"
            class="kcgl-yahoo-tag is-muted"
          >{{ t('yahoo.reconcile.recentlySynced') }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <AppEmptyState
          compact
          :title="t('yahoo.reconcile.empty')"
          :description="t('yahoo.reconcile.emptyHint')"
        />
      </template>
    </el-table>

    <p class="kcgl-yahoo-note">
      {{ t('yahoo.reconcile.note') }}
    </p>
  </template>
</template>

<style scoped>
.yahoo-section-subtitle {
  margin: 20px 0 8px;
  font-size: 0.95rem;
  font-weight: 600;
}
</style>
