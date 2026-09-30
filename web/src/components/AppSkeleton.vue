<script setup lang="ts">
/**
 * 骨架屏基元：列表/表格/详情三形的加载占位。
 *
 * 加载态三态之一（docs/07 §6）。v1 列表页在内容位置放转圈（内容区中央 spinner），
 * 加载完内容再"跳"进来；骨架屏按真实排版占位，加载完不产生位移。
 * 动效只有微光扫过（.kcgl-skeleton），不做位移与缩放。
 */
withDefaults(
  defineProps<{
    /** 占位行数（表格/列表形） */
    rows?: number
    /** 形态：表格形（等宽行）/ 卡片形（大块）/ 详情形（标签-值两列） */
    variant?: 'table' | 'cards' | 'detail'
  }>(),
  { rows: 5, variant: 'table' },
)
</script>

<template>
  <div
    class="skeleton"
    :class="`is-${variant}`"
    role="status"
    aria-busy="true"
  >
    <template v-if="variant === 'detail'">
      <div
        v-for="i in rows"
        :key="i"
        class="skeleton-row"
      >
        <span class="kcgl-skeleton skeleton-label" />
        <span class="kcgl-skeleton skeleton-value" />
      </div>
    </template>
    <template v-else>
      <span
        v-for="i in rows"
        :key="i"
        class="kcgl-skeleton skeleton-row-item"
      />
    </template>
  </div>
</template>

<style scoped>
.skeleton {
  display: grid;
  gap: var(--kcgl-space-3);
  padding: var(--kcgl-space-4) 0;
}

.skeleton-row {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: var(--kcgl-space-4);
  align-items: center;
}

.skeleton-label {
  height: 14px;
}

.skeleton-value {
  height: 14px;
}

.is-table .skeleton-row-item {
  height: 20px;
}

.is-cards .skeleton-row-item {
  height: 56px;
  border-radius: var(--kcgl-radius-m);
}
</style>
