<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { ApiError, fetchItem, fetchVenues } from '@/utils/api'
import type { ItemResponse, Venue } from '@/utils/api'
import { itemListDisplay } from './itemListShared'
import ItemPhotos from './ItemPhotos.vue'
import ItemDetailTabs from './ItemDetailTabs.vue'
import ItemAdjustDialog from './ItemAdjustDialog.vue'
import ItemEditDialog from './ItemEditDialog.vue'
import ItemVoidDialog from './ItemVoidDialog.vue'
import ItemDeleteDialog from './ItemDeleteDialog.vue'

/**
 * 商品详情（M5-①）：页头（管理号 + 状态片 + 分歧提示 + 四个操作按钮）、商品本体与装载，
 * 以及三个操作弹层；详细字段与两张历史表在页签体 ItemDetailTabs 里（D-152）。
 * 分歧徽标=号内快照 vs 现值三维度（会场码/年月/档位字母，D-063 snapshot
 * 单模式的界面落点）；编辑=全量 PUT（可选字段 null=清空，409000 重读后
 * 保留输入再提交）；作废→跳录入页重录（?reEntry=）；删除→回列表回收站。
 * 本页不接 SSE 失效（D-052 B：单件页操作后本地重取已覆盖；他人改动由
 * 切标签页时的重取兜底——懒加载那条 watch 现在归页签体）。
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

// ------------------------------------------------------------- 展示帮助函数

/** 行内文案与标签色走商品一覧那份共用件（itemListShared，D-147）：语义与拆分前逐字一致。 */
const { stockText, saleText, stockTagClass, saleTagClass } = itemListDisplay(t)

/**
 * 互链跳转（D-131）：原路是列表里点行、这里是详情里点号，都走 item-detail
 * （同组件复用，路由参数变化时整体重载）。对端已不在（异常数据）时按钮不渲染。
 * 页签体里只报「要去看哪一件」（emit openItem）——跳转落在这里。
 */
function goToItem(id: number | null | undefined): void {
  if (id == null) {
    return
  }
  void router.push({ name: 'item-detail', params: { id } })
}

// ------------------------------------------------------------- 三个操作弹层（各自独立组件）

/**
 * 弹层自持表单与提交（ItemEditDialog/ItemVoidDialog/ItemDeleteDialog，D-119 抽取）；
 * 父组件只开合它们，并在成功后重载详情或跳转。样式走全局 .kcgl-* 基元：
 * scoped 样式不跨组件边界，子组件里 .itemd-* 会静默失效。
 */
const editOpen = ref(false)
const voidOpen = ref(false)
const deleteOpen = ref(false)

/** 作废成功 → 跳录入页（携 ?reEntry= 作重录源）；页面卸载，无弹层状态要复位。 */
function onVoided(): void {
  const current = item.value
  if (current == null) {
    return
  }
  void router.push({ name: 'entry', query: { reEntry: String(current.id) } })
}

/** 软删成功 → 回商品一覧（回收站标签里可复原）。 */
function onDeleted(): void {
  void router.push({ name: 'items' })
}

// ------------------------------------------------------------- 手工修正弹层（D4，仅管理员）

/** 弹层自持表单与提交（ItemAdjustDialog），父组件只开合它并在成功后重载详情。 */
const adjustOpen = ref(false)

// ------------------------------------------------------------- 装配

/** 页签体（ItemDetailTabs）向外只暴露 reset：换件时清空两张历史表并回默认页签。 */
const tabsRef = ref<InstanceType<typeof ItemDetailTabs> | null>(null)

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

// 同组件复用跳转（/items/5 → /items/12）时整体重载；页签体的两张历史表也随换件清空
watch(() => route.params.id, (next, prev) => {
  if (next !== prev && route.name === 'item-detail') {
    tabsRef.value?.reset()
    reloadAll()
  }
})
</script>

<template>
  <section class="itemd-view">
    <!-- 返回走 history 而非具名跳转（:on-back="goBack"）：列表的筛选与页码在 query 里，
         push 一个具名路由会把它们丢掉（D-112/C1）。标题不是纯文本（管理号 + 状态片），
         故用 title 插槽；分岐提示自带警示色，用 description 插槽而非 description 属性。 -->
    <AppPageHeader
      :back-label="t('items.detail.back')"
      :on-back="goBack"
    >
      <template #title>
        <span
          v-if="item"
          class="itemd-title"
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
        </span>
      </template>
      <template
        v-if="item && diverged"
        #description
      >
        <p class="itemd-divergence">
          {{ t('items.detail.divergence') }}
        </p>
      </template>
      <template #actions>
        <template v-if="item && !item.voided">
          <el-button
            v-if="canEdit"
            type="primary"
            @click="editOpen = true"
          >
            {{ t('items.detail.edit') }}
          </el-button>
          <el-button
            v-if="canEdit"
            @click="voidOpen = true"
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
            @click="deleteOpen = true"
          >
            {{ t('items.detail.delete') }}
          </el-button>
        </template>
      </template>
    </AppPageHeader>

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

      <ItemDetailTabs
        ref="tabsRef"
        :item="item"
        :item-id="currentItemId()"
        :venues="venues"
        @open-item="goToItem"
      />
    </div>

    <ItemEditDialog
      v-if="item"
      v-model="editOpen"
      :item="item"
      :venues="venues"
      @saved="reloadAll()"
      @stale="loadItem()"
    />

    <ItemVoidDialog
      v-if="item"
      v-model="voidOpen"
      :item="item"
      @voided="onVoided()"
    />

    <ItemDeleteDialog
      v-if="item"
      v-model="deleteOpen"
      :item="item"
      @deleted="onDeleted()"
    />

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

/* 标题内容（管理号 + 状态片）的排布；字号字重由 AppPageHeader 的 .page-header-title 定 */
.itemd-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.itemd-divergence {
  margin: 6px 0 0;
  font-size: 0.85rem;
  color: var(--kcgl-color-warning);
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

</style>
