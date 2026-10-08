<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import AppPageHeader from '@/components/AppPageHeader.vue'
import ItemListTab from './ItemListTab.vue'
import ItemRecycleTab from './ItemRecycleTab.vue'

/**
 * 商品一覧（M5-①，D-061）：两个页签的**壳**。主列表（筛选/表格/行内动作/一括削除/
 * 三个弹层）在 ItemListTab，削除済み商品（软删恢复）在 ItemRecycleTab（D-141）。
 * 本文件只剩页签状态与两者的装配（D-147）。
 *
 * 两页签之间只有一条双向依赖，且只经由这里：删件 → 回收站那份数据陈旧；復元件 →
 * 主列表陈旧。挂载首载与 SSE 失效（他人操作）也在这里分发，于是「什么时候重取谁」
 * 全仓只有这一处可读。
 */
const { t } = useI18n()
const auth = useAuthStore()

const isAdmin = computed(() => auth.me != null && auth.me.role === 1)

const activeTab = ref('list')

const listTab = ref<InstanceType<typeof ItemListTab> | null>(null)
const recycleTab = ref<InstanceType<typeof ItemRecycleTab> | null>(null)

/** 主列表删了件：回收站那份数据陈旧了（否则切过去看不到刚删的件，E2E 实测踩到过）。 */
function onListChanged(): void {
  void reloadRecycle()
}

/** 回收站復元了件：主列表陈旧了。保留当前页，别把用户从第 3 页踢回第 1 页。 */
function onRecycleChanged(): void {
  void listTab.value?.refresh()
}

/** 回收站重取。页签还没挂上时（非管理员）什么都不做。 */
function reloadRecycle(): Promise<void> {
  return recycleTab.value?.reload() ?? Promise.resolve()
}

function reload(): void {
  void listTab.value?.reload()
  if (isAdmin.value) {
    void reloadRecycle()
  }
}

// 录入/编辑/作废（ITEM）、库存动作（INVENTORY）、CSV 标记回写（YAHOO_IMPORT）都会改变列表
useSyncInvalidation(['ITEM', 'INVENTORY', 'YAHOO_IMPORT'], reload)

onMounted(() => {
  // 首载先消费 URL（从详情返回/深链直达时带回落札筛选与页码，C1）——那部分在子组件
  // 的 loadFromRoute 里，与它自己的列表加载挨着，免得"先设条件再查"被拆到两个文件
  void listTab.value?.loadFromRoute()
  if (isAdmin.value) {
    void reloadRecycle()
  }
})
</script>

<template>
  <section class="items-view">
    <AppPageHeader :title="t('items.title')" />

    <div class="kcgl-card items-body">
      <el-tabs
        v-model="activeTab"
        class="items-tabs"
      >
        <el-tab-pane
          :label="t('items.title')"
          name="list"
        >
          <ItemListTab
            ref="listTab"
            @changed="onListChanged"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="isAdmin"
          :label="t('items.recycle.tabTitle')"
          name="recycle"
        >
          <ItemRecycleTab
            ref="recycleTab"
            @changed="onRecycleChanged"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </section>
</template>

<style scoped>
.items-view {
  display: grid;
  /* D-126：这一行是「中间列横滑」能不能成立的总开关。grid 隐式列是 auto，其下限是
     min-content——表内 13 列各有固定列宽时，min-content 就是列宽之和（1406），于是
     卡片被撑到 1472 宽、越过 .shell-main 溢出到页面，而 el-table 的容器宽等于它自己
     的表格宽（scrollWidth == clientWidth），**表格内部永远不出横滑条**，钉列是 sticky
     实现、没有内部滚动就粘不住，等于白钉。minmax(0, 1fr) 把列的下限压到 0，卡片才
     回到"视口给多宽就多宽"，横向溢出归 el-table 自己管内（探针实测：改前 1280 视口
     页面横滑 440px、表内横滑 0px；改后页面 0、表内 ~400px，左钉商品列与右钉操作列
     在滑到底后位移 0px）。 */
  grid-template-columns: minmax(0, 1fr);
  gap: var(--kcgl-space-4);
}

.items-body {
  padding: var(--kcgl-space-5) var(--kcgl-space-6);
}
</style>
