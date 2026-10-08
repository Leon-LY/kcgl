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

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('home.accountInfo') }}
      </h2>
      <dl class="home-info">
        <div class="home-info-row">
          <dt>{{ t('home.username') }}</dt>
          <dd>{{ auth.me.username }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.displayName') }}</dt>
          <dd>{{ auth.me.displayName }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.role') }}</dt>
          <dd>{{ t(`auth.role.${auth.me.role}`) }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.locale') }}</dt>
          <dd>{{ auth.me.locale }}</dd>
        </div>
      </dl>
    </div>

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('common.shellSwitch') }}
      </h2>
      <div class="home-shell-options">
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
    </div>

    <div class="kcgl-card home-card home-card-wide">
      <h2 class="home-card-title">
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

    <p class="home-preparing">
      {{ t('home.preparing') }}
    </p>
  </section>
</template>

<style scoped>
.home {
  display: grid;
  gap: 16px;
}

/* 平板档（≥768，docs/07 §1 三档表）：首页铺成两列。否则 880 宽的列里每张卡都被
   拉成横贯一整条，卡内文字只占左边一小截，右半张卡全是空白——用户报的「所有
   元素上下排、右侧大量空白」在宽屏上最刺眼。两列只给「短卡片」并排（账号信息 /
   表示切替），宽内容（欢迎语、禁行说明、引导清单、快捷入口、页脚）各占整行。 */
@media (min-width: 768px) {
  .home {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    /* 卡片各按自身内容收高。默认的 stretch 会把「表示切替」拉成和隔壁
       「アカウント情報」一样高——那个高度差就变成卡片底部的空白。 */
    align-items: start;
  }

  .home-welcome,
  .home-notice,
  .home > .setup-card,
  .home-card-wide,
  .home-preparing {
    grid-column: 1 / -1;
  }
}

.home-welcome {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.home-card {
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 20px;
}

/* 门禁回跳说明卡：主色描边标出「你刚才那一下的结果在这里」，形状仍是整框
   （侧边彩色竖条是 docs/07 §1 硬禁用），与同页其他卡同构。 */
.home-notice {
  display: grid;
  gap: 12px;
  padding: 20px;
  border-color: var(--kcgl-color-primary);
}

.home-notice-text {
  margin: 0;
  font-size: 0.95rem;
  line-height: 1.6;
}

.home-notice-action {
  width: 100%;
}

.home-card-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.home-info {
  margin: 0;
  display: grid;
  gap: var(--kcgl-space-3);
}

/* 一字段一行：标签定宽在左、值紧随其后，三档同一套。原先手机档把标签压在值
   上方（四行摊成八行），而这里的值都很短（「管理者」「ja-JP」），右半张卡全空
   ——是「右侧大量空白」的另一处来源。标签列 7em（0.9rem ≈ 101px）装得下三语
   最长标签（en「Display name」约 86px）。 */
.home-info-row {
  display: grid;
  grid-template-columns: 7em 1fr;
  gap: var(--kcgl-space-2);
  align-items: baseline;
}

.home-info-row dt {
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.home-info-row dd {
  margin: 0;
}

.home-shell-options {
  display: flex;
  gap: 8px;
}

.home-shell-option {
  flex: 1;
  height: 44px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.home-shell-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 触屏无 hover，按压态是唯一反馈 */
.home-shell-option:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

/* 快捷入口：两列磁贴（600px 起三列）。原先是七行横贯整宽的行，每行只有十来个
   字符，右半行全空，七行还要滚掉大半屏——首页因此有 1800px 高。磁贴 64px 高
   （触控下限 44px 有余），宽屏三列正好把一行填满。
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

.home-preparing {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}
</style>
