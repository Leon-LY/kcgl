<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import AppEmptyState from '@/components/AppEmptyState.vue'
import { formatJstDateTime, formatYen } from '@/utils/format'
import { toDisplayMessage } from '@/utils/errors'
import { fetchPendingShipments } from '@/utils/api'
import type { YahooPendingShipment } from '@/utils/api'

/**
 * 出荷待ち（M4，docs/01 7.2）：雅虎落札成交→实物扫码卖出之间的拣货队列。
 * 后端按货架号序整页返回（量级=数日发货时滞，无分页）；滞留红标=成交超
 * 阈值天数未出库。「売り上げる」直达扫码页定位该件（?code= 预填），走
 * 正常 SELL 流程出库清账。他人卖出/CSV 回写经 SSE 失效重取（INVENTORY
 * 含卖出出库，YAHOO_IMPORT 含成交标记入列）。
 */

const { t } = useI18n()
const router = useRouter()

const items = ref<YahooPendingShipment[]>([])
const loading = ref(false)
const loadError = ref('')

/** SSE 重取与手动重试并发时只认最新一次响应（列表视图统一防乱序模式）。 */
let requestSeq = 0

async function reload(): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await fetchPendingShipments()
    if (seq !== requestSeq) {
      return
    }
    items.value = data.items
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    loadError.value = toDisplayMessage(error, t)
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

function goSell(item: YahooPendingShipment): void {
  void router.push({ name: 'scan', query: { code: item.itemCode } })
}

useSyncInvalidation(['INVENTORY', 'YAHOO_IMPORT'], () => {
  void reload()
})

onMounted(() => {
  void reload()
})
</script>

<template>
  <section class="shipment-view">
    <h1 class="shipment-title">
      {{ t('yahoo.shipments.title') }}
    </h1>

    <p
      v-if="loadError"
      class="kcgl-error-box"
      role="alert"
    >
      {{ loadError }}
      <button
        type="button"
        class="shipment-retry"
        @click="reload"
      >
        {{ t('common.reload') }}
      </button>
    </p>

    <template v-else>
      <p
        v-if="items.length > 0 || !loading"
        class="shipment-count"
      >
        {{ t('yahoo.shipments.count', { n: items.length }) }}
      </p>

      <p
        v-if="loading && items.length === 0"
        class="shipment-loading"
      >
        {{ t('common.loading') }}
      </p>

      <!-- 空态改用共用基元（雅虎/Excel 两页同款）：标题仍是原句，下面多一句
           「下一步」——只报「没有」的空态会被读成加载失败（实测反馈） -->
      <AppEmptyState
        v-else-if="items.length === 0"
        class="shipment-empty"
        compact
        :title="t('yahoo.shipments.empty')"
        :description="t('yahoo.shipments.emptyHint')"
      />

      <div
        v-else
        class="shipment-list"
      >
        <div
          v-for="item in items"
          :key="item.itemId"
          class="shipment-card"
        >
          <div class="shipment-head">
            <span class="shipment-thumb">
              <img
                v-if="item.thumbUrl"
                :src="item.thumbUrl"
                alt=""
                loading="lazy"
              >
            </span>
            <div class="shipment-body">
              <p class="shipment-code">
                {{ item.itemCode }}
              </p>
              <p class="shipment-meta">
                {{ t(`common.warehouse.${item.warehouse}`) }}
              </p>
              <p
                v-if="item.shelfNo"
                class="shipment-meta"
              >
                {{ t('yahoo.shelfNo') }} {{ item.shelfNo }}
              </p>
              <p class="shipment-date">
                {{ t('yahoo.shipments.closedAt') }}：{{ formatJstDateTime(item.closedAt) }}
              </p>
            </div>
            <div class="shipment-side">
              <p class="shipment-price">
                {{ formatYen(item.soldPrice) }}
              </p>
              <span
                v-if="item.delayed"
                class="shipment-delayed"
              >
                {{ t('yahoo.shipments.delayed') }}
              </span>
            </div>
          </div>
          <button
            type="button"
            class="kcgl-btn kcgl-btn-primary shipment-sell"
            @click="goSell(item)"
          >
            {{ t('yahoo.shipments.goSell') }}
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
/* 内容列宽由移动壳统一持有（--kcgl-content-width），页面根不再自设 560px——
   否则手机上等于又把版面缩回「PC 窄列」 */
.shipment-view {
  display: grid;
  gap: 12px;
}

.shipment-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.shipment-count {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.shipment-loading {
  padding: 40px 0;
  text-align: center;
  color: var(--kcgl-color-text-faint);
  font-size: 0.9rem;
}

/* 空态留白/居中/字色由 AppEmptyState 自带，这里只补上下呼吸位 */
.shipment-empty {
  padding-top: 24px;
  padding-bottom: 24px;
}

/* 错误句里的行内重试链接：视觉必须保持紧凑下划线（不能撑大去挤错误文案），
   故不改视觉盒，用 ::after 外扩命中区到约 47px；父容器即自身，自身需
   position: relative 才能承载这个绝对定位热区 */
.shipment-retry {
  position: relative;
  display: inline-block;
  margin-left: 8px;
  padding: 0;
  border: none;
  background: none;
  color: var(--kcgl-color-danger);
  font: inherit;
  font-size: 0.9rem;
  text-decoration: underline;
  cursor: pointer;
  transition: transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.shipment-retry::after {
  content: '';
  position: absolute;
  inset: -12px -10px;
}

/* 触屏无 hover：按下 1px 下沉（与 .kcgl-btn-primary:active 同语汇） */
.shipment-retry:active {
  transform: translateY(1px);
}

.shipment-card {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-card);
  box-shadow: var(--kcgl-shadow-card);
}

.shipment-card + .shipment-card {
  margin-top: 8px;
}

.shipment-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.shipment-thumb {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-bg);
  overflow: hidden;
}

.shipment-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.shipment-body {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
}

.shipment-code {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.shipment-meta {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.shipment-date {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.shipment-side {
  flex-shrink: 0;
  display: grid;
  gap: 4px;
  justify-items: end;
}

.shipment-price {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  white-space: nowrap;
}

/* 滞留角标：纯状态词（真微型标签，不是内容字），走微型标签下限 0.8rem
   （docs/07 §3），与同壳 .status-tag 一致；不上内容字的 0.9rem。 */
.shipment-delayed {
  padding: 2px 8px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.8rem;
  white-space: nowrap;
}

.shipment-sell {
  width: 100%;
}
</style>
