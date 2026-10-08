<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AppEmptyState from '@/components/AppEmptyState.vue'
import { formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { fetchPendingShipments } from '@/utils/api'
import type { YahooPendingShipment } from '@/utils/api'
import { yahooDisplay } from './yahooShared'

/**
 * 出荷待ち页签（M5-②b）：拣货队列，行内「この商品を売り上げる」直达扫码页。
 *
 * 从 YahooView 抽出来的（D-144）。与另两个页签无依赖，只由父组件在初始载入与
 * SSE 失效时调 reload()（本组件不自载，避免父组件装配顺序影响加载次数）。
 */

const { t } = useI18n()

const router = useRouter()

const { warehouseOf } = yahooDisplay(t)

const shipmentItems = ref<YahooPendingShipment[]>([])
const shipmentsError = ref('')
let shipmentsSeq = 0

async function loadShipments(): Promise<void> {
  const seq = ++shipmentsSeq
  shipmentsError.value = ''
  try {
    const data = await fetchPendingShipments()
    if (seq !== shipmentsSeq) {
      return
    }
    shipmentItems.value = data.items
  } catch (error) {
    if (seq !== shipmentsSeq) {
      return
    }
    shipmentsError.value = toDisplayMessage(error, t)
  }
}

function goSell(item: YahooPendingShipment): void {
  void router.push({ name: 'scan', query: { code: item.itemCode } })
}

/** 父组件调它：初始载入与 SSE 失效整页重取（首载由父组件的 onMounted 触发，本组件不自载）。 */
function reload(): Promise<void> {
  return loadShipments()
}

defineExpose({ reload })
</script>

<template>
  <p
    v-if="shipmentsError"
    class="kcgl-error-box"
    role="alert"
  >
    {{ shipmentsError }}
    <el-button
      link
      type="primary"
      @click="loadShipments"
    >
      {{ t('common.reload') }}
    </el-button>
  </p>
  <template v-else>
    <p class="yahoo-section-count">
      {{ t('yahoo.shipments.count', { n: shipmentItems.length }) }}
    </p>
    <el-table
      :data="shipmentItems"
      row-key="itemId"
      class="kcgl-yahoo-table"
    >
      <el-table-column
        width="70"
      >
        <template #default="{ row }">
          <span class="yahoo-thumb">
            <img
              v-if="(row as YahooPendingShipment).thumbUrl"
              :src="(row as YahooPendingShipment).thumbUrl ?? undefined"
              alt=""
              loading="lazy"
            >
          </span>
        </template>
      </el-table-column>
      <el-table-column
        prop="itemCode"
        :label="t('yahoo.itemCode')"
        min-width="130"
      >
        <template #default="{ row }">
          <span class="kcgl-yahoo-code">{{ (row as YahooPendingShipment).itemCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.warehouse')"
        width="120"
      >
        <template #default="{ row }">
          {{ warehouseOf((row as YahooPendingShipment).warehouse) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.shelfNo')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooPendingShipment).shelfNo ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.reconcile.soldPrice')"
        width="110"
        align="right"
      >
        <template #default="{ row }">
          {{ formatYen((row as YahooPendingShipment).soldPrice) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.orderId')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooPendingShipment).orderId ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.shipments.closedAt')"
        width="150"
      >
        <template #default="{ row }">
          {{ formatJstDateTime((row as YahooPendingShipment).closedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('yahoo.auctionId')"
        width="110"
      >
        <template #default="{ row }">
          {{ (row as YahooPendingShipment).auctionId ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        width="110"
      >
        <template #default="{ row }">
          <span
            v-if="(row as YahooPendingShipment).delayed"
            class="kcgl-yahoo-tag is-failed"
          >{{ t('yahoo.shipments.delayed') }}</span>
        </template>
      </el-table-column>
      <el-table-column
        :label="t('admin.actions')"
        width="170"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="goSell(row as YahooPendingShipment)"
          >
            {{ t('yahoo.shipments.goSell') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <AppEmptyState
          compact
          :title="t('yahoo.shipments.empty')"
          :description="t('yahoo.shipments.emptyHint')"
        />
      </template>
    </el-table>
  </template>
</template>

<style scoped>
.yahoo-section-count {
  margin: 0 0 10px;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.yahoo-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.yahoo-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
