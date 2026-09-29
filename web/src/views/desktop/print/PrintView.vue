<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import QRCode from 'qrcode'
import epEn from 'element-plus/es/locale/lang/en'
import epJa from 'element-plus/es/locale/lang/ja'
import epZhCn from 'element-plus/es/locale/lang/zh-cn'
import { useDictsStore } from '@/stores/dicts'
import { useSettingsStore } from '@/stores/settings'
import { ApiError, fetchItemsForPrint, type ItemSummary } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'
import { dayjs, JST_TZ } from '@/utils/format'
import { normalizeNumericText } from '@/utils/normalize'
import LabelSheet from './LabelSheet.vue'
import { customPreset, LABEL_PRESETS, type LabelEntry, type LabelPreset, type LabelPresetKey } from './label'

/**
 * 标签打印页（M2-7，docs/01 4.3）：创建日区间（JST 日界）+ 会场筛选 →
 * 按录入顺序 A4 排版打印。QR 内容恒为管理号（7.7），打印规格 margin 4（预览屏 2）；
 * 标签必含人读码（会场/日期段分行）。Element Plus 首个消费页（D-027）。
 */

/** 打印安全上限：日常 1000 件/天，超量提示缩小区间（渲染/打印耗时与易错率双护）。 */
const MAX_LABELS = 500
const PAGE_SIZE = 100

const { t, locale } = useI18n()
const dicts = useDictsStore()
const settings = useSettingsStore()

const range = ref<[string, string]>([todayJst(), todayJst()])
const venueIdFilter = ref<number | null>(null)
const reprintCode = ref('')
/** null=设置未就绪且用户未手选（radio 不选中，排版回退 small；加载后落管理员默认）。 */
const presetKey = ref<LabelPresetKey | null>(null)
const showThumb = ref(false)
const entries = ref<LabelEntry[]>([])
const total = ref(0)
const truncated = ref(false)
const loading = ref(false)
const loadError = ref<string | null>(null)
const loadErrorId = ref<string | null>(null)

/** 管理号重打输入（blur 归一化，IME 组合输入中不转换——7.8 纪律）。 */
function normalizeCodeOnBlur(): void {
  reprintCode.value = normalizeNumericText(reprintCode.value).toUpperCase()
}

/** 单票重打模式：输入了管理号即以精确匹配优先（日期/会场条件不适用）。 */
const codeMode = computed(() => reprintCode.value !== '')

function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

/** 设置缺位/脏值兜底尺寸（与服务端默认 50×30 同值）。 */
const DEFAULT_WIDTH_MM = 50
const DEFAULT_HEIGHT_MM = 30

const customDims = computed(() => ({
  widthMm: settings.data?.labelWidthMm ?? DEFAULT_WIDTH_MM,
  heightMm: settings.data?.labelHeightMm ?? DEFAULT_HEIGHT_MM,
}))

const presetOptions = computed<Array<{ value: LabelPresetKey; label: string }>>(() => [
  ...LABEL_PRESETS.map((p) => ({ value: p.key, label: t(`print.preset.${p.key}`) })),
  {
    value: 'custom',
    label: t('print.preset.customDims', { w: customDims.value.widthMm, h: customDims.value.heightMm }),
  },
])

/** 当前生效规格：custom=按管理员设定的幅/高推导（M5-③）；未就绪回退 small。 */
const preset = computed<LabelPreset>(() => {
  if (presetKey.value === 'custom') {
    return customPreset(customDims.value.widthMm, customDims.value.heightMm)
  }
  return LABEL_PRESETS.find((p) => p.key === presetKey.value) ?? LABEL_PRESETS[0]
})
const sheets = computed(() => {
  const perSheet = preset.value.cols * preset.value.rows
  return Math.ceil(entries.value.length / perSheet)
})

// EP 组件内置文案随应用语言联动（日期面板/清除按钮等）
const epLocale = computed(() => {
  if (locale.value === 'zh-CN') return epZhCn
  if (locale.value === 'en-US') return epEn
  return epJa
})

// 切到不支持缩略图的尺寸（38×21）时收起开关，避免「开着却不生效」的困惑
watch(
  () => preset.value.thumbSupported,
  (supported) => {
    if (!supported) {
      showThumb.value = false
    }
  },
)

async function buildQr(code: string): Promise<string | null> {
  try {
    // 打印规格（7.7）：quiet zone 4 模块、ECC=M；录入成功屏预览 margin 2，此处打印更宽
    return await QRCode.toDataURL(code, {
      width: 200,
      margin: 4,
      errorCorrectionLevel: 'M',
    })
  } catch {
    // 生成失败不阻断：标签人读码仍可手输定位（M5 兜底）
    return null
  }
}

async function load(): Promise<void> {
  if (loading.value) {
    return
  }
  loading.value = true
  loadError.value = null
  loadErrorId.value = null
  entries.value = []
  truncated.value = false
  try {
    const base = {
      createdFrom: range.value[0],
      createdTo: range.value[1],
      venueId: venueIdFilter.value ?? undefined,
      code: codeMode.value ? reprintCode.value : undefined,
    }
    const first = await fetchItemsForPrint({ ...base, page: 1, size: PAGE_SIZE })
    total.value = first.total
    const all: ItemSummary[] = [...first.rows]
    const want = Math.min(first.total, MAX_LABELS)
    while (all.length < want) {
      const next = await fetchItemsForPrint({
        ...base,
        page: Math.floor(all.length / PAGE_SIZE) + 1,
        size: PAGE_SIZE,
      })
      if (next.rows.length === 0) {
        break
      }
      all.push(...next.rows)
    }
    if (all.length > want) {
      all.length = want
    }
    truncated.value = first.total > all.length
    const built: LabelEntry[] = []
    for (const item of all) {
      built.push({ item, qr: await buildQr(item.itemCode) })
    }
    entries.value = built
  } catch (error) {
    loadError.value = toDisplayMessage(error, t)
    loadErrorId.value = error instanceof ApiError ? (error.errorId ?? null) : null
  } finally {
    loading.value = false
  }
}

function print(): void {
  window.print()
}

onMounted(() => {
  // 会场下拉加载失败不阻断打印（无会场筛选仍可按日期打印）
  dicts.ensureLoaded().catch(() => {})
  // 默认规格=管理员设定（label.preset，M5-③）；未就绪期间用户已手选则尊重手选，
  // 失败回退 small。SSE SETTING 失效由 store reload 自动带入新默认
  void settings
    .ensureLoaded()
    .then(() => {
      if (presetKey.value === null) {
        presetKey.value = settings.data?.labelPreset ?? 'small'
      }
    })
    .catch(() => {
      if (presetKey.value === null) {
        presetKey.value = 'small'
      }
    })
  void load()
})
</script>

<template>
  <el-config-provider :locale="epLocale">
    <section class="print-view">
      <div class="print-toolbar kcgl-card">
        <div class="print-toolbar-row">
          <label class="print-field">
            <span class="print-field-label">{{ t('print.rangeLabel') }}</span>
            <el-date-picker
              v-model="range"
              type="daterange"
              value-format="YYYY-MM-DD"
              :clearable="false"
              :start-placeholder="t('print.rangeFrom')"
              :end-placeholder="t('print.rangeTo')"
              class="print-range"
            />
          </label>
          <label class="print-field">
            <span class="print-field-label">{{ t('print.venueLabel') }}</span>
            <el-select
              v-model="venueIdFilter"
              clearable
              :placeholder="t('print.venueAll')"
              class="print-venue"
            >
              <el-option
                v-for="venue in dicts.enabledVenues"
                :key="venue.id"
                :value="venue.id"
                :label="`${venue.name}（${venue.code}）`"
              />
            </el-select>
          </label>
          <label class="print-field">
            <span class="print-field-label">{{ t('print.reprintLabel') }}</span>
            <el-input
              v-model="reprintCode"
              class="print-reprint"
              :placeholder="t('print.reprintPlaceholder')"
              clearable
              @blur="normalizeCodeOnBlur"
            />
          </label>
          <div class="print-field">
            <span class="print-field-label">{{ t('print.presetLabel') }}</span>
            <el-radio-group v-model="presetKey">
              <el-radio-button
                v-for="option in presetOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <label
            class="print-field"
            :class="{ 'is-disabled': !preset.thumbSupported }"
          >
            <span class="print-field-label">{{ t('print.thumbLabel') }}</span>
            <el-switch
              v-model="showThumb"
              :disabled="!preset.thumbSupported"
            />
          </label>
          <span class="print-actions">
            <el-button
              type="primary"
              :loading="loading"
              @click="load"
            >
              {{ t('print.load') }}
            </el-button>
            <el-button
              :disabled="entries.length === 0 || loading"
              @click="print"
            >
              {{ t('print.print') }}
            </el-button>
          </span>
        </div>
        <p
          v-if="loadError"
          class="print-error"
        >
          {{ loadError }}<span v-if="loadErrorId">（ID: {{ loadErrorId }}）</span>
        </p>
        <p
          v-else-if="entries.length > 0"
          class="print-count"
        >
          {{ t('print.count', { n: entries.length, sheets }) }}
          <span v-if="truncated">{{ t('print.truncated', { n: MAX_LABELS }) }}</span>
        </p>
      </div>

      <p class="print-guide">
        {{ t('print.guide') }}
      </p>

      <div
        v-if="entries.length > 0"
        class="print-sheets"
      >
        <LabelSheet
          :entries="entries"
          :preset="preset"
          :show-thumb="showThumb"
        />
      </div>
      <p
        v-else-if="!loading && !loadError"
        class="print-empty"
      >
        {{ codeMode ? t('print.reprintNotFound') : t('print.empty') }}
      </p>
    </section>
  </el-config-provider>
</template>

<style scoped>
.print-view {
  display: grid;
  gap: 12px;
}

.print-toolbar {
  display: grid;
  gap: 8px;
  padding: 16px;
}

.print-toolbar-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 16px;
}

.print-field {
  display: grid;
  gap: 6px;
}

.print-field.is-disabled .print-field-label {
  color: var(--kcgl-color-text-sub);
  opacity: 0.6;
}

.print-field-label {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.print-range {
  width: 240px;
}

.print-venue {
  width: 220px;
}

.print-reprint {
  width: 180px;
}

.print-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.print-error {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

.print-count {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.print-count span {
  margin-left: 12px;
  color: var(--kcgl-color-warning);
}

.print-guide {
  margin: 0;
  padding: 10px 14px;
  border: 1px solid var(--kcgl-color-warning-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-warning-bg);
  font-size: 0.85rem;
  color: var(--kcgl-color-warning);
}

.print-empty {
  margin: 0;
  padding: 48px 0;
  text-align: center;
  font-size: 0.95rem;
  color: var(--kcgl-color-text-sub);
}

.print-sheets {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  background: #eceef1;
  overflow-x: auto;
}

@media print {
  .print-toolbar,
  .print-guide,
  .print-empty {
    display: none;
  }

  .print-view {
    gap: 0;
  }

  .print-sheets {
    display: block;
    padding: 0;
    background: none;
    overflow: visible;
  }
}
</style>

<style>
/* @page 无选择器作用域，必须全局；仅本路由挂载时生效 */
@page {
  size: A4;
  margin: 0;
}

@media print {
  /* 去除应用壳与桌面容器的屏幕留白，sheet 直取整页（mm 精确） */
  .shell-header,
  .shell-sidebar {
    display: none !important;
  }

  .shell-body {
    flex-direction: column;
  }

  .shell-main {
    max-width: none !important;
    padding: 0 !important;
  }

  body {
    background: #fff !important;
  }
}
</style>
