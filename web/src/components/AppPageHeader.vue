<script setup lang="ts">
import { computed } from 'vue'

/**
 * 页头基元（桌面端页面统一入口）：返回行 + 标题 +（可选）说明 + 右侧操作区。
 *
 * v1 各页面自己拼标题与工具行：迁移前桌面 12 个页面里，**6 个连标题规则都没有**
 * （`<h1>` 按浏览器默认 2em/700 渲染）、5 个自持 1.2rem/600、1 个 1.25rem/600；
 * 主操作按钮位置每页不同——用户每进一页都要重新找"这一屏是什么、能做什么"。
 * 统一到本组件后信息固定在左上（标题）与右上（操作），符合业务系统的扫读习惯。
 *
 * 不用卡片包裹：页头是页面的"抬头"，不是内容面（docs/07 §1 自查清单：不堆装饰）。
 *
 * **本组件不带外边距**：页头与下方内容之间的间距由调用方根容器的 gap 提供（桌面各页
 * 根容器都是 `display: grid` + gap，见各视图 `.xxx-view`）。组件若自带 margin-bottom，
 * 会与那份 gap 相加、标题下方凭空多出 24px。新页面接入时沿用同一模式即可。
 *
 * **返回行只接受回调（`onBack`），不提供具名路由跳转**：二级页返回列表时，列表的
 * 筛选与页码在 query 里，`router.push({name})` 会把它们丢掉（D-112/C1），必须走
 * `router.back()`。给出「具名跳转」这个口子等于邀请后来者写错，故不留。
 */

const props = defineProps<{
  /** 已翻译的页面标题（调用方 t(...)，本组件不做 i18n——标题 key 由路由 meta 管理）。
   *  标题需带结构（如详情页的管理号 + 状态片）时改用 title 插槽。 */
  title?: string
  /** 已翻译的补充说明（可选）：一句话说清本页用途。需自定义样式时改用 description 插槽。 */
  description?: string
  /** 返回行的文案（与 onBack 搭配使用） */
  backLabel?: string
  /** 返回动作：给值即在标题上方渲染返回行 */
  onBack?: () => void
}>()

const hasBack = computed(() => props.onBack !== undefined)
</script>

<template>
  <header class="page-header">
    <button
      v-if="hasBack"
      type="button"
      class="page-header-back"
      @click="onBack?.()"
    >
      ← {{ backLabel }}
    </button>
    <div class="page-header-row">
      <div class="page-header-text">
        <h1 class="page-header-title">
          <slot name="title">
            {{ props.title }}
          </slot>
        </h1>
        <slot name="description">
          <p
            v-if="props.description"
            class="page-header-desc"
          >
            {{ props.description }}
          </p>
        </slot>
      </div>
      <div class="page-header-actions">
        <slot name="actions" />
      </div>
    </div>
  </header>
</template>

<style scoped>
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
