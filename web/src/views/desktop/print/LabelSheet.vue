<script setup lang="ts">
import { computed } from 'vue'
import { formatJstDate } from '@/utils/format'
import { chunkSheets, splitItemCode, type LabelEntry, type LabelPreset } from './label'

/**
 * A4 标签页渲染（M2-7）：按预置栅格把条目排版成整页 sheet。
 * 打印页与 M5 单件重打（n=1）共用本组件；sheet/label 尺寸全部 mm 直出，
 * @page A4 margin 0 + 引导条「缩放 100%」（用户打印对话框缩放是模块尺寸偏离主因，7.7）。
 */
const props = defineProps<{
  entries: LabelEntry[]
  preset: LabelPreset
  showThumb: boolean
}>()

const sheets = computed(() => chunkSheets(props.entries, props.preset))
const thumbOn = computed(() => props.showThumb && props.preset.thumbSupported)

/** 栅格参数经 CSS 变量下发（mm 直出，打印物理尺寸即所见）。 */
const sheetStyle = computed(() => ({
  gridTemplateColumns: `repeat(${props.preset.cols}, var(--label-w))`,
  paddingTop: `${props.preset.padYMm}mm`,
  paddingBottom: `${props.preset.padYMm}mm`,
  paddingLeft: `${props.preset.padXMm}mm`,
  paddingRight: `${props.preset.padXMm}mm`,
  columnGap: `${props.preset.colGapMm}mm`,
  rowGap: `${props.preset.rowGapMm}mm`,
  '--label-w': `${props.preset.widthMm}mm`,
  '--label-h': `${props.preset.heightMm}mm`,
  '--qr-size': `${props.preset.qrMm}mm`,
  '--thumb-w': `${props.preset.thumbMm}mm`,
  '--head-pt': `${props.preset.headPt}pt`,
  '--tail-pt': `${props.preset.tailPt}pt`,
  '--date-pt': `${props.preset.datePt}pt`,
}))
</script>

<template>
  <div
    v-for="(sheet, sheetIndex) in sheets"
    :key="sheetIndex"
    class="print-sheet"
    :style="sheetStyle"
    :data-sheet="sheetIndex + 1"
  >
    <div
      v-for="entry in sheet"
      :key="entry.item.id"
      class="print-label"
    >
      <img
        v-if="entry.qr"
        class="print-qr"
        :src="entry.qr"
        :alt="entry.item.itemCode"
      >
      <span
        v-else
        class="print-qr print-qr-fallback"
      >QR</span>
      <span class="print-text">
        <span class="print-code-head">{{ splitItemCode(entry.item.itemCode).head }}</span>
        <span class="print-code-tail">{{ splitItemCode(entry.item.itemCode).tail }}</span>
        <span class="print-date">{{ formatJstDate(entry.item.buyDate) }}</span>
      </span>
      <img
        v-if="thumbOn && entry.item.thumbUrl"
        class="print-thumb"
        :src="entry.item.thumbUrl"
        :alt="entry.item.itemCode"
      >
    </div>
  </div>
</template>

<style scoped>
.print-sheet {
  width: 210mm;
  height: 297mm;
  background: #fff;
  display: grid;
  grid-auto-rows: var(--label-h);
  box-sizing: border-box;
}

.print-label {
  width: var(--label-w);
  height: var(--label-h);
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 1.5mm;
  padding: 1mm 1.5mm;
  overflow: hidden;
  break-inside: avoid;
  /* 屏显校位用虚线框；实体不干胶自带裁切线，打印时去除 */
  border: 1px dashed #c3c7cd;
}

.print-qr {
  width: var(--qr-size);
  height: var(--qr-size);
  flex: none;
}

.print-qr-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #c3c7cd;
  font-size: 6pt;
  color: #666;
}

.print-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.3mm;
  font-family: 'Courier New', monospace;
  line-height: 1.15;
}

.print-code-head {
  font-size: var(--head-pt);
  font-weight: 700;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.print-code-tail {
  font-size: var(--tail-pt);
  font-weight: 700;
  letter-spacing: 0.05em;
  white-space: nowrap;
}

.print-date {
  font-family: inherit;
  font-size: var(--date-pt);
  color: var(--kcgl-color-text-sub);
  white-space: nowrap;
}

.print-thumb {
  width: var(--thumb-w);
  height: calc(var(--label-h) - 2mm);
  flex: none;
  object-fit: cover;
  border: 1px solid #d5d8dd;
}

@media print {
  .print-label {
    border: none;
  }
}
</style>
