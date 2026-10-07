<script setup lang="ts">
/**
 * 空态基元：一句话说清「这里为什么是空的 + 下一步该做什么」。
 *
 * v1 各页面用 el-empty 默认图或纯文本「暂无数据」收场，用户在雅虎联动/Excel 两页
 * 把它读成了「内容加载不出来」（实测反馈）。空态是界面的一部分，不是缺省兜底：
 * 空态要能教会用户下一步（下载模板/导入 CSV/新增第一件），所以带 action 插槽。
 *
 * 图形用内联描边 SVG（docs/07 §1：不用 emoji 充图标、不引图标字体）；默认是
 * 「空的收纳盒」——与库存作业的实物语汇一致；页面可用 icon 插槽换图形。
 */
defineProps<{
  /** 已翻译的空态标题（如「取込履歴はありません」） */
  title: string
  /** 已翻译的下一步引导（如「CSV を取り込むと、ここに履歴が並びます」） */
  description?: string
  /** 表格空行内等"已经很窄"的位置：收紧留白与图形，避免把表身撑高半屏 */
  compact?: boolean
}>()
</script>

<template>
  <div
    class="empty-state"
    :class="{ 'is-compact': compact }"
  >
    <slot name="icon">
      <svg
        class="empty-state-mark"
        viewBox="0 0 48 48"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M8 19h32v19a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2z" />
        <path d="M6 12a2 2 0 0 1 2-2h32a2 2 0 0 1 2 2v7H6z" />
        <path d="M19 25h10" />
      </svg>
    </slot>
    <p class="empty-state-title">
      {{ title }}
    </p>
    <p
      v-if="description"
      class="empty-state-desc"
    >
      {{ description }}
    </p>
    <div class="empty-state-action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--kcgl-space-2);
  padding: var(--kcgl-space-7) var(--kcgl-space-5);
  text-align: center;
  /* 行高显式声明：空态也会落进 el-table 的 #empty 槽，那里的 .el-table__empty-text
     带 60px 行高（为单行文本设计），不改的话标题与引导会被拉成两行间隔 */
  line-height: 1.5;
}

.empty-state.is-compact {
  gap: var(--kcgl-space-1);
  padding: var(--kcgl-space-4) var(--kcgl-space-4);
}

.empty-state.is-compact .empty-state-mark {
  width: 28px;
  height: 28px;
}

.empty-state-mark {
  width: 44px;
  height: 44px;
  color: var(--kcgl-color-text-faint);
}

.empty-state-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text);
}

.empty-state-desc {
  margin: 0;
  max-width: 46ch;
  font-size: 0.875rem;
  color: var(--kcgl-color-text-sub);
}

/* 移动端字号下限（docs/07 §3）：空态说明是内容，0.875rem=14px 差一口气到
   0.9rem（14.4px）的下限；桌面保持原值。空态出现在到货/盘点等列表页，
   手机上本来就是"看一句说明再决定下一步"的场景，字小了直接影响引导。 */
.shell-mobile .empty-state-desc {
  font-size: 0.9rem;
}

.empty-state-action:not(:empty) {
  margin-top: var(--kcgl-space-3);
}
</style>
