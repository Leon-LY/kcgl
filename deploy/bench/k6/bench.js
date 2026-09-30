// kcgl 压测脚本（M7-④，docs/01 §4.2 六门槛之前四项；SSE 另见 sse-check.sh）
//
// 用法（需先完成 seed-bench.sql + bench-images.sh + bench-verify.sql 全 OK）：
//   k6 run -e BASE=http://localhost:8082 -e K6_USERS=bench-op1,bench-op2,bench-op3,bench-op4 \
//        -e K6_PASS='...' -e VENUE_ID=1 bench.js
//
// 场景与门槛（docs/01 规格）：
//   browse    列表+筛选翻页            p95 < 500ms
//   search    关键词+组合筛选           p95 < 1s
//   dashboard 大盘                      p95 < 1s
//   entry     录入两步（preview+保存）  p95 < 300ms，吞吐 ≥ 5 件/s（10 并发）
//   附加       kcgl_session_kicked = 0（账号池须盖住 maximumSessions(8)）
//
// 注意：entry 场景会写入真实数据（在途件）——压测后按需重跑 seed-bench.sql
// 复位分布（README 执行顺序：只读门槛 → 录入门槛 → 复位）。
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:8082'
// 账号池（逗号分隔）——压测**不能只用一个账号**：SecurityConfig 的
// maximumSessions(8) 让单账号最多 8 个并发会话，第 9 个登录把最早的踢掉，被踢会话
// 此后每次请求 401。本脚本峰值 22 个 VU（browse 5 + search 5 + dashboard 2 +
// entry 10）→ 至少 3 个账号；默认 4 个（每账号 ≤6 会话）。这 4 个账号由
// seed-bench.sql 建（EDITOR 角色 + 可登录，密码见 README）。
// 本地预演实录：原先 17 VU 共用 admin → 近 10 万请求全 401、app 侧再无鉴权流量。
const ACCOUNTS = (__ENV.K6_USERS || 'bench-op1,bench-op2,bench-op3,bench-op4')
  .split(',').map((s) => s.trim()).filter(Boolean)
const K6_PASS = __ENV.K6_PASS || __ENV.ADMIN_PASS || ''
const VENUE_ID = __ENV.VENUE_ID || '1'

const entered = new Counter('kcgl_items_entered')
// 会话被踢/过期计数：账号池不够或上限被撞时它非 0——计入门槛（见 options.thresholds），
// 不让「全线 401」再退化成一行不起眼的 checks 失败
const kicked = new Counter('kcgl_session_kicked')

/** 按 VU 号轮转分摊账号，使每个账号的并发会话数 ≈ VU 总数 / 账号数 */
function accountForVU(vu) {
  return ACCOUNTS[(vu - 1) % ACCOUNTS.length]
}

// 关键词池：命中造数语料（item_name/category/remark 均有匹配项）
const KEYWORDS = ['九谷焼', '茶道具', '備前焼', '陶磁器', '棚卸し済み', '清水焼', '鉄瓶']
// 筛选池：与造数分布对齐（在库=1、已出库=2；双仓；三会场）
const FILTERS = [
  { warehouse: 1, stockStatus: 1 },
  { warehouse: 2, stockStatus: 1 },
  { stockStatus: 2 },
  { stockStatus: 0 },
  { saleStatus: 1 },
  {},
]
// 关键词检索**不能**叠 stockStatus=0（在途）：在途件是「刚录入、只登记买入信息」的
// 状态（会场/日期/价格/仓库），item_name 与 category 尚为 NULL——录入本来就不采集
// 商品名（entry 场景只 POST venueId/buyDate/purchasePrice/warehouse，与真实流程同形）。
// 实测矩阵（本地语料 5 万件，kw × 筛选的 data.total）：
//   九谷焼 × {warehouse=1&stockStatus=1、warehouse=2&stockStatus=1、stockStatus=2、
//            saleStatus=1、无筛选} = 319 / 60 / 601 / 129 / 980 —— 全部 >0
//   九谷焼 × stockStatus=0 = 0（六类目词全军覆没；同筛选下只有备注词「棚卸し済み」
//            命中 3,989——它匹配的是 remark 列）
// 首轮实测因此 95/588 ≈ 16% 的「有命中」断言失败（1/6 筛选位 × 全部类目词）——
// 这是**断言与语料语义不一致**，不是端点缺陷；若不剔除，该断言恒有一成假红、永久
// 失去检出能力（D-080 第 8 条的字符集双重编码正是靠它抓到的）。在途筛选仍由
// browse 场景覆盖（浏览不叠关键词，与真实流量同形）。
const SEARCH_FILTERS = FILTERS.filter((f) => f.stockStatus !== 0)

// 浏览与搜索打的是**同一个**端点 /api/items/search（商品一覧页的唯一数据源，
// ItemsView→searchItems）——browse 只带筛选、search 再叠 kw，与真实流量同形。
// 另有一个 /api/items（createdFrom/createdTo **必填**）是打印页专用（listForPrint）：
// 原稿拿它当列表端点、还配了它不认的仓库/状态参 → 四场景之首全程 400（本地预演实测）
const LIST_PATH = '/api/items/search'

// 登录是 Spring Security formLogin（form-urlencoded）——发 JSON body 必 401：
// 服务端取 request.getParameter("username")，JSON 字段它读不到（本地预演实测，
// 且该路径下失败计数不累加，表现为「密码没错却一直 401」）。
//
// ⚠ 会话 Cookie 必须**手工回填到每个请求头**（D-082）：k6 的 cookie jar 只在
//   设置它的那一轮迭代里把它发出去，下一轮迭代就不再发送（实测：iter0 带
//   JSESSIONID → 200，iter1 起 sent=(none) → 服务端认不出会话，每个请求 401）。
//   SameSite 不是原因——手工 jar.set() 去掉该属性后行为不变。后果极隐蔽：
//   登录本身 200（login 门槛全绿），此后**全部请求退化成未认证流量**，压测测到
//   的其实是 401 的响应时间。故此处登录后取会话 ID，之后每个请求显式带 Cookie。
function login(user) {
  const res = http.post(`${BASE}/api/auth/login`,
    `username=${encodeURIComponent(user)}&password=${encodeURIComponent(K6_PASS)}`,
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
  check(res, { 'login 200': (r) => r.status === 200 })
  if (res.status !== 200) {
    return null
  }
  const matched = /JSESSIONID=([^;]+)/.exec(res.headers['Set-Cookie'] || '')
  return matched ? matched[1] : null
}

function pick(arr, i) {
  return arr[i % arr.length]
}

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 1, stages: [
        { duration: '30s', target: 5 },
        { duration: '2m', target: 5 },
      ],
      exec: 'browse',
      tags: { name: 'browse' },
    },
    search: {
      executor: 'ramping-vus',
      startVUs: 1, stages: [
        { duration: '30s', target: 5 },
        { duration: '2m', target: 5 },
      ],
      exec: 'search',
      startTime: '30s',
      tags: { name: 'search' },
    },
    dashboard: {
      executor: 'constant-vus',
      vus: 2, duration: '2m',
      exec: 'dashboard',
      startTime: '1m',
      tags: { name: 'dashboard' },
    },
    entry: {
      executor: 'constant-vus',
      vus: 10, duration: '2m',
      exec: 'entry',
      startTime: '3m',
      tags: { name: 'entry' },
    },
  },
  thresholds: {
    // 按场景分桶断言（tags.name）：列表 500ms / 搜索 1s / 大盘 1s / 保存 300ms
    'http_req_duration{name:browse}': ['p(95)<500'],
    'http_req_duration{name:search}': ['p(95)<1000'],
    'http_req_duration{name:dashboard}': ['p(95)<1000'],
    // 录入 p95 只看保存（preview 是只读快路径，不设独立门槛）
    'http_req_duration{name:entry,expected_response:true}': ['p(95)<300'],
    // 10 并发录入吞吐 ≥ 5 件/s（迭代含 preview+保存两请求）
    'iterations{name:entry}': ['rate>=5'],
    'checks': ['rate>0.99'],
    // 零踢人：账号池按 22 VU / 8 会话上限配好就应为 0；非 0 即账号池不够
    // （或单账号被多 VU 复用），必须显式失败而不是让全网流量静默变 401
    'kcgl_session_kicked': ['count==0'],
  },
}

const vus = {}

export function setup() {
  // setup 阶段逐个验证账号池可登录——凭据/角色错误立即失败，不跑 5 分钟空转
  for (const user of ACCOUNTS) {
    const res = http.post(`${BASE}/api/auth/login`,
      `username=${encodeURIComponent(user)}&password=${encodeURIComponent(K6_PASS)}`,
      { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
    if (res.status !== 200) {
      throw new Error(`账号 ${user} 登录失败（${res.status}）：检查 K6_USERS/K6_PASS`)
    }
  }
  return {}
}

// 每场景每 VU 首次迭代登录一次，会话 ID 存进 VU 状态，之后每个请求显式回填
function ensureLogin(state, vu) {
  if (!state.sid) {
    state.sid = login(accountForVU(vu))
  }
  return !!state.sid
}

/** 每请求都显式带会话 Cookie（原因见 login() 上方注释） */
function get(url, st, name) {
  return http.get(url, { tags: { name: name }, headers: { Cookie: `JSESSIONID=${st.sid}` } })
}

/** 401 = 会话被踢（并发超上限）或已过期：计数 + 清会话，由下轮 ensureLogin 重登 */
function noteAuth(res, state) {
  if (res.status === 401) {
    kicked.add(1)
    state.sid = null
  }
  return res
}

export function browse(data) {
  const st = (vus[__VU] = vus[__VU] || {})
  if (!ensureLogin(st, __VU)) return
  const i = __ITER
  const f = pick(FILTERS, i)
  const qs = Object.entries(f).map(([k, v]) => `${k}=${v}`).join('&')
  const page = 1 + (i % 20) // 一覧页 1 起（page=0 服务端容忍但非真实流量）
  const res = noteAuth(get(`${BASE}${LIST_PATH}?${qs}&page=${page}&size=50`, st, 'browse'), st)
  check(res, { 'list 200': (r) => r.status === 200 })
  sleep(1)
}

export function search(data) {
  const st = (vus[__VU] = vus[__VU] || {})
  if (!ensureLogin(st, __VU)) return
  const i = __ITER
  const kw = encodeURIComponent(pick(KEYWORDS, i))
  const f = pick(SEARCH_FILTERS, i + 3)
  const qs = Object.entries(f).map(([k, v]) => `${k}=${v}`).join('&')
  const res = noteAuth(
    get(`${BASE}${LIST_PATH}?kw=${kw}${qs ? '&' + qs : ''}&page=1&size=50`, st, 'search'), st)
  // 响应包封 {code,message,data}——total 在 data 下（原稿取顶层 total=undefined，
  // 「有命中」断言恒假、checks 门槛必红）
  check(res, {
    'search 200': (r) => r.status === 200,
    'search has hits': (r) => (r.json('data.total') ?? 0) > 0,
  })
  sleep(1)
}

export function dashboard(data) {
  const st = (vus[__VU] = vus[__VU] || {})
  if (!ensureLogin(st, __VU)) return
  const res = noteAuth(get(`${BASE}/api/stats/dashboard`, st, 'dashboard'), st)
  check(res, { 'dashboard 200': (r) => r.status === 200 })
  sleep(2)
}

export function entry(data) {
  const st = (vus[__VU] = vus[__VU] || {})
  if (!ensureLogin(st, __VU)) return
  const today = new Date().toISOString().slice(0, 10)
  const price = 1000 + (Math.random() * 90000 | 0)
  const pv = noteAuth(get(
    `${BASE}/api/item-codes/preview?venueId=${VENUE_ID}&buyDate=${today}&price=${price}`, st, 'entry'), st)
  if (!check(pv, { 'preview 200': (r) => r.status === 200 })) return
  // 幂等键由**客户端**生成（web/src/utils/id.ts newClientId，docs/01 7.0）——预览
  // 响应里没有这个字段（原稿从预览取 → 恒 undefined，POST 变成无幂等键的裸录入，
  // 幂等/重放路径在压测里从未被走到）。k6 侧同构生成一个 ≤36 字符的唯一键。
  const reqId = `${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  const res = noteAuth(http.post(`${BASE}/api/items`,
    JSON.stringify({
      clientReqId: reqId,
      venueId: Number(VENUE_ID),
      buyDate: today,
      purchasePrice: price,
      warehouse: 1 + (Math.random() * 2 | 0),
    }),
    { headers: { 'Content-Type': 'application/json', Cookie: `JSESSIONID=${st.sid}` }, tags: { name: 'entry' } }), st)
  if (check(res, { 'save 200': (r) => r.status === 200 })) {
    entered.add(1)
  }
  sleep(0.5)
}
