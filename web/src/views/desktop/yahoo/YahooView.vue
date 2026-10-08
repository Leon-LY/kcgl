<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import AppPageHeader from '@/components/AppPageHeader.vue'
import YahooImportTab from './YahooImportTab.vue'
import YahooReconcileTab from './YahooReconcileTab.vue'
import YahooShipmentTab from './YahooShipmentTab.vue'

/**
 * 雅虎联动桌面页（M5-②b，docs/01 7.2/7.4）。三个页签各自成组件（D-144）：受注
 * インポート、出荷待ち、照合。上传仅编辑者以上，读取全员（服务端 @PreAuthorize 兜底）。
 *
 * 父组件只剩页签壳与装配：三份数据各由自己的子组件持有与加载，彼此不知道对方存在。
 * 跨页签的依赖只有一条——"批次到终态了、导入回写已可见"——受注インポート emit
 * settled，父组件去刷另两份；SSE 失效时父组件统一重取三份。
 *
 * 子组件一律不自载（首载由下面的 onMounted 触发），否则加载次数随装配顺序变化，
 * 单测里 toHaveBeenCalledTimes 的断言会变得不可推导。
 */

const { t } = useI18n()

const activeTab = ref('import')

const importTab = ref<InstanceType<typeof YahooImportTab> | null>(null)
const shipmentTab = ref<InstanceType<typeof YahooShipmentTab> | null>(null)
const reconcileTab = ref<InstanceType<typeof YahooReconcileTab> | null>(null)

/** 出荷待ち重取。页签还没挂上时什么都不做。 */
function reloadShipments(): Promise<void> {
  return shipmentTab.value?.reload() ?? Promise.resolve()
}

/** 照合重取。页签还没挂上时什么都不做。 */
function reloadReconcile(): Promise<void> {
  return reconcileTab.value?.reload() ?? Promise.resolve()
}

/** 批次被观测到终态：导入回写已可见，另两份数据该重取了（见受注インポート的轮询与上传兜底）。 */
function onBatchSettled(): void {
  void reloadShipments()
  void reloadReconcile()
}

/** 整页重取三份：首载与 SSE 失效共用。 */
function reloadAll(): void {
  void importTab.value?.reload()
  void reloadShipments()
  void reloadReconcile()
}

// 卖出（INVENTORY）与 CSV 回写（YAHOO_IMPORT）都会改变三份数据
useSyncInvalidation(['INVENTORY', 'YAHOO_IMPORT'], reloadAll)

onMounted(reloadAll)
</script>

<template>
  <section class="yahoo-view">
    <AppPageHeader :title="t('yahoo.title')" />

    <div class="kcgl-card yahoo-body">
      <el-tabs
        v-model="activeTab"
        class="yahoo-tabs"
      >
        <el-tab-pane
          :label="t('yahoo.import.title')"
          name="import"
        >
          <YahooImportTab
            ref="importTab"
            @settled="onBatchSettled"
          />
        </el-tab-pane>

        <el-tab-pane
          :label="t('yahoo.shipments.title')"
          name="shipments"
        >
          <YahooShipmentTab ref="shipmentTab" />
        </el-tab-pane>

        <el-tab-pane
          :label="t('yahoo.reconcile.title')"
          name="reconcile"
        >
          <YahooReconcileTab ref="reconcileTab" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </section>
</template>

<style scoped>
.yahoo-view {
  display: grid;
  gap: 16px;
}

.yahoo-body {
  padding: 20px 24px;
}
</style>
