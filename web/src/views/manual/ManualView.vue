<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { getVisibleSections, pick, pickList, toLang } from '@/manual'
import type { Device, ManualSection, Role } from '@/manual'

/**
 * 应用内操作手册（M7）。
 *
 * 与甲方 PDF 读的是同一份内容（src/manual/sections/*.json），此处按「当前角色 +
 * 当前壳」过滤——同一台手机上，编辑者与查看者看到的步骤本就不同，手册若不分
 * 角色，只能给所有人看最大集，越权步骤就白纸黑字写在眼前了。
 *
 * 过滤放在页内而不是路由 meta：本页在两种壳里都能打开，且全员可进（查看者也
 * 需要查自己能不能做），路由守卫拦不得。devices 由 useShell 定，注意它是 ref。
 *
 * 版式取「文档」而非「卡片阵列」：手册是长文，一屏一卡会把层级切碎成表格。
 * 徽标只标角色——设备档已由过滤承担，再标一遍是重复信息（PDF 因为不分档，
 * 才需要设备徽标）。
 */

const { t, locale } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const { shell } = useShell()

const lang = computed(() => toLang(locale.value))
const device = computed<Device>(() => shell.value)
const role = computed<number>(() => auth.me?.role ?? 3)

const sections = computed(() => getVisibleSections(role.value, device.value))

/** 双语徽标文案：只在节点确实带了角色限制时渲染，缺省（全员）不标 */
function roleLabel(roles: Role[] | undefined): string {
  if (!roles || roles.length === 0 || roles.length === 3) return ''
  if (roles.length === 1 && roles[0] === 1) return t('manual.roleAdmin')
  if (roles.length === 1 && roles[0] === 3) return t('manual.roleViewer')
  return t('manual.roleEditor')
}

function sectionAnchor(section: ManualSection): string {
  return `manual-${section.id}`
}

/** 目录跳转：走 scrollIntoView 而非 <a href="#...">——vue-router 未配 scrollBehavior，
 *  锚链会被它接成一次 pushState 导航，URL 变了却不滚动（看着像点了没反应）。 */
function jumpTo(section: ManualSection): void {
  document.getElementById(sectionAnchor(section))?.scrollIntoView({
    block: 'start',
    behavior: 'smooth',
  })
}

function onBack(): void {
  router.back()
}
</script>

<template>
  <div class="manual-view">
    <AppPageHeader
      :title="t('manual.title')"
      :description="t('manual.description')"
      :back-label="t('common.back')"
      :on-back="onBack"
    />

    <p
      v-if="sections.length === 0"
      class="manual-empty"
    >
      {{ t('manual.empty') }}
    </p>

    <template v-else>
      <nav
        class="manual-toc"
        :aria-label="t('manual.toc')"
      >
        <h2 class="manual-toc-title">
          {{ t('manual.toc') }}
        </h2>
        <ol class="manual-toc-list">
          <li
            v-for="section in sections"
            :key="section.id"
          >
            <button
              type="button"
              class="manual-toc-link"
              @click="jumpTo(section)"
            >
              {{ pick(section.title, lang) }}
            </button>
          </li>
        </ol>
      </nav>

      <section
        v-for="section in sections"
        :id="sectionAnchor(section)"
        :key="section.id"
        class="manual-section"
      >
        <h2 class="manual-section-title">
          {{ pick(section.title, lang) }}
        </h2>
        <p
          v-if="section.intro"
          class="manual-section-intro"
        >
          {{ pick(section.intro, lang) }}
        </p>

        <article
          v-for="feature in section.features"
          :key="feature.id"
          class="manual-feature"
          :data-feature-id="feature.id"
        >
          <h3 class="manual-feature-title">
            {{ pick(feature.title, lang) }}
            <span
              v-if="roleLabel(feature.roles)"
              class="manual-badge"
            >{{ roleLabel(feature.roles) }}</span>
          </h3>
          <p
            v-if="feature.intro"
            class="manual-feature-intro"
          >
            {{ pick(feature.intro, lang) }}
          </p>

          <ol class="manual-steps">
            <li
              v-for="(step, index) in feature.steps"
              :key="index"
              class="manual-step"
            >
              <span class="manual-step-title">{{ pick(step.title, lang) }}</span>
              <span
                v-if="roleLabel(step.roles)"
                class="manual-badge"
              >{{ roleLabel(step.roles) }}</span>
              <p
                v-if="step.body"
                class="manual-step-body"
              >
                {{ pick(step.body, lang) }}
              </p>
              <ul
                v-if="step.bullets"
                class="manual-step-bullets"
              >
                <li
                  v-for="(item, i) in pickList(step.bullets, lang)"
                  :key="i"
                >
                  {{ item }}
                </li>
              </ul>
              <p
                v-if="step.note"
                class="manual-step-note"
              >
                {{ pick(step.note, lang) }}
              </p>
            </li>
          </ol>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* 根容器：列向 grid + gap（页头不带外边距，间距由此处给，同桌面各页约定） */
.manual-view {
  display: grid;
  gap: var(--kcgl-space-6);
  padding-bottom: var(--kcgl-space-7);
}

.manual-empty {
  margin: 0;
  color: var(--kcgl-color-text-sub);
}

/* 目录：细边框容器 + 一列按钮。手机档下就是一小段清单，不折叠（章节至多 8 条） */
.manual-toc {
  padding: var(--kcgl-space-3) var(--kcgl-space-4);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-fill);
}

.manual-toc-title {
  margin: 0 0 var(--kcgl-space-1);
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--kcgl-color-text-sub);
  text-transform: uppercase;
}

.manual-toc-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: var(--kcgl-space-1) var(--kcgl-space-4);
}

.manual-toc-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--kcgl-color-primary);
  font: inherit;
  font-size: 0.9rem;
  cursor: pointer;
  transition: color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.manual-toc-link:hover {
  color: var(--kcgl-color-primary-dark);
  text-decoration: underline;
}

/* 章：与上一章用发丝线分隔——长文档靠留白和线分段，不靠容器套容器 */
.manual-section {
  scroll-margin-top: 72px;
  border-top: 1px solid var(--kcgl-color-divider);
  padding-top: var(--kcgl-space-5);
}

.manual-section-title {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 600;
}

.manual-section-intro {
  margin: var(--kcgl-space-2) 0 0;
  color: var(--kcgl-color-text-sub);
  font-size: 0.9rem;
  max-width: 68ch;
}

.manual-feature {
  margin-top: var(--kcgl-space-5);
}

.manual-feature-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--kcgl-space-2);
}

.manual-feature-intro {
  margin: var(--kcgl-space-2) 0 0;
  color: var(--kcgl-color-text-sub);
  font-size: 0.9rem;
  max-width: 68ch;
}

/* 步序：**不能用 display:grid 排列**。ol 一旦成为 grid 容器，li 被块级化后
   序号标记不再绘制（实测截图里整列步骤一个数字都没有，「先①后②」这层全靠它承载）。
   故用普通块流 + li 的上外边距拉开间距。 */
.manual-steps {
  margin: var(--kcgl-space-3) 0 0;
  padding-left: var(--kcgl-space-5);
  list-style: decimal;
}

.manual-step + .manual-step {
  margin-top: var(--kcgl-space-4);
}

.manual-step::marker {
  color: var(--kcgl-color-text-faint);
  font-size: 0.85rem;
}

.manual-step-title {
  font-weight: 600;
  margin-right: var(--kcgl-space-2);
}

.manual-step-body {
  margin: var(--kcgl-space-1) 0 0;
  max-width: 68ch;
}

/* 同上：ul 不设 grid，否则每条前面的圆点不绘制 */
.manual-step-bullets {
  margin: var(--kcgl-space-2) 0 0;
  padding-left: var(--kcgl-space-5);
  list-style: disc;
  color: var(--kcgl-color-text-sub);
  font-size: 0.9rem;
  max-width: 68ch;
}

.manual-step-bullets li + li {
  margin-top: var(--kcgl-space-1);
}

/* 提醒：整块浅底 + 完整边框（禁侧描边条，docs/07 §1） */
.manual-step-note {
  margin: var(--kcgl-space-2) 0 0;
  padding: var(--kcgl-space-2) var(--kcgl-space-3);
  border: 1px solid var(--kcgl-color-info-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-info-bg);
  color: var(--kcgl-color-info-text);
  font-size: 0.875rem;
  max-width: 68ch;
}

/* 角色徽标：中性胶囊，仅承载「这步谁能做」，不做颜色分级 */
.manual-badge {
  display: inline-block;
  padding: 1px var(--kcgl-space-2);
  border: 1px solid var(--kcgl-color-neutral-border);
  border-radius: var(--kcgl-radius-pill);
  background: var(--kcgl-color-neutral-bg);
  color: var(--kcgl-color-text-sub);
  font-size: 0.72rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
  vertical-align: middle;
}

/* 手机档：正文 15px 起、行距放宽，仓库现场一两臂距离下也读得清 */
@media (max-width: 719px) {
  .manual-view {
    gap: var(--kcgl-space-5);
  }

  .manual-step-body,
  .manual-step-bullets,
  .manual-section-intro,
  .manual-feature-intro {
    font-size: 0.95rem;
    line-height: 1.7;
  }

  .manual-section {
    scroll-margin-top: 64px;
  }
}
</style>
