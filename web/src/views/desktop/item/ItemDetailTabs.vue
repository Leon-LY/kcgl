<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatJstDate, formatJstDateTime, formatYen } from '@/utils/format'
import { renderMessageJson, toDisplayMessage } from '@/utils/errors'
import { fetchItemLedgers, fetchItemYahooListings } from '@/utils/api'
import type { ItemLedgerRow, ItemResponse, Venue, YahooListingRow } from '@/utils/api'
import { itemListDisplay } from './itemListShared'

/**
 * 商品详情的页签体（M5-①）：基本情報 / 取引履歴 / ヤフー出品三段，从 ItemDetailView
 * 抽出来的（D-152）。页面只把「哪一件、会场表」递进来，本件自持页签选择、两张历史表的
 * 数据与按页签懒加载（原来就写在页面里的那条 watch），以及展示用的取文案函数。
 * 对外：emit openItem（互链跳转到另一件——跳转是页面的事），expose reset（换件时清表回默认页签）。
 * 模板逐字来自原页面，类名一个未改——它们是单测与 e2e 的定位锚点。
 */

const props = defineProps<{
  /** 当前这件；页面用 v-if="item" 保证非空。 */
  item: ItemResponse
  /** 路由里的件号（懒加载两张表时用）。 */
  itemId: number
  /** 会场表：分歧号的展示要用（会场码 → 会场名）。 */
  venues: Venue[]
}>()

const emit = defineEmits<{
  /** 互链跳转：去看另一件（对端不在时不渲染按钮，见模板）。 */
  openItem: [id: number]
}>()

const { t } = useI18n()

// ------------------------------------------------------------- 展示帮助函数

/** 行内文案与标签色走商品一覧那份共用件（itemListShared，D-147）：语义与拆分前逐字一致。 */
const { warehouseOf, stockText, saleText, stockTagClass, saleTagClass } = itemListDisplay(t)

function venueNameOf(target: ItemResponse): string {
  return props.venues.find((v) => v.id === target.venueId)?.name ?? target.venueCode
}

const seqText = computed(() => {
  const current = props.item
  if (current == null) {
    return ''
  }
  return t('items.detail.seqValue', {
    venue: venueNameOf(current),
    month: current.buyMonth,
    seq: `${current.seqPrefix}${current.seqNo}`,
    band: current.priceBandCode,
  })
})

function ledgerTypeText(type: number | null | undefined): string {
  return type == null ? '' : t(`items.ledger.type.${type}`)
}

/**
 * 流水理由（V7，D-130）：系统生成的理由（目前只有盘点差异入账的「棚卸調整 PD…」）
 * 后端同时落了 i18n 键+参数，按当前语言渲染；人工填写的理由没有键，原样显示。
 * 历史行也没有键，回退日文原文。
 */
function ledgerReasonText(row: ItemLedgerRow): string {
  return renderMessageJson(row.reasonCode, row.reasonParams, t, row.reason) || '—'
}

/** from→to 迁移展示：双侧空=无仓维度；单侧空=边界态；双侧有=「A → B」。 */
function rangeText(from: string, to: string): string {
  if (from === '' && to === '') return '—'
  if (from === '') return to
  if (to === '') return from
  return `${from} → ${to}`
}

function whLabel(wh: number | null | undefined): string {
  return wh == null ? '' : t(`common.warehouse.${wh}`)
}

function qtyText(qty: number | null | undefined): string {
  if (qty == null) return '—'
  return qty > 0 ? `+${qty}` : String(qty)
}

/**
 * 互链跳转（D-131）：原路是列表里点行、这里是详情里点号，都走 item-detail
 * （同组件复用，路由参数变化时整体重载）。对端已不在（异常数据）时按钮不渲染。
 * 号在页签体里，那边只报「要去看哪一件」（emit openItem）——跳转是页面的事。
 */
function goToItem(id: number | null | undefined): void {
  if (id == null) {
    return
  }
  emit('openItem', id)
}

// ------------------------------------------------------------- 页签与两张历史表（切标签页时重取）

const activeTab = ref('basic')

const ledgers = ref<ItemLedgerRow[]>([])
const ledgerError = ref('')
let ledgerSeq = 0

async function loadLedgers(): Promise<void> {
  const seq = ++ledgerSeq
  ledgerError.value = ''
  try {
    const data = await fetchItemLedgers(props.itemId)
    if (seq !== ledgerSeq) {
      return
    }
    ledgers.value = data.rows
  } catch (error) {
    if (seq !== ledgerSeq) {
      return
    }
    ledgerError.value = toDisplayMessage(error, t)
  }
}

const listings = ref<YahooListingRow[]>([])
const listingError = ref('')
let listingSeq = 0

async function loadListings(): Promise<void> {
  const seq = ++listingSeq
  listingError.value = ''
  try {
    const data = await fetchItemYahooListings(props.itemId)
    if (seq !== listingSeq) {
      return
    }
    listings.value = data.rows
  } catch (error) {
    if (seq !== listingSeq) {
      return
    }
    listingError.value = toDisplayMessage(error, t)
  }
}

watch(activeTab, (tab) => {
  if (tab === 'ledger') {
    void loadLedgers()
  } else if (tab === 'listing') {
    void loadListings()
  }
})

// ------------------------------------------------------------- 换件复位

/** 路由参数变化时页面调它：清空两张表并回默认页签（原来写在页面的 route watch 里）。 */
function reset(): void {
  activeTab.value = 'basic'
  ledgers.value = []
  listings.value = []
}

defineExpose({ reset })
</script>

<template>
  <el-tabs
    v-model="activeTab"
    class="itemd-tabs"
  >
    <el-tab-pane
      :label="t('items.detail.tab.basic')"
      name="basic"
    >
      <el-descriptions
        :title="t('items.detail.section.basic')"
        :column="2"
        border
        class="itemd-desc"
      >
        <el-descriptions-item :label="t('items.detail.field.itemCode')">
          <span class="itemd-code">{{ item.itemCode }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.venue')">
          {{ venueNameOf(item) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.buyDate')">
          {{ formatJstDate(item.buyDate) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.purchasePrice')">
          {{ formatYen(item.purchasePrice) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.fee')">
          {{ formatYen(item.fee) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.shippingFee')">
          {{ formatYen(item.shippingFee) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.tax')">
          {{ formatYen(item.tax) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.totalCost')">
          {{ formatYen(item.totalCost) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.soldPrice')">
          {{ formatYen(item.soldPrice) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.profit')">
          {{ formatYen(item.profit) }}
        </el-descriptions-item>
      </el-descriptions>

      <el-descriptions
        :title="t('items.detail.section.stock')"
        :column="2"
        border
        class="itemd-desc"
      >
        <el-descriptions-item :label="t('items.detail.field.stockStatus')">
          <span
            class="itemd-tag"
            :class="stockTagClass(item.stockStatus)"
          >{{ stockText(item.stockStatus) }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.saleStatus')">
          <span
            class="itemd-tag"
            :class="saleTagClass(item.saleStatus)"
          >{{ saleText(item.saleStatus) }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.warehouse')">
          {{ warehouseOf(item.warehouse) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.shelfNo')">
          {{ item.shelfNo ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.warehouseInDate')">
          {{ formatJstDate(item.warehouseInDate) }}
        </el-descriptions-item>
      </el-descriptions>

      <el-descriptions
        :title="t('items.detail.section.detail')"
        :column="2"
        border
        class="itemd-desc"
      >
        <el-descriptions-item :label="t('items.detail.field.itemName')">
          {{ item.itemName ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.category')">
          {{ item.category ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.authorKiln')">
          {{ item.authorKiln ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.sizeText')">
          {{ item.sizeText ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.weightG')">
          {{ item.weightG ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.salesChannel')">
          {{ item.salesChannel ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.groupNo')">
          {{ item.groupNo ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.photoDate')">
          <!-- 验收 13：未拍（photo_date NULL）显示「未撮影」而非通用空值「—」——「没拍」与「字段未填」语义不同 -->
          {{ item.photoDate ? formatJstDate(item.photoDate) : t('items.detail.notTaken') }}
        </el-descriptions-item>
        <el-descriptions-item
          :label="t('items.detail.field.remark')"
          :span="2"
        >
          {{ item.remark ?? '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-descriptions
        :title="t('items.detail.section.system')"
        :column="2"
        border
        class="itemd-desc"
      >
        <el-descriptions-item :label="t('items.detail.field.seq')">
          {{ seqText }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('items.detail.field.createdAt')">
          {{ formatJstDateTime(item.createdAt) }}
        </el-descriptions-item>
        <!-- 作废重录互链（D-131）：改从结构化列（re_entry_of/void_re_entry）渲染，
             不再由后端把「再登録元/先」日文标记写进备注——那串日文切语言也不变，
             还会被 Excel 导入导出与编辑弹层原样搬运。 -->
        <el-descriptions-item
          v-if="item.reEntryOfCode"
          :label="t('items.detail.field.reEntryOf')"
        >
          <el-button
            link
            type="primary"
            class="itemd-link"
            @click="goToItem(item.reEntryOf)"
          >
            {{ item.reEntryOfCode }}
          </el-button>
        </el-descriptions-item>
        <el-descriptions-item
          v-if="item.voidReEntryCode"
          :label="t('items.detail.field.voidReEntry')"
        >
          <el-button
            link
            type="primary"
            class="itemd-link"
            @click="goToItem(item.voidReEntry)"
          >
            {{ item.voidReEntryCode }}
          </el-button>
        </el-descriptions-item>
      </el-descriptions>
    </el-tab-pane>

    <el-tab-pane
      :label="t('items.detail.tab.ledger')"
      name="ledger"
    >
      <p
        v-if="ledgerError"
        class="kcgl-error-box"
        role="alert"
      >
        {{ ledgerError }}
        <el-button
          link
          type="primary"
          @click="loadLedgers"
        >
          {{ t('common.reload') }}
        </el-button>
      </p>
      <el-table
        v-else
        :data="ledgers"
        row-key="id"
        class="itemd-table"
      >
        <el-table-column
          :label="t('items.ledger.column.at')"
          width="150"
        >
          <template #default="{ row }">
            {{ formatJstDateTime((row as ItemLedgerRow).createdAt) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.type')"
          width="110"
        >
          <template #default="{ row }">
            {{ ledgerTypeText((row as ItemLedgerRow).txnType) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.warehouse')"
          width="170"
        >
          <template #default="{ row }">
            {{ rangeText(whLabel((row as ItemLedgerRow).whFrom), whLabel((row as ItemLedgerRow).whTo)) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.qty')"
          width="70"
          align="right"
        >
          <template #default="{ row }">
            {{ qtyText((row as ItemLedgerRow).qtyChange) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.stock')"
          width="150"
        >
          <template #default="{ row }">
            {{ rangeText(stockText((row as ItemLedgerRow).stockFrom), stockText((row as ItemLedgerRow).stockTo)) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.sale')"
          width="150"
        >
          <template #default="{ row }">
            {{ rangeText(saleText((row as ItemLedgerRow).saleFrom), saleText((row as ItemLedgerRow).saleTo)) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.reason')"
          min-width="160"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ ledgerReasonText(row as ItemLedgerRow) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.ledger.column.operator')"
          width="120"
        >
          <template #default="{ row }">
            {{ (row as ItemLedgerRow).operatorName ?? '—' }}
          </template>
        </el-table-column>
        <template #empty>
          {{ t('items.ledger.empty') }}
        </template>
      </el-table>
    </el-tab-pane>

    <el-tab-pane
      :label="t('items.detail.tab.listing')"
      name="listing"
    >
      <p
        v-if="listingError"
        class="kcgl-error-box"
        role="alert"
      >
        {{ listingError }}
        <el-button
          link
          type="primary"
          @click="loadListings"
        >
          {{ t('common.reload') }}
        </el-button>
      </p>
      <el-table
        v-else
        :data="listings"
        row-key="id"
        class="itemd-table"
      >
        <el-table-column
          :label="t('items.listing.column.orderId')"
          min-width="110"
        >
          <template #default="{ row }">
            {{ (row as YahooListingRow).orderId ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.listing.column.auctionId')"
          min-width="120"
        >
          <template #default="{ row }">
            {{ (row as YahooListingRow).yahooAuctionId ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.listing.column.listPrice')"
          width="110"
          align="right"
        >
          <template #default="{ row }">
            {{ formatYen((row as YahooListingRow).listPrice) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.listing.column.soldPrice')"
          width="110"
          align="right"
        >
          <template #default="{ row }">
            {{ formatYen((row as YahooListingRow).soldPrice) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.listing.column.status')"
          width="110"
        >
          <template #default="{ row }">
            <span
              class="itemd-tag"
              :class="saleTagClass((row as YahooListingRow).status)"
            >{{ saleText((row as YahooListingRow).status) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.listing.column.listedAt')"
          width="150"
        >
          <template #default="{ row }">
            {{ formatJstDateTime((row as YahooListingRow).listedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('items.listing.column.closedAt')"
          width="150"
        >
          <template #default="{ row }">
            {{ formatJstDateTime((row as YahooListingRow).closedAt) }}
          </template>
        </el-table-column>
        <template #empty>
          {{ t('items.listing.empty') }}
        </template>
      </el-table>
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.itemd-desc {
  margin-bottom: 20px;
}

.itemd-table {
  width: 100%;
}
</style>
