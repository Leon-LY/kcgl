<script setup lang="ts">
/**
 * 状态片基元：业务状态一律走这里，不再逐页挑 el-tag 的 type。
 *
 * v1 同一状态在不同页面拿到不同颜色（在庫有 primary/success 两种写法），用户无法
 * 靠颜色建立稳定的状态语汇。本组件把状态收敛成五档语义 tone，色值取 docs/07 §2.3
 * 的三件套（文字/浅底/描边），矩形 4px 圆角与控件一致（胶囊只留给筛选片）。
 */
type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger'

withDefaults(defineProps<{ tone?: Tone }>(), { tone: 'neutral' })
</script>

<template>
  <span
    class="status-tag"
    :class="`is-${tone}`"
  >
    <slot />
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 var(--kcgl-space-2);
  border: 1px solid;
  border-radius: var(--kcgl-radius-s);
  font-size: 0.78rem;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}

/* 移动端微型标签下限 0.8rem（docs/07 §3）：0.78rem=12.5px 差一口气，
   在手机上是全页最小的一处字。桌面保持原值（高密度是对的）。 */
.shell-mobile .status-tag {
  font-size: 0.8rem;
}

.status-tag.is-neutral {
  border-color: var(--kcgl-color-neutral-border);
  background: var(--kcgl-color-neutral-bg);
  color: var(--kcgl-color-neutral);
}

.status-tag.is-primary {
  border-color: var(--kcgl-color-info-border);
  background: var(--kcgl-color-info-bg);
  color: var(--kcgl-color-info-text);
}

.status-tag.is-success {
  border-color: var(--kcgl-color-success-border);
  background: var(--kcgl-color-success-bg);
  color: var(--kcgl-color-success);
}

.status-tag.is-warning {
  border-color: var(--kcgl-color-warning-border);
  background: var(--kcgl-color-warning-bg);
  color: var(--kcgl-color-warning);
}

.status-tag.is-danger {
  border-color: var(--kcgl-color-danger-border);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
}
</style>
