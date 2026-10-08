<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import SetupChecklistCard from '@/components/SetupChecklistCard.vue'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { shell, switchShell } = useShell()

const canEntry = computed(() => auth.me != null && auth.me.role <= 2)

/**
 * 门禁回跳的落地信息。路由守卫把电脑版专属页（ラベル印刷、/admin/** 等）在
 * 手机壳里的访问拦成 { home, ?desktopOnly=<原路径> }——这里把原路径还原出来，
 * 让用户看到「你点的是哪个页面」以及一条明确的出路，而不是撞一下墙回到原地。
 */
const desktopOnlyPath = computed(() => {
  const raw = route.query.desktopOnly
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.length > 0 ? value : null
})

/** 原路径的文档标题（读路由 meta，不另抄一份文案表）；解析不到就不带页面名。 */
const desktopOnlyName = computed(() => {
  const path = desktopOnlyPath.value
  if (path === null) {
    return ''
  }
  const titleKey = router.resolve(path).meta.titleKey
  return typeof titleKey === 'string' ? t(titleKey) : ''
})

/** 切到电脑版并直接打开目标页：切换本身会落 localStorage，桌面壳侧栏可切回。 */
function openInDesktop(): void {
  const path = desktopOnlyPath.value
  switchShell('desktop')
  void router.push(path ?? { name: 'dashboard' })
}

const shellOptions = [
  { value: 'mobile', labelKey: 'common.shellMobile' },
  { value: 'desktop', labelKey: 'common.shellDesktop' },
] as const

/**
 * 切到桌面壳后 '/' 会重定向到大盘（M5-③ D-072：桌面落地页=dashboard），
 * 这里主动带上目标页，避免「切了壳还停在移动首页」的一跳。
 */
function onSwitchShell(target: 'mobile' | 'desktop'): void {
  switchShell(target)
  if (target === 'desktop') {
    void router.push({ name: 'dashboard' })
  }
}
</script>

<template>
  <section
    v-if="auth.me"
    class="home"
  >
    <h1 class="home-welcome">
      {{ t('home.welcome', { name: auth.me.displayName }) }}
    </h1>

    <!-- 门禁回跳的说明卡：只在守卫拦回来时出现（?desktopOnly）。role=alert 是因为
         它就是用户刚才那一下的反馈，出现即该被读出来 -->
    <div
      v-if="desktopOnlyPath"
      class="kcgl-card home-notice"
      role="alert"
    >
      <p class="home-notice-text">
        {{
          desktopOnlyName
            ? t('home.desktopOnly.title', { name: desktopOnlyName })
            : t('home.desktopOnly.titlePlain')
        }}
      </p>
      <button
        type="button"
        class="kcgl-btn kcgl-btn-primary home-notice-action"
        @click="openInDesktop"
      >
        {{ t('home.desktopOnly.action') }}
      </button>
    </div>

    <SetupChecklistCard />

    <!-- 作业入口排在首启引导之后、设置之前：手机上进首页就是为了点一个页面钻进去，
         入口不该排在「看的东西」下面。原先它排第三（账号信息、表示切替之后），
         首屏给的是两个不可点的信息块 -->
    <div class="kcgl-card home-card">
      <h2 class="kcgl-section-title">
        {{ t('home.quickActions') }}
      </h2>
      <div class="home-actions">
        <button
          v-if="canEntry"
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'entry' })"
        >
          <span class="home-entry-label">{{ t('home.goEntry') }}</span>
        </button>
        <button
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'arrival' })"
        >
          <span class="home-entry-label">{{ t('home.goArrival') }}</span>
        </button>
        <button
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'scan' })"
        >
          <span class="home-entry-label">{{ t('home.goScan') }}</span>
        </button>
        <button
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'today' })"
        >
          <span class="home-entry-label">{{ t('home.goToday') }}</span>
        </button>
        <!-- 标签印刷是电脑版页面（meta.shell）：先挂个小标，别让用户点进去才被
             守卫拦回来。点仍然可点——拦回来会带一句白话说明和一条出路 -->
        <button
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'print' })"
        >
          <span class="home-entry-label">{{ t('home.goPrint') }}</span>
          <span class="kcgl-mini-tag home-entry-tag">{{ t('home.desktopOnly.tag') }}</span>
        </button>
        <button
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'stocktake' })"
        >
          <span class="home-entry-label">{{ t('home.goStocktake') }}</span>
        </button>
        <button
          type="button"
          class="home-entry-link"
          @click="router.push({ name: 'pending-shipments' })"
        >
          <span class="home-entry-label">{{ t('home.goPendingShipments') }}</span>
        </button>
      </div>
    </div>

    <!-- 表示切替：整台设备的显示方式，选一次长期不动，属「设置」不属「作业」。
         故沉到页尾、去掉卡片与标题，只留一个分段控件。
         形状与磁贴刻意拉开（一个外框 + 等宽两段 + 选中片填色，宽度只占内容宽）
         ——页面底部若并排两颗和磁贴同形的白按钮，会被读成「另外两个入口」。
         aria 分组名沿用已有的 common.shellSwitch：标题从视觉上撤掉，语义不撤。 -->
    <div
      class="home-shell-options"
      role="group"
      :aria-label="t('common.shellSwitch')"
    >
      <button
        v-for="option in shellOptions"
        :key="option.value"
        type="button"
        class="home-shell-option"
        :class="{ 'is-active': shell === option.value }"
        :aria-pressed="shell === option.value"
        @click="onSwitchShell(option.value)"
      >
        {{ t(option.labelKey) }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.home {
  display: grid;
  gap: var(--kcgl-space-4);
}

.home-welcome {
  margin: 0;
  /* docs/07 §3 字号档：页面标题 1.25rem/600 */
  font-size: 1.25rem;
  font-weight: 600;
}

/* 门禁回跳说明卡：主色描边标出「你刚才那一下的结果在这里」，形状仍是整框
   （侧边彩色竖条是 docs/07 §1 硬禁用），与同页卡片同构。
   内边距走 .kcgl-card 的常规档（--kcgl-space-5），不另写任意值。 */
.home-notice {
  display: grid;
  gap: var(--kcgl-space-3);
  border-color: var(--kcgl-color-primary);
}

/* 一句话的说明，按 docs/07 §3「纯阅读正文 1rem」 */
.home-notice-text {
  margin: 0;
  font-size: 1rem;
  line-height: 1.6;
}

.home-notice-action {
  width: 100%;
}

.home-card {
  display: grid;
  gap: var(--kcgl-space-3);
}

/* 快捷入口：两列磁贴（600px 起三列）。原先是七行横贯整宽的行，每行只有十来个
   字符，右半行全空，七行还要滚掉大半屏——首页因此有 1800px 高。磁贴 64px 高
   （触控下限 44px 有余），宽屏三列正好把一行填满；不铺第四列，是因为英文最长
   标签「Pending shipments」在 880 宽的四列里放不下会折成两行，同排磁贴被撑高
   就参差不齐。
   flex-wrap 是给「ラベル印刷 + パソコン用 小标」那条留的：窄磁贴里小标换到第二
   行，不会把标签挤扁。 */
.home-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--kcgl-space-2);
}

@media (min-width: 600px) {
  .home-actions {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

.home-entry-link {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--kcgl-space-2);
  min-height: 64px;
  /* 右侧留 24px 给绝对定位的行尾尖括号（尖括号在 8px 处，本身约 10px 宽），
     别让它压在标签上 */
  padding: var(--kcgl-space-3) var(--kcgl-space-5) var(--kcgl-space-3) var(--kcgl-space-3);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-m);
  background: var(--kcgl-color-card);
  box-shadow: var(--kcgl-shadow-card);
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.95rem;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 标签占满剩余宽度：把行尾的尖括号顶到磁贴右边（原写法靠父容器
   space-between 顶伪元素——flex 里裸文本成了匿名盒，碰巧生效；给标签一个
   flex 项是同一效果，还顺手让换行行为可控）。 */
.home-entry-label {
  flex: 1 1 auto;
  min-width: 0;
}

/* 行尾尖括号 = 「点了会去另一个页面」的唯一提示。磁贴只有文字和描边，
   拿掉它看着就是个静态格子，用户不下手点。
   绝对定位贴右边中线：标签换行时（en「Pending shipments」两行）它不会跟着
   被挤到第三行去——留在 flex 流里就是那个下场。 */
.home-entry-link::after {
  content: '›';
  position: absolute;
  right: var(--kcgl-space-2);
  top: 50%;
  transform: translateY(-50%);
  color: var(--kcgl-color-text-sub);
  font-size: 1.3rem;
  line-height: 1;
}

/* 「电脑版页面」小标：视觉由 .kcgl-mini-tag（brand.css ⑤）给。窄磁贴里它会
   换到第二行——不缩（nowrap），缩了就是一行挤不下的糊字。 */
.home-entry-tag {
  flex: 0 0 auto;
}

/* 悬停高亮只在真有指针的设备生效（触屏 tap 会把 :hover 卡在最后点过的元素上） */
@media (hover: hover) {
  .home-entry-link:hover {
    border-color: var(--kcgl-color-primary);
    color: var(--kcgl-color-primary);
  }
}

/* 触屏无 hover，按压态是唯一反馈：边框变主色 + 1px 下沉 */
.home-entry-link:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  transform: translateY(1px);
}

/* 分段控件：一个外框 + 两段。
   width: fit-content + 子项 flex:1（basis 0）= 两段等宽、整体只占内容宽：
   铺满的话平板档 880px 上会变成左右各 440px 的两条长按钮，比上面的卡片还宽。
   不写 overflow: hidden 收圆角——那会把 :focus-visible 的焦点环一并裁掉
   （docs/07 §6：焦点环始终可见），改用分段各自只圆外侧两角。 */
.home-shell-options {
  display: flex;
  justify-self: start;
  width: fit-content;
  /* 与上方作业卡拉开一档：它不属于「作业」那一组 */
  margin-top: var(--kcgl-space-2);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
}

.home-shell-option {
  flex: 1;
  min-height: 44px;
  padding: 0 var(--kcgl-space-5);
  /* 外框已有描边，分段自己不再画框；两段之间 1px 是控件内部的分段界线，
     不是 docs/07 §1 禁的「侧边彩色强调条」 */
  border: 0;
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.9rem;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.home-shell-option:first-child {
  border-top-left-radius: calc(var(--kcgl-radius-s) - 1px);
  border-bottom-left-radius: calc(var(--kcgl-radius-s) - 1px);
}

.home-shell-option:last-child {
  border-top-right-radius: calc(var(--kcgl-radius-s) - 1px);
  border-bottom-right-radius: calc(var(--kcgl-radius-s) - 1px);
  border-left: 1px solid var(--kcgl-color-border);
}

.home-shell-option.is-active {
  background: var(--kcgl-color-primary-soft);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 触屏无 hover，按压态是唯一反馈 */
.home-shell-option:active {
  color: var(--kcgl-color-primary);
}
</style>
