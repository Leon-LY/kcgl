<script setup lang="ts">
import { useRouter } from 'vue-router'

/**
 * 页头基元（桌面端页面统一入口）：返回行 + 标题 +（可选）说明 + 右侧操作区。
 *
 * v1 各页面自己拼标题与工具行：字号 1.05/1.2/1.25 三种混用、标题与筛选器同排、
 * 主操作按钮位置每页不同——用户每进一页都要重新找"这一屏是什么、能做什么"。
 * 统一到本组件后信息固定在左上（标题）与右上（操作），符合业务系统的扫读习惯。
 *
 * 不用卡片包裹：页头是页面的"抬头"，不是内容面（docs/07 §1 自查清单：不堆装饰）。
 */
const props = defineProps<{
  /** 已翻译的页面标题（调用方 t(...)，本组件不做 i18n——标题 key 由路由 meta 管理） */
  title: string
  /** 已翻译的补充说明（可选）：一句话说清本页用途 */
  description?: string
  /** 二级页返回链接（列表↔详情）：给值即在标题上方渲染返回行 */
  backTo?: { name: string; label: string }
}>()

const router = useRouter()
</script>

<template>
  <header class="page-header">
    <button
      v-if="props.backTo"
      type="button"
      class="page-header-back"
      @click="router.push({ name: props.backTo.name })"
    >
      ← {{ props.backTo.label }}
    </button>
    <div class="page-header-row">
      <div class="page-header-text">
        <h1 class="page-header-title">
          {{ props.title }}
        </h1>
        <p
          v-if="props.description"
          class="page-header-desc"
        >
          {{ props.description }}
        </p>
      </div>
      <div class="page-header-actions">
        <slot name="actions" />
      </div>
    </div>
  </header>
</template>

<style scoped>
.page-header {
  margin-bottom: var(--kcgl-space-5);
}

.page-header-back {
  margin-bottom: var(--kcgl-space-2);
  padding: 0;
  border: none;
  background: none;
  color: var(--kcgl-color-primary);
  font: inherit;
  font-size: 0.875rem;
  cursor: pointer;
  transition: color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.page-header-back:hover {
  color: var(--kcgl-color-primary-dark);
  text-decoration: underline;
}

.page-header-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--kcgl-space-4);
  flex-wrap: wrap;
}

.page-header-text {
  min-width: 0;
}

.page-header-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  line-height: 1.4;
}

.page-header-desc {
  margin: var(--kcgl-space-1) 0 0;
  font-size: 0.875rem;
  color: var(--kcgl-color-text-sub);
}

.page-header-actions {
  display: flex;
  align-items: center;
  gap: var(--kcgl-space-2);
  flex-shrink: 0;
}

.page-header-actions:empty {
  display: none;
}
</style>
