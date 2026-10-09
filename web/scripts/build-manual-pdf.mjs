import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { chromium } from '@playwright/test'

/**
 * 甲方用操作手册 PDF（M7）：读 web/src/manual/sections/*.json，出中日英三份 A4 PDF。
 *
 * 与 `npm run manual:pdf` 同名的一个事实源：应用内手册页与这份 PDF 读的是同一批
 * JSON（manual.spec.ts 已在单测层守住其形状）。两处各写一份必然会漂移——改一处
 * 忘了另一处，甲方拿到的文档和现场看到的界面就对不上。
 *
 * 与页内手册的区别：**这里不过滤**。甲方要看的是全貌（谁会用到哪一章），所以每个
 * 章/功能/步骤都带角色与设备徽标，让「管理员专属」「手机专用」在纸面上一眼可辨。
 *
 * 为什么用 Playwright 而不是 pandoc/其他 md→pdf：仓库里已有 @playwright/test 与
 * 已下载的 chromium（E2E 用），page.pdf() 零新依赖。装一套 LaTeX/weasyprint 只为
 * 出三份文档，不划算且给交付环境添负担。
 *
 * 幂等可重跑：cd web && npm run manual:pdf
 */

const LANGS = ['ja', 'zh', 'en']
/** 交付包装进 docs/ 时用的文件名，ja 在前（甲方主要读日文） */
const FILE_SUFFIX = { ja: 'ja', zh: 'zh', en: 'en' }

const SECTIONS_DIR = fileURLToPath(new URL('../src/manual/sections/', import.meta.url))
const OUT_DIR = fileURLToPath(new URL('../../docs/', import.meta.url))

/** 章节顺序与 web/src/manual/index.ts 的 import 顺序一致（文件名前缀即排序） */
const SECTION_FILES = [
  '01-start.json',
  '02-mobile.json',
  '03-desktop.json',
  '04-yahoo.json',
  '05-excel.json',
  '06-admin.json',
  '07-monitor.json',
  '08-concepts.json',
]

/** 手册自身的界面文案（正文三语在 JSON 里，这些是排版用的外围字） */
const LABELS = {
  ja: {
    title: '操作マニュアル',
    lead: 'このシステムの操作方法を、機能ごとにまとめました。「誰が」「どの端末で」使う操作かは、各項目の右のラベルで分かります。',
    toc: 'もくじ',
    legend: 'ラベルの見方',
    legendRole: 'ロール（権限）',
    legendDevice: '端末',
    roleAdmin: '管理者のみ',
    roleEditor: '編集者以上',
    roleViewer: '閲覧者向け',
    devMobile: 'スマホ',
    devDesktop: 'パソコン',
    generatedAt: '作成日',
  },
  zh: {
    title: '操作手册',
    lead: '这里按功能汇总了本系统的操作方法。「谁」「在哪台设备上」使用该操作，看每项右侧的标记即可。',
    toc: '目录',
    legend: '标记说明',
    legendRole: '角色（权限）',
    legendDevice: '设备',
    roleAdmin: '仅管理员',
    roleEditor: '编辑者及以上',
    roleViewer: '查看者',
    devMobile: '手机',
    devDesktop: '电脑',
    generatedAt: '编制日期',
  },
  en: {
    title: 'Operation Manual',
    lead: 'How to use this system, feature by feature. The labels to the right of each item show who uses it and on which device.',
    toc: 'Contents',
    legend: 'How to read the labels',
    legendRole: 'Role (permission)',
    legendDevice: 'Device',
    roleAdmin: 'Admins only',
    roleEditor: 'Editors and above',
    roleViewer: 'For viewers',
    devMobile: 'Phone',
    devDesktop: 'PC',
    generatedAt: 'Prepared',
  },
}

const DEVICE_LABEL_KEY = { mobile: 'devMobile', desktop: 'devDesktop' }

/** 品牌主蓝，与 web/src/styles/brand.css 一致（纸面与界面同一套色） */
const BRAND = '#2062a6'
const BRAND_DARK = '#1a4f86'
const INK = '#1f2933'
const INK_SUB = '#5b6874'
const LINE = '#d5dce3'
const FILL = '#f4f6f8'
const INFO_BG = '#eef4fb'
const INFO_BORDER = '#c3d7ec'

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 角色徽标：全员（含三值）与未标注都不出标——满篇都是徽标等于没有徽标 */
function roleBadge(roles, labels) {
  if (!roles || roles.length === 0 || roles.length === 3) return ''
  let text
  if (roles.length === 1 && roles[0] === 1) text = labels.roleAdmin
  else if (roles.length === 1 && roles[0] === 3) text = labels.roleViewer
  else text = labels.roleEditor
  return `<span class="badge badge-role">${escapeHtml(text)}</span>`
}

/** 设备徽标：只在确实限定了单端时出 */
function deviceBadge(devices, labels) {
  if (!devices || devices.length === 0 || devices.length === 2) return ''
  const key = DEVICE_LABEL_KEY[devices[0]]
  if (!key) return ''
  return `<span class="badge badge-device">${escapeHtml(labels[key])}</span>`
}

function phrases(texts) {
  return (texts ?? []).map((item) => `<li>${escapeHtml(item)}</li>`).join('')
}

function renderStep(step, lang, labels) {
  const body = step.body ? `<p class="step-body">${escapeHtml(step.body[lang])}</p>` : ''
  const bullets = step.bullets
    ? `<ul class="step-bullets">${phrases(step.bullets[lang])}</ul>`
    : ''
  const note = step.note ? `<p class="step-note">${escapeHtml(step.note[lang])}</p>` : ''
  const badges = roleBadge(step.roles, labels) + deviceBadge(step.devices, labels)
  return `<li class="step">
      <p class="step-title">${escapeHtml(step.title[lang])}${badges}</p>
      ${body}${bullets}${note}
    </li>`
}

function renderFeature(feature, lang, labels) {
  const intro = feature.intro ? `<p class="feature-intro">${escapeHtml(feature.intro[lang])}</p>` : ''
  const badges = roleBadge(feature.roles, labels) + deviceBadge(feature.devices, labels)
  const steps = feature.steps.map((step) => renderStep(step, lang, labels)).join('\n')
  return `<article class="feature">
      <h3 class="feature-title">${escapeHtml(feature.title[lang])}${badges}</h3>
      ${intro}
      <ol class="steps">
${steps}
      </ol>
    </article>`
}

function renderSection(section, index, lang, labels) {
  const intro = section.intro ? `<p class="section-intro">${escapeHtml(section.intro[lang])}</p>` : ''
  const badges = roleBadge(section.roles, labels) + deviceBadge(section.devices, labels)
  const features = section.features.map((feature) => renderFeature(feature, lang, labels)).join('\n')
  return `<section class="section" id="sec-${escapeHtml(section.id)}">
      <h2 class="section-title"><span class="section-num">${index + 1}</span>${escapeHtml(section.title[lang])}${badges}</h2>
      ${intro}
${features}
    </section>`
}

function renderDocument(sections, lang, labels, generatedAt) {
  const toc = sections
    .map(
      (section, i) =>
        `<li><a href="#sec-${escapeHtml(section.id)}"><span class="toc-num">${i + 1}</span>${escapeHtml(section.title[lang])}</a></li>`,
    )
    .join('')

  return `<!doctype html>
<html lang="${lang}">
<head>
<meta charset="utf-8">
<title>${escapeHtml(labels.title)}</title>
<style>
  @page {
    size: A4;
    margin: 18mm 16mm 16mm;
  }

  html {
    /* 拉丁字用 Segoe UI（本机已有），CJK 逐字回退到系统字——三语一份 HTML，
       ja/zh/en 各自落到能读的字形上。不引 webfont：交付机未必能联网。 */
    font-family: "Segoe UI", "Yu Gothic", Meiryo, "Microsoft YaHei", "Hiragino Sans",
      "Noto Sans CJK JP", "Noto Sans CJK SC", sans-serif;
    font-size: 10.5pt;
    line-height: 1.75;
    color: ${INK};
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }

  body { margin: 0; }

  h1, h2, h3 { line-height: 1.4; }

  /* ---------- 封面区 ---------- */
  .cover {
    padding-bottom: 10mm;
    border-bottom: 2px solid ${BRAND};
    margin-bottom: 8mm;
  }

  .cover h1 {
    margin: 0;
    font-size: 24pt;
    font-weight: 700;
    color: ${BRAND_DARK};
    letter-spacing: 0.01em;
  }

  .cover .lead {
    margin: 4mm 0 0;
    max-width: 150mm;
    color: ${INK_SUB};
  }

  .cover .meta {
    margin: 3mm 0 0;
    font-size: 9pt;
    color: ${INK_SUB};
  }

  /* ---------- 徽标说明 ---------- */
  .legend {
    margin: 0 0 8mm;
    padding: 3mm 4mm;
    border: 1px solid ${LINE};
    border-radius: 2mm;
    background: ${FILL};
    font-size: 9pt;
    color: ${INK_SUB};
  }

  .legend p { margin: 0 0 1.5mm; }
  .legend p:last-child { margin-bottom: 0; }
  .legend strong { color: ${INK}; }

  /* ---------- 目录 ---------- */
  .toc {
    break-after: page;
  }

  .toc h2 {
    margin: 0 0 3mm;
    font-size: 13pt;
    color: ${BRAND_DARK};
  }

  .toc ol {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .toc li {
    padding: 1.6mm 0;
    border-bottom: 1px solid ${LINE};
  }

  .toc a {
    display: flex;
    gap: 3mm;
    color: ${INK};
    text-decoration: none;
  }

  .toc-num {
    flex-shrink: 0;
    width: 6mm;
    color: ${BRAND};
    font-weight: 600;
  }

  /* ---------- 章 ---------- */
  .section {
    break-before: page;
  }

  .section-title {
    display: flex;
    align-items: baseline;
    gap: 3mm;
    margin: 0;
    padding-bottom: 2mm;
    border-bottom: 1px solid ${LINE};
    font-size: 15pt;
    color: ${BRAND_DARK};
  }

  .section-num {
    flex-shrink: 0;
    font-size: 11pt;
    font-weight: 700;
    color: ${BRAND};
  }

  .section-intro {
    margin: 3mm 0 0;
    max-width: 150mm;
    color: ${INK_SUB};
  }

  /* ---------- 功能 ---------- */
  .feature {
    margin-top: 6mm;
    /* 一个功能尽量不跨页：跨页后「步骤 3」出现在下一页开头，读者会以为步骤丢了 */
    break-inside: avoid;
  }

  .feature-title {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 2mm;
    margin: 0;
    font-size: 12pt;
  }

  .feature-intro {
    margin: 2mm 0 0;
    max-width: 150mm;
    color: ${INK_SUB};
  }

  /* ---------- 步骤 ---------- */
  .steps {
    margin: 3mm 0 0;
    padding-left: 7mm;
  }

  .step { break-inside: avoid; }
  .step + .step { margin-top: 4mm; }

  .step-title {
    margin: 0;
    font-weight: 600;
  }

  .step-body {
    margin: 1mm 0 0;
    max-width: 150mm;
  }

  .step-bullets {
    margin: 1.5mm 0 0;
    padding-left: 5mm;
    max-width: 150mm;
    color: ${INK_SUB};
    font-size: 9.5pt;
  }

  .step-bullets li + li { margin-top: 1mm; }

  /* 提醒：整块浅底 + 完整边框。不设 margin-left → 与步骤文字对齐，不缩进 */
  .step-note {
    margin: 2mm 0 0;
    padding: 2mm 3mm;
    max-width: 150mm;
    border: 1px solid ${INFO_BORDER};
    border-radius: 1.5mm;
    background: ${INFO_BG};
    color: ${BRAND_DARK};
    font-size: 9.5pt;
    break-inside: avoid;
  }

  /* ---------- 徽标 ---------- */
  .badge {
    display: inline-block;
    margin-left: 2mm;
    padding: 0.2mm 2mm;
    border-radius: 3mm;
    font-size: 8pt;
    font-weight: 500;
    line-height: 1.6;
    white-space: nowrap;
    vertical-align: baseline;
  }

  .badge-role {
    border: 1px solid ${LINE};
    background: ${FILL};
    color: ${INK_SUB};
  }

  .badge-device {
    border: 1px solid ${INFO_BORDER};
    background: ${INFO_BG};
    color: ${BRAND_DARK};
  }
</style>
</head>
<body>
  <header class="cover">
    <h1>${escapeHtml(labels.title)}</h1>
    <p class="lead">${escapeHtml(labels.lead)}</p>
    <p class="meta">${escapeHtml(labels.generatedAt)}: ${escapeHtml(generatedAt)}</p>
  </header>

  <div class="legend">
    <p><strong>${escapeHtml(labels.legend)}</strong></p>
    <p>${escapeHtml(labels.legendRole)}: ${escapeHtml(labels.roleAdmin)} / ${escapeHtml(labels.roleEditor)} / ${escapeHtml(labels.roleViewer)}</p>
    <p>${escapeHtml(labels.legendDevice)}: ${escapeHtml(labels.devMobile)} / ${escapeHtml(labels.devDesktop)}</p>
  </div>

  <nav class="toc">
    <h2>${escapeHtml(labels.toc)}</h2>
    <ol>${toc}</ol>
  </nav>

${sections.map((section, i) => renderSection(section, i, lang, labels)).join('\n')}
</body>
</html>`
}

async function loadSections() {
  const sections = []
  for (const file of SECTION_FILES) {
    const raw = await readFile(path.join(SECTIONS_DIR, file), 'utf8')
    sections.push(JSON.parse(raw))
  }
  return sections
}

/** 页脚页码：Chromium 的 footerTemplate 用 pageNumber/totalPages 类名，样式须内联 */
function footerTemplate() {
  return `<div style="width:100%;margin:0 16mm;font-size:8pt;color:${INK_SUB};
    display:flex;justify-content:flex-end;">
    <span><span class="pageNumber"></span> / <span class="totalPages"></span></span>
  </div>`
}

async function main() {
  const sections = await loadSections()
  await mkdir(OUT_DIR, { recursive: true })

  // en-CA 的短日期就是 YYYY-MM-DD：三语一份、无月名缩写（dateStyle:'medium' 在本机
  // 实测吐出法语月名 "oct."，这是不可控的 ICU 回退），且日本票据同样认这个写法。
  const generatedAt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo',
  }).format(new Date())

  const browser = await chromium.launch()
  const page = await browser.newPage()

  const written = []
  for (const lang of LANGS) {
    const labels = LABELS[lang]
    const html = renderDocument(sections, lang, labels, generatedAt)
    // 中间 HTML 落系统临时目录而非 docs/：docs/ 是交付物目录，多留一个中间产物
    // 会让人分不清哪个是甲方要的东西。删不删无所谓，系统会自己清。
    // 用 file:// 加载而非 setContent：内部锚点链接（目录跳章）需要真实 URL 才成为
    // PDF 里的可点链接，setContent 的 about:blank 底址下锚点不生效。
    const htmlPath = path.join(tmpdir(), `kcgl-manual-${lang}.html`)
    await writeFile(htmlPath, html, 'utf8')
    await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'load' })

    const file = path.join(OUT_DIR, `操作手順書-${FILE_SUFFIX[lang]}.pdf`)
    await page.pdf({
      path: file,
      format: 'A4',
      printBackground: true,
      displayHeaderFooter: true,
      headerTemplate: '<div></div>',
      footerTemplate: footerTemplate(lang),
      margin: { top: '18mm', bottom: '16mm', left: '16mm', right: '16mm' },
    })
    written.push(file)
  }

  await browser.close()

  const counts = sections.map((section) => section.features.length)
  console.log(
    `[manual-pdf] ${sections.length} 章 / 共 ${counts.reduce((a, b) => a + b, 0)} 功能，已出三语 PDF：`,
  )
  for (const file of written) {
    console.log(`  ${file}`)
  }
}

main().catch((error) => {
  console.error('[manual-pdf] 失败：', error)
  process.exitCode = 1
})
