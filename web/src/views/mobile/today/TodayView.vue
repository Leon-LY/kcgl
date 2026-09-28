<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSyncInvalidation } from '@/composables/useSyncInvalidation'
import { fetchTodaySession } from '@/utils/api'
import type { TodaySession } from '@/utils/api'

/**
 * 本日录入会话页（/today，M2-8b）：created_by=me + 当天 JST 窗口的个人清单，
 * 现场誊写与收工对数用。含作废件（口径=作废也数进「取り消し」），
 * 不分页（个人日清单量级为数十件，一次全量返回）。
 */

const { t } = useI18n()

const session = ref<TodaySession | null>(null)
const loading = ref(true)
const loadError = ref(false)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    session.value = await fetchTodaySession()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

// 他端失效重取（SSE）：ITEM 事件=本人清单可能已变（他端录入/管理员作废）；
// IMAGE=照片异步补传后缩略图补齐
useSyncInvalidation(['ITEM', 'IMAGE'], () => void load())
</script>

<template>
  <section class="today-view">
    <h1 class="today-title">
      {{ t('today.title') }}
    </h1>

    <div class="kcgl-card today-toolbar">
      <p
        v-if="loading && session === null"
        class="today-count"
      >
        {{ t('common.loading') }}
      </p>
      <template v-else-if="session !== null">
        <p class="today-count">
          {{ t('today.countSummary', { active: session.activeCount, voided: session.voidedCount }) }}
        </p>
        <p class="today-date">
          {{ t('today.dateLabel', { date: session.date }) }}
        </p>
      </template>
    </div>

    <div
      v-if="loadError"
      class="kcgl-card today-error"
    >
      <p class="today-error-text">
        {{ t('today.loadFailed') }}
      </p>
      <button
        type="button"
        class="kcgl-btn today-retry"
        @click="load"
      >
        {{ t('common.reload') }}
      </button>
    </div>

    <template v-else-if="session !== null">
      <div
        v-if="session.rows.length === 0"
        class="today-empty"
      >
        {{ t('today.empty') }}
      </div>
      <ul
        v-else
        class="today-list"
      >
        <li
          v-for="row in session.rows"
          :key="row.id"
          class="kcgl-card today-row"
          :class="{ 'is-voided': row.voided }"
        >
          <span class="today-thumb">
            <img
              v-if="row.thumbUrl"
              :src="row.thumbUrl"
              alt=""
              loading="lazy"
            >
          </span>
          <span class="today-body">
            <span class="today-code">{{ row.itemCode }}</span>
            <span
              v-if="row.voided"
              class="today-void"
            >
              <span class="today-void-tag">{{ t('today.voidedTag') }}</span>
              <span
                v-if="row.voidReason"
                class="today-void-reason"
              >{{ row.voidReason }}</span>
            </span>
          </span>
          <span class="today-time">{{ row.createdAt }}</span>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.today-view {
  max-width: 560px;
  margin: 0 auto;
  display: grid;
  gap: 12px;
}

.today-title {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.today-toolbar {
  display: grid;
  gap: 2px;
  padding: 16px;
}

.today-count {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
}

.today-date {
  margin: 0;
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.today-error {
  display: grid;
  gap: 12px;
  justify-items: center;
  padding: 24px 16px;
}

.today-error-text {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.today-retry {
  min-width: 160px;
  border: 1px solid var(--kcgl-color-border);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
}

.today-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--kcgl-color-text-faint);
  font-size: 0.9rem;
}

.today-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.today-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
}

.today-row + .today-row {
  margin-top: 8px;
}

/* 作废件整行降饱和：誊写时一眼跳过，但保留在对数清单里 */
.today-row.is-voided {
  background: var(--kcgl-color-bg);
  box-shadow: none;
}

.today-thumb {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  overflow: hidden;
}

.today-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.today-body {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
}

/* 大字管理号：现场誊写/对数主信息，字号大于常规列表 */
.today-code {
  font-size: 1.15rem;
  font-weight: 600;
  letter-spacing: 0.03em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.today-row.is-voided .today-code {
  text-decoration: line-through;
  text-decoration-thickness: 2px;
  color: var(--kcgl-color-text-faint);
}

.today-void {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.today-void-tag {
  flex-shrink: 0;
  padding: 1px 8px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.75rem;
  font-weight: 600;
}

.today-void-reason {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.today-time {
  flex-shrink: 0;
  font-size: 0.85rem;
  font-variant-numeric: tabular-nums;
  color: var(--kcgl-color-text-sub);
}
</style>
