<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import { fetchChecklist, markChecklistPrintDone } from '@/utils/api'
import type { Checklist } from '@/utils/api'

/**
 * 首启引导卡片（M2，M5-③ 抽为共享组件）：空字典首件录入必 400（六轮旅程）——
 * 五步引导对管理员置顶，全部完成后整体隐藏。获取失败静默不显示
 * （引导是增强，不阻塞所在页面）。桌面大盘与移动首页共用（i18n 沿用 home.setup.*）。
 */

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const { shell } = useShell()

const checklist = ref<Checklist | null>(null)

const isAdmin = computed(() => auth.me != null && auth.me.role === 1)

/**
 * 该步骤的目标页是不是电脑版专属（读路由 meta.shell，不在这里另抄一份清单——
 * 抄一份就会和路由表走岔）。五步里有四步落在 /admin/** 与 /print 上，手机上点了
 * 会被守卫拦回首页；先挂个「电脑版」小标，用户就不用挨个撞一遍墙。
 */
function isDesktopOnly(to: string): boolean {
  return router.resolve(to).meta.shell === 'desktop'
}

const setupSteps = computed(() => {
  const c = checklist.value
  if (c === null) {
    return []
  }
  return [
    { key: 'stepUsers', done: c.hasStaffUser, to: '/admin/users' },
    { key: 'stepVenue', done: c.hasVenue, to: '/admin/venues' },
    { key: 'stepBand', done: c.hasPriceBand, to: '/admin/price-bands' },
    { key: 'stepItem', done: c.hasItem, to: '/entry' },
    { key: 'stepPrint', done: c.printDone, to: '/print' },
  ].map((step) => ({ ...step, desktopOnly: isDesktopOnly(step.to) }))
})

const setupDone = computed(
  () => checklist.value !== null && setupSteps.value.every((step) => step.done),
)

/** 渲染开关：仅管理员、未全部完成、且获取成功（null=静默隐藏）。 */
const showCard = computed(() => isAdmin.value && checklist.value !== null && !setupDone.value)

const printMarking = ref(false)

async function onMarkPrintDone(): Promise<void> {
  if (printMarking.value) return
  printMarking.value = true
  try {
    checklist.value = await markChecklistPrintDone()
  } catch {
    // 幂等标记失败不打断页面——下次进来重试即可
  } finally {
    printMarking.value = false
  }
}

onMounted(async () => {
  if (!isAdmin.value) return
  try {
    checklist.value = await fetchChecklist()
  } catch {
    checklist.value = null
  }
})
</script>

<template>
  <div
    v-if="showCard"
    class="kcgl-card setup-card"
  >
    <h2 class="setup-title">
      {{ t('home.setup.title') }}
    </h2>
    <p class="setup-note">
      {{ t('home.setup.note') }}
    </p>
    <ol class="setup-steps">
      <li
        v-for="step in setupSteps"
        :key="step.key"
        class="setup-step"
        :class="{ 'is-done': step.done }"
      >
        <span
          class="setup-mark"
          :aria-hidden="true"
        >{{ step.done ? '✓' : '' }}</span>
        <button
          type="button"
          class="setup-link"
          @click="router.push(step.to)"
        >
          {{ t(`home.setup.${step.key}`) }}
        </button>
        <!-- 只在移动壳里标：桌面壳里这些步骤本来就是"点开就是了"，标了反而多余 -->
        <span
          v-if="shell === 'mobile' && step.desktopOnly"
          class="kcgl-mini-tag setup-tag"
        >{{ t('home.desktopOnly.tag') }}</span>
        <button
          v-if="step.key === 'stepPrint' && !step.done"
          type="button"
          class="setup-done-btn"
          :disabled="printMarking"
          @click="onMarkPrintDone"
        >
          {{ t('home.setup.printDone') }}
        </button>
      </li>
    </ol>
  </div>
</template>

<style scoped>
/* 元素本身已带 .kcgl-card（整框描边 + 圆角 + 浅投影）。这里原先多压一条
   3px 主色左竖条，属 docs/07 §1 硬禁用（侧边彩色竖条当强调）——本页其他卡都
   是整框，这条竖条是异类，且与主色"只用于可点击/已选中/进行中"的用法冲突。 */
.setup-card {
  display: grid;
  gap: 12px;
  padding: 20px;
}

.setup-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.setup-note {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

/* 移动端字号下限（docs/07 §3）：本卡同时挂在大盘（桌面）与移动首页上，
   只有移动端需要抬——桌面 24 吋屏上 0.85rem/0.95rem 的密度是对的。
   首页其余卡片的标题已统一到 1rem，这里跟上以免同页两种标题字号。 */
.shell-mobile .setup-title {
  font-size: 1rem;
}

.shell-mobile .setup-note {
  font-size: 0.9rem;
}

.setup-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}

.setup-step {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
}

.setup-mark {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border: 1.5px solid var(--kcgl-color-border);
  border-radius: 50%;
  color: var(--kcgl-color-primary);
  font-size: 0.85rem;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.setup-step.is-done .setup-mark {
  border-color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  font-weight: 700;
}

.setup-step.is-done .setup-link {
  color: var(--kcgl-color-text-sub);
}

.setup-link {
  border: none;
  background: none;
  padding: 0;
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.92rem;
  text-align: left;
  cursor: pointer;
  transition: color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 悬停高亮只在真有指针的设备生效（触屏 tap 会把 :hover 卡在最后点过的元素上） */
@media (hover: hover) {
  .setup-link:hover {
    color: var(--kcgl-color-primary);
    text-decoration: underline;
  }
}

/* 触屏无 hover，按压态是唯一反馈 */
.setup-link:active {
  color: var(--kcgl-color-primary);
  text-decoration: underline;
}

.setup-done-btn {
  margin-left: auto;
  flex-shrink: 0;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text-sub);
  font-size: 0.8rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 悬停高亮只在真有指针的设备生效（触屏 tap 会把 :hover 卡在最后点过的元素上） */
@media (hover: hover) {
  .setup-done-btn:hover {
    border-color: var(--kcgl-color-primary);
    color: var(--kcgl-color-primary);
  }
}

/* 触屏无 hover，按压态是唯一反馈：边框变主色 + 1px 下沉 */
.setup-done-btn:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  transform: translateY(1px);
}

/* 移动壳内：本共享引导卡补足触控目标与字号下限（桌面壳保持原密度，docs/07 §1、§3）。
   桌面靠鼠标、密度可以低；手机上一行文字按钮只有 ~20px 高，落在拇指热区外。 */
.shell-mobile .setup-step {
  min-height: 44px;
}

/* 文字按钮只放大命中区、视觉不变 */
.shell-mobile .setup-link {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
}

.shell-mobile .setup-done-btn {
  height: 44px;
  font-size: 0.9rem;
}
</style>
