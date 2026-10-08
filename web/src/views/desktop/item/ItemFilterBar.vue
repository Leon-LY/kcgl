<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Venue } from '@/utils/api'
import { ALL, emptyFilters, type FilterValue, type ItemFilterState } from './itemFilters'

/**
 * 商品一覧の検索・絞り込み条（D-142）。从 ItemsView 抽出来的。
 *
 * 它整块自洽：八个控件各自往条件对象里写一个字段，改完统一发一次 search，
 * 父组件只负责"发检索"这一件事。右侧的一括削除/一括入出力入口不是筛选条件
 * （挤进筛选行就等于暗示它们会影响当前查询结果），故不在这里，由父组件经
 * #aside 插槽落回同一行的第 2 列——`.items-toolbar` 的栅格定义在这边，
 * 插槽内容才排得进去。
 */
const filters = defineModel<ItemFilterState>({ required: true })

defineProps<{ venues: Venue[] }>()

const emit = defineEmits<{ search: [] }>()

const { t } = useI18n()

/** 改一项就换一个新对象（不可变约定，不原地改 prop）。 */
function patch(next: Partial<ItemFilterState>): void {
  filters.value = { ...filters.value, ...next }
}

/**
 * 每个控件一个可写 computed：模板里的写法与拆分前**逐字一致**（`v-model="warehouse"`），
 * 写回却走上面的 patch 换成新对象。这样 Element Plus 控件的绑定一行未改，行为风险为零。
 *
 * `@change` 仍是各自的检索触发点，与拆分前同：el-select / el-date-picker 的 change
 * 只在**用户选择**时发，程序性改 model-value（条件をクリア、首载消费 URL）不会触发
 * ——正是要的语义，否则清条件会被触发八次、首载一进页面就多发一次检索。
 */
const kw = computed({ get: () => filters.value.kw, set: (v: string) => patch({ kw: v }) })
const warehouse = computed({
  get: (): FilterValue => filters.value.warehouse,
  set: (v: FilterValue) => patch({ warehouse: v }),
})
const stockStatus = computed({
  get: (): FilterValue => filters.value.stockStatus,
  set: (v: FilterValue) => patch({ stockStatus: v }),
})
const saleStatus = computed({
  get: (): FilterValue => filters.value.saleStatus,
  set: (v: FilterValue) => patch({ saleStatus: v }),
})
const venueId = computed({
  get: (): FilterValue => filters.value.venueId,
  set: (v: FilterValue) => patch({ venueId: v }),
})
const buyDateFrom = computed({
  get: (): string | null => filters.value.buyDateFrom,
  set: (v: string | null) => patch({ buyDateFrom: v }),
})
const buyDateTo = computed({
  get: (): string | null => filters.value.buyDateTo,
  set: (v: string | null) => patch({ buyDateTo: v }),
})
const warnLevel = computed({
  get: (): FilterValue => filters.value.warnLevel,
  set: (v: FilterValue) => patch({ warnLevel: v }),
})

/** 条件をクリア：回默认态并重检索（页码由父组件的 search 归 1）。 */
function clear(): void {
  filters.value = emptyFilters()
  emit('search')
}
</script>

<template>
  <div class="items-toolbar">
    <div class="items-search">
      <el-input
        v-model="kw"
        :placeholder="t('items.searchPlaceholder')"
        clearable
        @keyup.enter="emit('search')"
        @clear="emit('search')"
      />
      <el-button
        type="primary"
        @click="emit('search')"
      >
        {{ t('items.search') }}
      </el-button>
    </div>
    <div class="items-filters">
      <el-select
        v-model="warehouse"
        class="items-filter-wh"
        @change="emit('search')"
      >
        <el-option
          :label="t('items.warehouseAll')"
          :value="ALL"
        />
        <el-option
          :label="t('common.warehouse.1')"
          :value="1"
        />
        <el-option
          :label="t('common.warehouse.2')"
          :value="2"
        />
      </el-select>
      <el-select
        v-model="stockStatus"
        class="items-filter"
        @change="emit('search')"
      >
        <el-option
          :label="t('items.filterAll')"
          :value="ALL"
        />
        <el-option
          :label="t('scan.stock.0')"
          :value="0"
        />
        <el-option
          :label="t('scan.stock.1')"
          :value="1"
        />
        <el-option
          :label="t('scan.stock.2')"
          :value="2"
        />
      </el-select>
      <el-select
        v-model="saleStatus"
        class="items-filter"
        @change="emit('search')"
      >
        <el-option
          :label="t('items.filterAll')"
          :value="ALL"
        />
        <el-option
          :label="t('scan.sale.0')"
          :value="0"
        />
        <el-option
          :label="t('scan.sale.1')"
          :value="1"
        />
        <el-option
          :label="t('scan.sale.2')"
          :value="2"
        />
        <el-option
          :label="t('scan.sale.3')"
          :value="3"
        />
      </el-select>
      <el-select
        v-model="venueId"
        class="items-filter-venue"
        filterable
        @change="emit('search')"
      >
        <el-option
          :label="t('items.venueAll')"
          :value="ALL"
        />
        <el-option
          v-for="venue in venues"
          :key="venue.id"
          :label="venue.name"
          :value="venue.id"
        />
      </el-select>
      <!-- 区间两端与分隔符同一组：换行时整组换行，不让「〜」被折到下一行孤悬 -->
      <span class="items-filter-range">
        <el-date-picker
          v-model="buyDateFrom"
          type="date"
          :placeholder="t('items.buyDateFrom')"
          value-format="YYYY-MM-DD"
          class="items-filter-date"
          @change="emit('search')"
        />
        <span class="items-range-sep">〜</span>
        <el-date-picker
          v-model="buyDateTo"
          type="date"
          :placeholder="t('items.buyDateTo')"
          value-format="YYYY-MM-DD"
          class="items-filter-date"
          @change="emit('search')"
        />
      </span>
      <el-select
        v-model="warnLevel"
        class="items-filter-slow"
        @change="emit('search')"
      >
        <el-option
          :label="t('items.slowAll')"
          :value="ALL"
        />
        <el-option
          :label="t('items.slowYellow')"
          :value="1"
        />
        <el-option
          :label="t('items.slowRed')"
          :value="2"
        />
      </el-select>
      <el-button
        link
        type="primary"
        @click="clear"
      >
        {{ t('items.clear') }}
      </el-button>
    </div>
    <p class="items-hint">
      {{ t('items.searchHint') }}
    </p>
    <slot name="aside" />
  </div>
</template>

<style scoped>
.items-toolbar {
  display: grid;
  /* 左=搜索/筛选/提示各占一行，右=一括入出力入口（父组件经 #aside 插进第 2 列）。
     栅格定义留在这边而不是父组件：插槽内容要成为本容器的栅格子项才排得进去。 */
  grid-template-columns: 1fr auto;
  align-items: start;
  gap: var(--kcgl-space-3);
  margin-bottom: var(--kcgl-space-3);
}

.items-search {
  display: flex;
  gap: var(--kcgl-space-2);
  max-width: 520px;
}

.items-filters {
  display: flex;
  align-items: center;
  gap: var(--kcgl-space-2);
  /* 行间距必须显式给：只有 column-gap 时换行后的控件会贴着上一行，看着像挤成一团 */
  row-gap: var(--kcgl-space-2);
  flex-wrap: wrap;
}

/* 日期区间整组换行（两端 + 分隔符是一个语义单位） */
.items-filter-range {
  display: inline-flex;
  align-items: center;
  gap: var(--kcgl-space-2);
}

.items-filter-wh {
  width: 150px;
}

.items-filter {
  width: 130px;
}

.items-filter-venue {
  width: 170px;
}

.items-filter-date {
  width: 150px;
}

.items-filter-slow {
  width: 150px;
}

.items-range-sep {
  color: var(--kcgl-color-text-faint);
}

.items-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-faint);
}
</style>
