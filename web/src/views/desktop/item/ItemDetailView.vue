<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { formatJstDate, formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { ApiError } from '@/utils/api'
import {
  deleteItem,
  fetchItem,
  fetchItemLedgers,
  fetchItemYahooListings,
  fetchVenues,
  updateItem,
  voidItem,
} from '@/utils/api'
import type {
  ItemLedgerRow,
  ItemResponse,
  Venue,
  YahooListingRow,
} from '@/utils/api'
import ItemPhotos from './ItemPhotos.vue'
import ItemAdjustDialog from './ItemAdjustDialog.vue'

/**
 * 商品详情（M5-①）：全字段四段式详情 + 取引履歴/ヤフー出品两历史表。
 * 分歧徽标=号内快照 vs 现值三维度（会场码/年月/档位字母，D-063 snapshot
 * 单模式的界面落点）；编辑=全量 PUT（可选字段 null=清空，409000 重读后
 * 保留输入再提交）；作废→跳录入页重录（?reEntry=）；删除→回列表回收站。
 * 本页不接 SSE 失效（D-052 B：单件页操作后本地重取已覆盖，他人改动由
 * 切标签页时的重取兜底）。
 */

const { t } = useI18n()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const canEdit = computed(() => auth.me != null && auth.me.role <= 2)
const isAdmin = computed(() => auth.me != null && auth.me.role === 1)

// ------------------------------------------------------------- 商品本体

function currentItemId(): number {
  const raw = route.params.id
  return Number(Array.isArray(raw) ? raw[0] : raw)
}

const item = ref<ItemResponse | null>(null)
const venues = ref<Venue[]>([])
const loading = ref(true)
const notFound = ref(false)
const loadFailed = ref(false)
let itemSeq = 0

async function loadItem(): Promise<void> {
  const seq = ++itemSeq
  loading.value = true
  notFound.value = false
  loadFailed.value = false
  try {
    const data = await fetchItem(currentItemId())
    if (seq !== itemSeq) {
      return
    }
    item.value = data
  } catch (error) {
    if (seq !== itemSeq) {
      return
    }
    if (error instanceof ApiError && error.code === 404001) {
      notFound.value = true // 含软删件（回收站态不经此页）
    } else {
      loadFailed.value = true
    }
  } finally {
    if (seq === itemSeq) {
      loading.value = false
    }
  }
}

/**
 * 戻る（C1）：有来路即原路返回——列表把筛选/页码投影进 URL（ItemsView#projectQuery），
 * 退回时自然带回来；从台帳等他页下钻则回他页。深链/直开无来路时退回商品一覧。
 * 浏览器后退键不受此影响（它走会话历史，本来就能回来）。
 */
function goBack(): void {
  if (router.options.history.state.back != null) {
    router.back()
    return
  }
  void router.push({ name: 'items' })
}

function venueNameOf(target: ItemResponse): string {
  return venues.value.find((v) => v.id === target.venueId)?.name ?? target.venueCode
}

// ------------------------------------------------------------- 分歧徽标（号内快照 vs 现值）

const ITEM_CODE_PATTERN = /^([A-Z]{2})(1[0-2]|[1-9])-([A-Z]{1,3})([1-9][0-9]?)([A-Z])?$/

const diverged = computed(() => {
  const current = item.value
  if (current == null) {
    return false
  }
  // ① 会场：号内 venueCode vs 现会场代码（会场表查不到该维度跳过，如刚被停用删档）
  const venue = venues.value.find((v) => v.id === current.venueId)
  if (venue != null && venue.code !== current.venueCode) {
    return true
  }
  // ② 月：号内 buyMonth vs 现落札日（D-068 去年代号后号内仅含月，跨年连续）
  if (current.buyMonth !== Number(current.buyDate.slice(5, 7))) {
    return true
  }
  // ③ 档位：管理号末位字母 vs 现价格档（服务端每次 PUT 重推导 priceBandCode）
  const match = ITEM_CODE_PATTERN.exec(current.itemCode)
  return match != null && match[5] != null && match[5] !== current.priceBandCode
})

const seqText = computed(() => {
  const current = item.value
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

// ------------------------------------------------------------- 历史两表（切标签页时重取）

const activeTab = ref('basic')

const ledgers = ref<ItemLedgerRow[]>([])
const ledgerError = ref('')
let ledgerSeq = 0

async function loadLedgers(): Promise<void> {
  const seq = ++ledgerSeq
  ledgerError.value = ''
  try {
    const data = await fetchItemLedgers(currentItemId())
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
    const data = await fetchItemYahooListings(currentItemId())
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

// ------------------------------------------------------------- 展示帮助函数

// el-table-column 渲染列时以 {row:{}} 探测嵌套列（TableColumnRenderer），
// 动态 i18n key 必须空值兜底——否则空数据页也刷 missing-key 告警（D-056）
function warehouseOf(target: number | null | undefined): string {
  return target == null ? '—' : t(`common.warehouse.${target}`)
}

function stockText(status: number | null | undefined): string {
  return status == null ? '' : t(`scan.stock.${status}`)
}

function saleText(status: number | null | undefined): string {
  return status == null ? '' : t(`scan.sale.${status}`)
}

function ledgerTypeText(type: number | null | undefined): string {
  return type == null ? '' : t(`items.ledger.type.${type}`)
}

function stockTagClass(status: number | null | undefined): string {
  return status === 1 ? 'is-success' : 'is-neutral'
}

function saleTagClass(status: number | null | undefined): string {
  return status === 1 ? 'is-warning' : status === 2 ? 'is-success' : 'is-neutral'
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

// ------------------------------------------------------------- 编辑弹层（E+，全量 PUT）

interface EditForm {
  venueId: number | null
  buyDate: string | null
  purchasePrice: number | undefined
  warehouse: number | null
  photoDate: string | null
  fee: number | undefined
  shippingFee: number | undefined
  tax: number | undefined
  shelfNo: string
  warehouseInDate: string | null
  groupNo: string
  remark: string
  itemName: string
  category: string
  authorKiln: string
  sizeText: string
  weightG: number | undefined
  salesChannel: string
}

const editOpen = ref(false)
const editBusy = ref(false)
const editError = ref('')
const editForm = ref<EditForm | null>(null)

/** 编辑下拉只给启用会场；现值若已停用则保留（快照可查不可新选）。 */
const editVenues = computed(() =>
  venues.value.filter((v) => v.enabled || v.id === item.value?.venueId))

function openEdit(): void {
  const current = item.value
  if (current == null) {
    return
  }
  editForm.value = {
    venueId: current.venueId,
    buyDate: current.buyDate,
    purchasePrice: current.purchasePrice,
    warehouse: current.warehouse,
    photoDate: current.photoDate,
    fee: current.fee ?? undefined,
    shippingFee: current.shippingFee ?? undefined,
    tax: current.tax ?? undefined,
    shelfNo: current.shelfNo ?? '',
    warehouseInDate: current.warehouseInDate,
    groupNo: current.groupNo ?? '',
    remark: current.remark ?? '',
    itemName: current.itemName ?? '',
    category: current.category ?? '',
    authorKiln: current.authorKiln ?? '',
    sizeText: current.sizeText ?? '',
    weightG: current.weightG ?? undefined,
    salesChannel: current.salesChannel ?? '',
  }
  editError.value = ''
  editOpen.value = true
}

function validateEdit(): string {
  const form = editForm.value
  if (form == null) {
    return ''
  }
  if (form.venueId == null || form.buyDate == null || form.purchasePrice == null || form.warehouse == null) {
    return t('items.edit.validationRequired')
  }
  const price = form.purchasePrice
  if (!Number.isInteger(price) || price < 1 || price > 99_999_999) {
    return t('items.edit.validationPrice')
  }
  return ''
}

function trimmed(value: string): string | null {
  const result = value.trim()
  return result === '' ? null : result
}

async function onEditSubmit(): Promise<void> {
  const current = item.value
  const form = editForm.value
  if (current == null || form == null || editBusy.value) {
    return
  }
  editError.value = validateEdit()
  if (editError.value !== '') {
    return
  }
  editBusy.value = true
  try {
    await updateItem(current.id, {
      version: current.version,
      venueId: form.venueId!,
      buyDate: form.buyDate!,
      purchasePrice: form.purchasePrice!,
      warehouse: form.warehouse!,
      photoDate: form.photoDate ?? null,
      fee: form.fee ?? null,
      shippingFee: form.shippingFee ?? null,
      tax: form.tax ?? null,
      shelfNo: trimmed(form.shelfNo),
      warehouseInDate: form.warehouseInDate ?? null,
      groupNo: trimmed(form.groupNo),
      remark: trimmed(form.remark),
      itemName: trimmed(form.itemName),
      category: trimmed(form.category),
      authorKiln: trimmed(form.authorKiln),
      sizeText: trimmed(form.sizeText),
      weightG: form.weightG ?? null,
      salesChannel: trimmed(form.salesChannel),
    })
    editOpen.value = false
    await loadItem()
  } catch (error) {
    if (error instanceof ApiError && error.code === 409000) {
      // 乐观锁冲突：重读刷新 version（表单输入保留，改完可直接再提交）
      editError.value = t('items.edit.versionConflict')
      await loadItem()
    } else {
      editError.value = toDisplayMessage(error, t)
    }
  } finally {
    editBusy.value = false
  }
}

// ------------------------------------------------------------- 作废并重录弹层（E+）

const voidOpen = ref(false)
const voidBusy = ref(false)
const voidError = ref('')
const voidReason = ref('')

function openVoid(): void {
  voidReason.value = ''
  voidError.value = ''
  voidOpen.value = true
}

async function onVoidSubmit(): Promise<void> {
  const current = item.value
  if (current == null || voidBusy.value) {
    return
  }
  if (voidReason.value.trim() === '') {
    voidError.value = t('entry.voidReasonRequired')
    return
  }
  voidBusy.value = true
  voidError.value = ''
  try {
    await voidItem(current.id, crypto.randomUUID(), voidReason.value.trim())
    // 成功即跳录入页（携带重录源）；页面卸载，无需复位弹层状态
    void router.push({ name: 'entry', query: { reEntry: String(current.id) } })
  } catch (error) {
    voidError.value = toDisplayMessage(error, t)
    voidBusy.value = false
  }
}

// ------------------------------------------------------------- 删除弹层（仅管理员）

const deleteOpen = ref(false)
const deleteBusy = ref(false)
const deleteError = ref('')
const deleteReason = ref('')

function openDelete(): void {
  deleteReason.value = ''
  deleteError.value = ''
  deleteOpen.value = true
}

async function onDeleteSubmit(): Promise<void> {
  const current = item.value
  if (current == null || deleteBusy.value) {
    return
  }
  deleteBusy.value = true
  deleteError.value = ''
  try {
    await deleteItem(
      current.id,
      crypto.randomUUID(),
      deleteReason.value.trim() === '' ? undefined : deleteReason.value.trim(),
    )
    void router.push({ name: 'items' }) // 回列表（回收站标签可见）
  } catch (error) {
    deleteError.value = toDisplayMessage(error, t)
    deleteBusy.value = false
  }
}

// ------------------------------------------------------------- 手工修正弹层（D4，仅管理员）

/** 弹层自持表单与提交（ItemAdjustDialog），父组件只开合它并在成功后重载详情。 */
const adjustOpen = ref(false)

// ------------------------------------------------------------- 装配

function reloadAll(): void {
  void loadItem()
}

onMounted(() => {
  void fetchVenues(false)
    .then((data) => {
      venues.value = data
    })
    .catch(() => undefined) // 会场表失败不阻断详情（分歧徽标①自动跳过）
  reloadAll()
})

// 同组件复用跳转（/items/5 → /items/12）时整体重载
watch(() => route.params.id, (next, prev) => {
  if (next !== prev && route.name === 'item-detail') {
    ledgers.value = []
    listings.value = []
    activeTab.value = 'basic'
    reloadAll()
  }
})
</script>

<template>
  <section class="itemd-view">
    <div class="admin-header">
      <div>
        <el-button
          link
          type="primary"
          class="itemd-back"
          @click="goBack"
        >
          ← {{ t('items.detail.back') }}
        </el-button>
        <h1
          v-if="item"
          class="admin-title itemd-title"
        >
          <span class="itemd-code">{{ item.itemCode }}</span>
          <span
            class="itemd-tag"
            :class="stockTagClass(item.stockStatus)"
          >{{ stockText(item.stockStatus) }}</span>
          <span
            class="itemd-tag"
            :class="saleTagClass(item.saleStatus)"
          >{{ saleText(item.saleStatus) }}</span>
          <span
            v-if="item.voided"
            class="itemd-tag is-danger"
          >{{ t('scan.voidedTag') }}</span>
        </h1>
        <p
          v-if="item && diverged"
          class="itemd-divergence"
        >
          {{ t('items.detail.divergence') }}
        </p>
      </div>
      <div
        v-if="item && !item.voided"
        class="itemd-actions"
      >
        <el-button
          v-if="canEdit"
          type="primary"
          @click="openEdit"
        >
          {{ t('items.detail.edit') }}
        </el-button>
        <el-button
          v-if="canEdit"
          @click="openVoid"
        >
          {{ t('entry.voidButton') }}
        </el-button>
        <el-button
          v-if="isAdmin"
          @click="adjustOpen = true"
        >
          {{ t('items.detail.adjust') }}
        </el-button>
        <el-button
          v-if="isAdmin"
          type="danger"
          plain
          @click="openDelete"
        >
          {{ t('items.detail.delete') }}
        </el-button>
      </div>
    </div>

    <p
      v-if="item && item.voided"
      class="kcgl-info-box"
    >
      {{ item.voidReason
        ? t('items.detail.voidedBanner', { reason: item.voidReason })
        : t('items.detail.voidedNoReasonBanner') }}
    </p>

    <div
      v-if="notFound"
      class="kcgl-card itemd-state"
    >
      <p>{{ t('items.detail.notFound') }}</p>
      <el-button
        type="primary"
        @click="goBack"
      >
        {{ t('items.detail.back') }}
      </el-button>
    </div>
    <div
      v-else-if="loadFailed"
      class="kcgl-card itemd-state"
    >
      <p>{{ t('items.detail.loadFailed') }}</p>
      <el-button
        type="primary"
        @click="reloadAll"
      >
        {{ t('common.reload') }}
      </el-button>
    </div>

    <div
      v-else-if="item"
      v-loading="loading"
      class="kcgl-card itemd-body"
    >
      <ItemPhotos
        :item-id="currentItemId()"
        :can-edit="canEdit"
      />

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
                {{ (row as ItemLedgerRow).reason ?? '—' }}
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
    </div>

    <el-dialog
      v-model="editOpen"
      :title="t('items.edit.title')"
      width="640px"
      :close-on-click-modal="!editBusy"
    >
      <div
        v-if="editForm != null"
        class="itemd-form"
      >
        <p class="itemd-dialog-note">
          {{ t('items.edit.note') }}
        </p>
        <div class="itemd-edit-grid">
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.venue') }}</span>
            <el-select
              v-model="editForm.venueId"
              filterable
              :disabled="editBusy"
            >
              <el-option
                v-for="venue in editVenues"
                :key="venue.id"
                :label="venue.name"
                :value="venue.id"
              />
            </el-select>
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.buyDate') }}</span>
            <el-date-picker
              v-model="editForm.buyDate"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.purchasePrice') }}</span>
            <el-input-number
              v-model="editForm.purchasePrice"
              :min="1"
              :max="99999999"
              :step="1"
              :precision="0"
              :controls="false"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.warehouse') }}</span>
            <el-select
              v-model="editForm.warehouse"
              :disabled="editBusy || item?.stockStatus !== 0"
            >
              <el-option
                :label="t('common.warehouse.1')"
                :value="1"
              />
              <el-option
                :label="t('common.warehouse.2')"
                :value="2"
              />
            </el-select>
            <span class="itemd-field-hint">{{ t('items.edit.warehouseHint') }}</span>
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.fee') }}</span>
            <el-input-number
              v-model="editForm.fee"
              :min="0"
              :max="99999999"
              :precision="0"
              :controls="false"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.shippingFee') }}</span>
            <el-input-number
              v-model="editForm.shippingFee"
              :min="0"
              :max="99999999"
              :precision="0"
              :controls="false"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.tax') }}</span>
            <el-input-number
              v-model="editForm.tax"
              :min="0"
              :max="99999999"
              :precision="0"
              :controls="false"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.photoDate') }}</span>
            <el-date-picker
              v-model="editForm.photoDate"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.shelfNo') }}</span>
            <el-input
              v-model="editForm.shelfNo"
              maxlength="32"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.warehouseInDate') }}</span>
            <el-date-picker
              v-model="editForm.warehouseInDate"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.groupNo') }}</span>
            <el-input
              v-model="editForm.groupNo"
              maxlength="32"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.weightG') }}</span>
            <el-input-number
              v-model="editForm.weightG"
              :min="1"
              :max="2000000"
              :precision="0"
              :controls="false"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.itemName') }}</span>
            <el-input
              v-model="editForm.itemName"
              maxlength="200"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.category') }}</span>
            <el-input
              v-model="editForm.category"
              maxlength="64"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.authorKiln') }}</span>
            <el-input
              v-model="editForm.authorKiln"
              maxlength="128"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.sizeText') }}</span>
            <el-input
              v-model="editForm.sizeText"
              maxlength="64"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field">
            <span class="itemd-field-label">{{ t('items.detail.field.salesChannel') }}</span>
            <el-input
              v-model="editForm.salesChannel"
              maxlength="32"
              :disabled="editBusy"
            />
          </label>
          <label class="itemd-field itemd-field-wide">
            <span class="itemd-field-label">{{ t('items.detail.field.remark') }}</span>
            <el-input
              v-model="editForm.remark"
              type="textarea"
              :rows="3"
              maxlength="500"
              :disabled="editBusy"
            />
          </label>
        </div>
        <p
          v-if="editError"
          class="itemd-form-error"
          role="alert"
        >
          {{ editError }}
        </p>
      </div>
      <template #footer>
        <el-button
          :disabled="editBusy"
          @click="editOpen = false"
        >
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="editBusy"
          @click="onEditSubmit"
        >
          {{ t('items.edit.save') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="voidOpen"
      :title="t('entry.voidTitle')"
      width="440px"
      :close-on-click-modal="!voidBusy"
    >
      <div class="itemd-form">
        <p class="itemd-dialog-note">
          {{ t('entry.voidNote') }}
        </p>
        <label class="itemd-field">
          <span class="itemd-field-label">{{ t('entry.voidReasonLabel') }}</span>
          <el-input
            v-model="voidReason"
            type="textarea"
            :rows="2"
            maxlength="255"
            :placeholder="t('entry.voidReasonPlaceholder')"
            :disabled="voidBusy"
          />
        </label>
        <p class="itemd-warn">
          {{ t('entry.voidLabelWarn') }}
        </p>
        <p
          v-if="voidError"
          class="itemd-form-error"
          role="alert"
        >
          {{ voidError }}
        </p>
      </div>
      <template #footer>
        <el-button
          :disabled="voidBusy"
          @click="voidOpen = false"
        >
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="voidBusy"
          @click="onVoidSubmit"
        >
          {{ t('entry.voidConfirm') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="deleteOpen"
      :title="t('items.detail.deleteTitle')"
      width="440px"
      :close-on-click-modal="!deleteBusy"
    >
      <div class="itemd-form">
        <p class="itemd-dialog-note">
          {{ t('items.detail.deleteNote') }}
        </p>
        <label class="itemd-field">
          <span class="itemd-field-label">{{ t('items.detail.deleteReasonLabel') }}</span>
          <el-input
            v-model="deleteReason"
            type="textarea"
            :rows="2"
            maxlength="255"
            :disabled="deleteBusy"
          />
        </label>
        <p
          v-if="deleteError"
          class="itemd-form-error"
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
          :loading="deleteBusy"
          @click="onDeleteSubmit"
        >
          {{ t('items.detail.deleteConfirm') }}
        </el-button>
      </template>
    </el-dialog>

    <ItemAdjustDialog
      v-if="item"
      v-model="adjustOpen"
      :item="item"
      @adjusted="reloadAll()"
    />
  </section>
</template>

<style scoped>
.itemd-view {
  display: grid;
  gap: 16px;
}

.itemd-back {
  padding: 0 0 6px;
  margin-bottom: 4px;
  height: auto;
}

.itemd-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.itemd-code {
  font-weight: 600;
  letter-spacing: 0.02em;
}

.itemd-divergence {
  margin: 6px 0 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-warning);
}

.itemd-actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.itemd-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 40px 24px;
  text-align: center;
}

.itemd-state p {
  margin: 0;
  color: var(--kcgl-color-text-sub);
}

.itemd-body {
  padding: 20px 24px;
}

.itemd-desc {
  margin-bottom: 20px;
}

.itemd-table {
  width: 100%;
}

.itemd-tag {
  display: inline-block;
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  font-size: 0.75rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.itemd-tag.is-success {
  border-color: var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.itemd-tag.is-warning {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.itemd-tag.is-danger {
  border-color: var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
}

.itemd-tag.is-neutral {
  border-color: var(--kcgl-color-border);
  background: var(--kcgl-color-bg);
  color: var(--kcgl-color-text-faint);
}

.itemd-form {
  display: grid;
  gap: 12px;
}

.itemd-dialog-note {
  margin: 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.itemd-warn {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-warning);
}

.itemd-edit-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 16px;
}

.itemd-field {
  display: grid;
  gap: 6px;
}

.itemd-field-wide {
  grid-column: 1 / -1;
}

.itemd-field-label {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.itemd-field-hint {
  font-size: 0.75rem;
  color: var(--kcgl-color-text-faint);
}

.itemd-form-error {
  margin: 0;
  padding: 8px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.85rem;
}
</style>
