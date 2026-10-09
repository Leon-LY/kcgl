#!/usr/bin/env node
/**
 * 操作手册截图生成（M7 追加交付物）：把 34 个功能各截一张**日文界面**图，落到
 * web/src/manual/screenshots/<章>/<功能>.jpg，供应用内手册页与甲方 PDF 共用
 * （sections/*.json 里每个功能的 `image` 字段指的就是这里的相对路径）。
 *
 * 为什么截图也要「跑脚本」而不是手截：
 *   ① 界面一改手册图就过期，手截的图没人知道是哪一版、也没法批量重来；
 *   ② 手截的图里会出现开发者的真实账号/真实数据——本脚本造的是**虚构演示数据**，
 *      落在一次性 E2E 库里，出图可公开。
 *
 * 前置：先起常驻 E2E 栈 —— `npm run e2e:stack`（后端 :18080）。
 *   本脚本自己拉一个 vite（:5173，代理到 18080）。库脏了先 `npm run e2e:reset`。
 *
 * 幂等性：会场/档位"有就跳过"，但商品是**新增**——重复跑会累积演示商品。
 * 要干净结果，先 `npm run e2e:reset` 再跑本脚本。
 *
 * 用法：cd web && node scripts/capture-manual-shots.mjs
 */
import { spawn, spawnSync } from 'node:child_process'
import { mkdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { chromium } from '@playwright/test'
import ExcelJS from 'exceljs'
import { APP_PORT, WEB_PORT, hasStackMarker, readStackMarker } from '../e2e/stack-shared.mjs'

const WEB_DIR = fileURLToPath(new URL('..', import.meta.url))
const SHOTS_DIR = fileURLToPath(new URL('../src/manual/screenshots/', import.meta.url))
const API = `http://127.0.0.1:${APP_PORT}`
const WEB = `http://127.0.0.1:${WEB_PORT}`

const E2E_PASSWORD = 'e2e-pass-123456'

const DESKTOP_VIEWPORT = { width: 1440, height: 900 }
const MOBILE_VIEWPORT = { width: 390, height: 844 }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** JST 日历日（YYYY-MM-DD），offsetDays 可回溯——与 arrival.spec 同一写法 */
function jstDate(offsetDays = 0) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(Date.now() + offsetDays * 86_400_000))
}

async function urlOk(url) {
  try {
    return (await fetch(url)).ok
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// vite：常驻栈不含前端，这里自己拉一个（代理到 18080）
// ---------------------------------------------------------------------------
async function startVite() {
  if (await urlOk(WEB)) {
    console.log('[shots] vite 已在跑，复用')
    return null
  }
  const child = spawn('npm', ['run', 'dev', '--', '--port', String(WEB_PORT), '--strictPort'], {
    cwd: WEB_DIR,
    env: { ...process.env, KCGL_API_TARGET: API },
    shell: true,
    stdio: 'ignore',
    detached: false,
  })
  process.stdout.write('[shots] 起 vite')
  for (let i = 0; i < 60; i++) {
    await sleep(1000)
    if (await urlOk(WEB)) {
      console.log(' 就绪')
      return child
    }
    process.stdout.write('.')
  }
  console.log('')
  throw new Error(`vite 起不来（${WEB}）`)
}

function stopVite(child) {
  if (!child) return
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    return
  }
  try {
    process.kill(-child.pid, 'SIGTERM')
  } catch {
    child.kill('SIGTERM')
  }
}

// ---------------------------------------------------------------------------
// API：统一信封 { code, message, data }
// ---------------------------------------------------------------------------
async function api(page, method, url, options = {}) {
  const res = await page.request[method](url, options)
  const text = await res.text()
  let body = null
  try {
    body = JSON.parse(text)
  } catch {
    /* 非 JSON（如文件流） */
  }
  if (!res.ok() || !body || body.code !== 0) {
    throw new Error(`API ${method.toUpperCase()} ${url} → ${res.status()} ${text.slice(0, 300)}`)
  }
  return body.data
}

const post = (page, url, data) => api(page, 'post', url, { data })
const get = (page, url) => api(page, 'get', url)

// ---------------------------------------------------------------------------
// 演示数据（全部虚构）
// ---------------------------------------------------------------------------
const VENUES = [
  ['NA', '名古屋オークション'],
  ['FK', '福岡骨董市'],
  ['KY', '京都古美術展'],
  ['OS', '大阪骨董市'],
]

/** 档位左闭右开；仓库内已有 X[0,3000) 由 e2e 种子创建 */
const BANDS = [
  ['Y', 3000, 10000],
  ['Z', 10000, 50000],
  ['W', 50000, 99_999_999],
]

/** 虚构商品：日式古董，价格落在上面各档位内 */
const ITEMS = [
  ['NA', 1200, 1, '古伊万里 染付 大皿', '陶磁器', '伊万里', '径32cm', 1800],
  ['NA', 2800, 1, '備前焼 花入', '陶磁器', '備前', '高24cm', 1600],
  ['FK', 4500, 2, '南部鉄瓶 1.2L', '金工', '南部', '径16cm', 1400],
  ['KY', 6800, 1, '螺鈿 硯箱', '漆器', '幕末', '24×18cm', 900],
  ['KY', 12_000, 2, '九谷焼 色絵 花瓶', '陶磁器', '九谷', '高30cm', 2200],
  ['OS', 25_000, 1, '大正期 桐箪笥', '木工', '大正', '幅90cm', 18_000],
  ['OS', 38_000, 2, '銀製 茶托 五客', '金工', '明治', '径11cm', 700],
  ['NA', 60_000, 1, '有田焼 染付 大皿 五枚', '陶磁器', '有田', '径28cm', 5200],
  ['FK', 85_000, 2, '古文書 一行書', '書蹟', '江戸', '135×34cm', 300],
  ['FK', 1500, 2, '肥前 磁器 小鉢', '陶磁器', '肥前', '径12cm', 900],
  ['KY', 9000, 1, '竹編 花籠', '竹工', '昭和', '高22cm', 800],
  ['OS', 20_000, 2, '真鍮 火鉢', '金工', '昭和初期', '径28cm', 3400],
]

async function ensureDicts(page) {
  let venues = await get(page, `${API}/api/venues?enabled=true`)
  for (const [code, name] of VENUES) {
    if (!venues.some((v) => v.code === code)) {
      await post(page, `${API}/api/venues`, { code, name })
    }
  }
  venues = await get(page, `${API}/api/venues?enabled=true`)
  const venueId = Object.fromEntries(venues.map((v) => [v.code, v.id]))

  let bands = await get(page, `${API}/api/price-bands`)
  for (const [code, lowerBound, upperBound] of BANDS) {
    if (!bands.some((b) => b.code === code)) {
      await post(page, `${API}/api/price-bands`, { code, lowerBound, upperBound })
    }
  }
  return venueId
}

/** 造商品 + 把它们推进到各种状态（覆盖手册要展示的全部状态） */
async function seedItems(page, venueId) {
  const created = []
  for (const [venue, price, warehouse, itemName, category, authorKiln, sizeText, weightG] of ITEMS) {
    const item = await post(page, `${API}/api/items`, {
      clientReqId: crypto.randomUUID(),
      venueId: venueId[venue],
      buyDate: jstDate(-45),
      purchasePrice: price,
      warehouse,
      itemName,
      category,
      authorKiln,
      sizeText,
      weightG,
    })
    created.push(item)
  }

  const at = (i) => created[i]
  const arrive = (item, inDate) =>
    post(page, `${API}/api/inventory/arrivals`, {
      items: [{ itemId: item.id, clientReqId: crypto.randomUUID() }],
      warehouseInDate: inDate,
    })

  // 在庫・旧入库（触发滞销黄/赤：默认阈值 warn30 / alarm90）
  await arrive(at(0), jstDate(-40)) // 黄
  await arrive(at(1), jstDate(-40)) // 黄
  await arrive(at(2), jstDate(-120)) // 赤（长期滞留）
  // 在庫・新入库
  await arrive(at(3), jstDate(0))
  await arrive(at(4), jstDate(0))
  await arrive(at(5), jstDate(-10))
  // 留 2 件在途（到货核对页的在途清单）
  // at(6) at(7) 不动
  //
  // 以下每个动作都以「在庫(stock=1)」为前置：InventoryStateMachine 的边表里
  // SELL/LIST_UP/CANCEL_MARK 的 stockFrom 全是 1——没到货就调用必然 409008
  // （踩过一次：sell 打在在途件上，脚本中断）。所以先在途→在庫，再推状态。
  await arrive(at(8), jstDate(-5))
  // 在庫 → 已出庫（成交）
  await post(page, `${API}/api/inventory/sell`, {
    itemId: at(8).id,
    clientReqId: crypto.randomUUID(),
    soldPrice: 120_000,
  })
  // 在庫 → 出品中
  await arrive(at(9), jstDate(-3))
  await post(page, `${API}/api/inventory/mark-listed`, {
    itemId: at(9).id,
    clientReqId: crypto.randomUUID(),
  })
  // 在庫 → 出品中 → キャンセル
  await arrive(at(10), jstDate(-3))
  await post(page, `${API}/api/inventory/mark-listed`, {
    itemId: at(10).id,
    clientReqId: crypto.randomUUID(),
  })
  await post(page, `${API}/api/inventory/mark-canceled`, {
    itemId: at(10).id,
    clientReqId: crypto.randomUUID(),
  })
  // 作废一件（展示「取消済み」状态）
  await post(page, `${API}/api/items/${at(11).id}/void`, {
    clientReqId: crypto.randomUUID(),
    reason: '会場の登録を誤ったため',
  })

  return created
}

/** 雅虎受注 xlsx：命中一件在庫商品 → 出荷待ち队列；另一行未登记（照合视图用） */
async function orderXlsx(itemCode) {
  const HEADER = [
    'OrderId', 'YahooAuctionMerchantId', 'OrderTime', 'YahooAuctionId',
    'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12', 'F13', 'F14', 'F15',
    'UnitPrice', 'F17', 'F18', 'F19', 'F20', 'F21',
  ]
  const row = (orderId, codes, serialTime, auctionId, unitPrice) => {
    const cells = Array.from({ length: 21 }, () => '')
    cells[0] = orderId
    cells[1] = codes
    cells[2] = serialTime
    cells[3] = auctionId
    cells[15] = unitPrice
    return cells
  }
  const workbook = new ExcelJS.Workbook()
  const sheet = workbook.addWorksheet('受注')
  sheet.addRow([...HEADER])
  sheet.addRow(row('10004866', itemCode, '46034.875', 'k1122334455', '25000'))
  sheet.addRow(row('10004867', 'ZZZZ-ZZ9X', '46034.875', 'k9988776655', '2000'))
  return Buffer.from(await workbook.xlsx.writeBuffer())
}

/**
 * 演示照片：复用 E2E 的 TEST_JPEG（e2e/fixtures.ts 里那张 320×240 的合成图）。
 * 不另抄一份 base64——同一张测试图只该有一个来源；字面量读不到就硬失败，
 * 免得悄悄传一张空图上去、手册里出现「写真はありません」。
 */
async function testJpeg() {
  const src = await readFile(fileURLToPath(new URL('../e2e/fixtures.ts', import.meta.url)), 'utf8')
  const found = /export const TEST_JPEG = Buffer\.from\(\s*'([^']+)',\s*'base64'/.exec(src)
  if (!found) throw new Error('没能从 e2e/fixtures.ts 读出 TEST_JPEG（写法变了？）')
  return Buffer.from(found[1], 'base64')
}

/** 给某件商品挂一张照片（手册「写真を撮る／写真を入れ替える」两步要在图里看得见） */
async function seedPhoto(page, item) {
  await api(page, 'post', `${API}/api/images`, {
    multipart: {
      file: { name: 'photo.jpg', mimeType: 'image/jpeg', buffer: await testJpeg() },
      clientUuid: crypto.randomUUID(),
      itemId: String(item.id),
    },
  })
}

async function seedYahoo(page, items) {
  // 找一件在庫商品做受注命中（at(3) 新入库在庫）
  const target = items[3]
  const bytes = await orderXlsx(target.itemCode)
  await api(page, 'post', `${API}/api/yahoo/imports`, {
    multipart: {
      file: {
        name: 'ストア9.20(1).xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: bytes,
      },
    },
  })
}

// ---------------------------------------------------------------------------
// 截图清单：手册的功能各一张
// ---------------------------------------------------------------------------
/**
 * 未登录态的两张（登录页与语言切换都在登录页上）——必须在登录**之前**拍，
 * 否则路由守卫会把 /login 直接重定向到首页。
 */
const ANON_SHOTS = [
  { file: '01-start/login.jpg', shell: 'desktop', path: '/login' },
  // 语言切换：原生 select 的下拉由操作系统绘制，Playwright 截不到展开态，
  // 所以改拍「控件所在的位置」——顶栏右侧那一带（含账号名与ヘルプ），
  // 读者一眼就知道去哪儿切。与右上角同款的登录页那张靠裁切区分开。
  {
    file: '01-start/language.jpg',
    shell: 'desktop',
    path: '/login',
    clipTo: { selector: '.lang-switch', pad: { top: 16, right: 60, bottom: 16, left: 430 } },
  },
]

/** @type {{file:string, shell:'desktop'|'mobile', path:string, tab?:string, filter?:{selector:string,option:string}, clipTo?:{selector:string,last?:boolean,pad?:number|{top:number,right:number,bottom:number,left:number}}, waitFor?:string, before?:Function}[]} */
const SHOTS = [
  // 1 章 はじめに（登录页两张见上）
  { file: '01-start/first-password.jpg', shell: 'desktop', path: '/change-password' },
  // 「スマホのホーム画面に追加」＝端末のブラウザメニューの操作で、アプリの画面ではない。
  // ヘッドレス Chromium にブラウザ UI が無く撮る手段が存在しないため**意図的に図を付けない**
  // （D-160）。手順の文面だけで足りる機能。
  // 表示切替：切り替えボタンだけを切り出して撮る（ダッシュボード全体だと 03 章と同一画像になる）。
  {
    file: '01-start/shell-switch.jpg',
    shell: 'desktop',
    path: '/dashboard',
    clipTo: { selector: '.shell-mobile-switch', pad: 40 },
  },

  // 2 章 スマホで使う
  { file: '02-mobile/home.jpg', shell: 'mobile', path: '/' },
  { file: '02-mobile/entry.jpg', shell: 'mobile', path: '/entry' },
  { file: '02-mobile/arrival.jpg', shell: 'mobile', path: '/arrival' },
  { file: '02-mobile/scan.jpg', shell: 'mobile', path: '/scan' },
  { file: '02-mobile/stocktake.jpg', shell: 'mobile', path: '/stocktake' },
  { file: '02-mobile/pending-shipments.jpg', shell: 'mobile', path: '/pending-shipments' },
  { file: '02-mobile/today.jpg', shell: 'mobile', path: '/today' },

  // 3 章 パソコンで使う
  { file: '03-desktop/dashboard.jpg', shell: 'desktop', path: '/dashboard' },
  { file: '03-desktop/items.jpg', shell: 'desktop', path: '/items' },
  { file: '03-desktop/item-detail.jpg', shell: 'desktop', path: '/items/__ITEM__' },
  { file: '03-desktop/print.jpg', shell: 'desktop', path: '/print' },

  // 4 章 ヤフー連携（同一页面三个页签，各自拍各自那页）
  { file: '04-yahoo/yahoo-import.jpg', shell: 'desktop', path: '/yahoo', tab: 'import' },
  { file: '04-yahoo/yahoo-shipments.jpg', shell: 'desktop', path: '/yahoo', tab: 'shipments' },
  { file: '04-yahoo/yahoo-reconcile.jpg', shell: 'desktop', path: '/yahoo', tab: 'reconcile' },

  // 5 章 エクセル連携（同页两个页签）
  { file: '05-excel/excel-import.jpg', shell: 'desktop', path: '/excel', tab: 'import' },
  { file: '05-excel/excel-export.jpg', shell: 'desktop', path: '/excel', tab: 'export' },

  // 6 章 管理機能
  { file: '06-admin/venues.jpg', shell: 'desktop', path: '/admin/venues' },
  { file: '06-admin/price-bands.jpg', shell: 'desktop', path: '/admin/price-bands' },
  { file: '06-admin/users.jpg', shell: 'desktop', path: '/admin/users' },
  { file: '06-admin/settings.jpg', shell: 'desktop', path: '/admin/settings' },

  // 7 章 監視・ガバナンス
  { file: '07-monitor/ledgers.jpg', shell: 'desktop', path: '/ledgers' },
  { file: '07-monitor/operation-logs.jpg', shell: 'desktop', path: '/admin/logs' },
  { file: '07-monitor/system-status.jpg', shell: 'desktop', path: '/admin/system' },

  // 8 章 用語と状態（概念の章：同じ画面でも「何を見せるか」を変えて撮る）
  {
    file: '08-concepts/item-code.jpg',
    shell: 'desktop',
    path: '/items/__ITEM__',
    clipTo: { selector: '.itemd-code', pad: { top: 90, right: 280, bottom: 60, left: 90 } },
  },
  {
    file: '08-concepts/qr.jpg',
    shell: 'desktop',
    path: '/print',
    clipTo: { selector: '.print-sheets', pad: 24 },
  },
  {
    file: '08-concepts/stock-status.jpg',
    shell: 'desktop',
    path: '/items',
    filter: { selector: '.items-filter-stock', option: '在庫' },
  },
  {
    file: '08-concepts/sale-status.jpg',
    shell: 'desktop',
    path: '/items',
    filter: { selector: '.items-filter-sale', option: '出品中' },
  },
  {
    file: '08-concepts/warehouses.jpg',
    shell: 'desktop',
    path: '/dashboard',
    // ダッシュボードの「倉庫別サマリー」カードだけを切り出す
    clipTo: { selector: '.dashboard-card', last: true, pad: 16 },
  },
  {
    file: '08-concepts/slow-moving.jpg',
    shell: 'desktop',
    path: '/items',
    filter: { selector: '.items-filter-slow', option: '長期滞留（赤）' },
  },
]

/** 等页面挂载且换页动效走完（与 e2e/fixtures.ts 的 waitForViewSettled 同一判据） */
async function settle(page, waitFor) {
  await page.waitForSelector('.kcgl-view-slot', { timeout: 20_000 })
  await page.waitForFunction(
    () => getComputedStyle(document.querySelector('.kcgl-view-slot')).transform === 'none',
    null,
    { timeout: 20_000 },
  )
  if (waitFor) {
    await page.waitForSelector(waitFor, { timeout: 20_000 })
  }
  await page.waitForTimeout(500)
}

function shotUrl(entry) {
  const [pathname, query] = entry.path.split('?')
  const params = new URLSearchParams(query ?? '')
  params.set('shell', entry.shell)
  return `${WEB}${pathname}?${params.toString()}`
}

// ---------------------------------------------------------------------------
/** 起过的 vite 句柄：出错路径也要能收掉它，否则子进程吊着事件循环、脚本永不退出 */
let activeVite = null

async function main() {
  if (!hasStackMarker()) {
    console.error('[shots] 没有常驻 E2E 栈。先 `npm run e2e:stack`（后端 :18080）。')
    process.exitCode = 1
    return
  }
  console.log(`[shots] 复用常驻栈（pid ${readStackMarker()?.pid}，后端 :${APP_PORT}）`)

  await mkdir(SHOTS_DIR, { recursive: true })
  const vite = await startVite()
  activeVite = vite

  const browser = await chromium.launch()
  const context = await browser.newContext({
    baseURL: WEB,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport: DESKTOP_VIEWPORT,
  })
  // 主屏安装引导条在每次新页面挂载后都可能浮出，会盖住手机截图的页头——而
  // 手册里「添加到主屏幕」是另一条功能（且它本身无图），故全站预置「後で」。
  // 用 addInitScript 而非逐页点按钮：它是 localStorage 记忆，写一次即可全站生效，
  // 也不必和挂载时序赛跑。注意队列警示条不可关闭，这里压不掉。
  await context.addInitScript(() => {
    try {
      localStorage.setItem('kcgl-pwa-guide-dismissed', '1')
    } catch {
      /* 隐私模式下 localStorage 可能不可用；截图脚本无需为此中断 */
    }
  })

  const page = await context.newPage()

  let loggedIn = false
  const login = async () => {
    if (loggedIn) return
    await page.goto(`${WEB}/login?shell=desktop`)
    await page.fill('#login-username', 'admin')
    await page.fill('#login-password', E2E_PASSWORD)
    await page.getByRole('button', { name: 'ログイン' }).click()
    await page.waitForSelector('.dashboard-view, .home-welcome', { timeout: 20_000 })
    loggedIn = true
  }

  /** 切到指定页签（Element Plus 的 el-tabs 会给页签头挂 id="tab-<name>"） */
  const openTab = async (name) => {
    await page.locator(`#tab-${name}`).click()
    await page.waitForTimeout(400)
  }

  /** 设一个下拉筛选：点开 → 选文本命中的那一项 */
  const applyFilter = async (filter) => {
    await page.locator(filter.selector).click()
    await page.locator('.el-select-dropdown__item', { hasText: filter.option }).first().click()
    await page.waitForTimeout(400)
  }

  /** 局部裁切：把元素外扩 pad 像素后只截这一块（概念章最怕「一张全屏图讲不清看哪儿」） */
  const clipRect = async (clipTo) => {
    const locator = clipTo.last ? page.locator(clipTo.selector).last() : page.locator(clipTo.selector).first()
    await locator.scrollIntoViewIfNeeded()
    const box = await locator.boundingBox()
    if (!box) throw new Error(`裁切目标没有尺寸：${clipTo.selector}`)
    const p = clipTo.pad ?? 0
    const pad =
      typeof p === 'number'
        ? { top: p, right: p, bottom: p, left: p }
        : { top: p.top ?? 0, right: p.right ?? 0, bottom: p.bottom ?? 0, left: p.left ?? 0 }
    const size = page.viewportSize()
    const x = Math.max(0, box.x - pad.left)
    const y = Math.max(0, box.y - pad.top)
    return {
      x,
      y,
      width: Math.min(size.width - x, box.width + pad.left + pad.right),
      height: Math.min(size.height - y, box.height + pad.top + pad.bottom),
    }
  }

  let done = 0
  const failures = []
  const shoot = async (entry, detailId) => {
    const file = path.join(SHOTS_DIR, entry.file)
    await mkdir(path.dirname(file), { recursive: true })
    try {
      await page.setViewportSize(entry.shell === 'mobile' ? MOBILE_VIEWPORT : DESKTOP_VIEWPORT)
      const url = shotUrl({ ...entry, path: entry.path.replace('__ITEM__', String(detailId)) })
      await page.goto(url)
      await settle(page, entry.waitFor)
      if (entry.tab) {
        await openTab(entry.tab)
      }
      if (entry.filter) {
        await applyFilter(entry.filter)
        // 筛选会重检索，等表格重画完再截
        await page.waitForTimeout(600)
      }
      if (entry.before) {
        await entry.before(page)
        await page.waitForTimeout(400)
      }
      const clip = entry.clipTo ? await clipRect(entry.clipTo) : undefined
      await page.screenshot({ path: file, type: 'jpeg', quality: 82, clip })
      done++
      console.log(`  ✓ ${entry.file}`)
    } catch (error) {
      failures.push(`${entry.file}: ${error.message}`)
      console.log(`  ✗ ${entry.file} — ${error.message}`)
    }
  }

  // --- 阶段一：未登录态两张
  for (const entry of ANON_SHOTS) {
    await shoot(entry, 0)
  }

  // --- 阶段二：演示数据（登录后经 API 造；page.request 与浏览器同一 cookie 罐）
  await login()
  const existing = await api(page, 'get', `${API}/api/items/search?page=1&size=1`)
  if ((existing?.total ?? 0) > 0) {
    // 散文警告拦不住人（本脚本自己就踩过一次：在旧库上重跑，截图里商品累积成 22 件，
    // 与已复核的 11 件对不上）。出图是交付物，脏状态必须当场说破。
    console.warn(
      `[shots] ⚠ 库里已有 ${existing.total} 件商品——本脚本的商品是**新增**，` +
        '继续跑会累积。要干净结果请先 `npm run e2e:reset`。',
    )
  }
  console.log('[shots] 造演示数据 …')
  const venueId = await ensureDicts(page)
  const items = await seedItems(page, venueId)
  await seedYahoo(page, items)
  // 详情页截图用的就是 items[3]（雅虎受注命中的那件），给它挂一张照片
  await seedPhoto(page, items[3])
  console.log(`[shots] 演示数据就绪（${items.length} 件商品）`)

  // 详情页截图要一个真实 id
  const detailId = items[3]?.id ?? items[0].id

  // --- 阶段三：预热（不带截图地把每条路由走一遍）
  // 首次访问某个路由会触发 vite 的依赖优化：发现新依赖就整页 reload，截图正好落在
  // reload 之后的空白态上——实测 /items 截出过一张只有外壳、没有表格的空图，而同一
  // 路由晚些再拍就正常。先热一遍 chunk 与依赖，再正式拍。
  console.log('[shots] 预热路由 …')
  const warmed = new Set()
  for (const entry of SHOTS) {
    const url = shotUrl({ ...entry, path: entry.path.replace('__ITEM__', String(detailId)) })
    // 同一 URL 只热一次；页签/筛选/裁切由正式拍那一轮做
    const key = `${entry.shell}|${entry.path}`
    if (warmed.has(key)) continue
    warmed.add(key)
    try {
      await page.setViewportSize(entry.shell === 'mobile' ? MOBILE_VIEWPORT : DESKTOP_VIEWPORT)
      await page.goto(url)
      await settle(page)
    } catch {
      // 预热失败不拦流程：正式拍时逐条报错，那里才是该看的信息
    }
  }
  console.log(`[shots] 预热完成（${warmed.size} 条路由）`)

  // --- 阶段四：逐条截图
  for (const entry of SHOTS) {
    await shoot(entry, detailId)
  }

  await browser.close()
  stopVite(vite)

  const total = SHOTS.length + ANON_SHOTS.length
  console.log(`\n[shots] 完成 ${done}/${total}，落盘 ${SHOTS_DIR}`)
  console.log('[shots] 注：手册共 34 功能，「スマホのホーム画面に追加」在端末のブラウザ側の操作で図が撮れないため付けない（D-160）')
  if (failures.length > 0) {
    console.log(`[shots] 未成功 ${failures.length} 条：`)
    for (const f of failures) console.log(`  - ${f}`)
  }
  // 显式退出：vite 子进程还挂着事件循环，不 exit 脚本不会自己结束
  process.exit(failures.length > 0 ? 1 : 0)
}

main().catch((error) => {
  console.error('[shots] 失败：', error)
  stopVite(activeVite)
  // 必须显式 exit：只设 exitCode 的话，vite 子进程仍吊着事件循环，脚本会「静默挂死」
  // 到被外部 timeout 杀掉——上一次就是这样白等了 600 秒还没看到任何报错。
  process.exit(1)
})
