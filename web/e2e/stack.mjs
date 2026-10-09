#!/usr/bin/env node
/**
 * 本地迭代用的常驻 E2E 栈（up / reset / down）。
 *
 * 为什么要有：`npx playwright test` 每次都重建那套一次性栈——`docker run mysql:8.4`
 * 初始化 20–40s、Spring Boot 起 + Flyway ~10s、Vite ~7s。哪怕只跑一条用例也要付这
 * 30s 左右的固定成本（实测单条用例全跑 33s，其中用例本身只占 2–3s）。常驻栈把这笔
 * 成本摊到一次：
 *
 *   npm run e2e:stack                      # 起一次（后台跑 e2e/start-backend.mjs）
 *   npx playwright test e2e/entry.spec.ts  # 反复跑，栈被复用（见 playwright.config）
 *   npm run e2e:reset                      # 库脏了就重置（下一段讲）
 *   npm run e2e:down                       # 收摊（杀进程树 + 删容器）
 *
 * **复用会让库变脏**：用例会改种子数据（taro 改密、建商品/账号、改阈值），而
 * `E2eDataSeeder.upsert` 是"已存在就跳过"——所以重启 jar 并不能把库变回干净。
 * `reset` 的做法是**把库 drop 掉重建**（容器不动，省掉那 20–40s 的容器初始化），
 * jar 重启时 Flyway 迁移 + 种子重跑，得到与全新建容器等同的干净库，约 10s。
 *
 * 只复用**本脚本起的**那套：靠标记文件判定，免得撞上你开发用的 5173/18080。
 */
import { spawn, spawnSync } from 'node:child_process'
import { closeSync, openSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import {
  APP_PORT,
  CONTAINER,
  DB_NAME,
  DB_PASSWORD,
  DB_USER,
  STACK_LOG,
  clearStackMarker,
  readStackMarker,
  sleep,
  writeStackMarker,
} from './stack-shared.mjs'

const E2E_DIR = path.dirname(fileURLToPath(import.meta.url))
const HEALTH_URL = `http://127.0.0.1:${APP_PORT}/actuator/health`
/** MySQL 首次初始化的等待上限：30 次 × 2s = 60s，够慢机器用。 */
const BOOT_ATTEMPTS = 150

function fail(message) {
  console.error(`[e2e-stack] ${message}`)
  process.exit(1)
}

async function healthOk() {
  try {
    const res = await fetch(HEALTH_URL)
    return res.ok
  } catch {
    return false
  }
}

/** 杀进程树：Windows 用 taskkill /T（java 是 node 的子进程），POSIX 打进程组。 */
function killTree(pid) {
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
    return
  }
  try {
    process.kill(-pid, 'SIGTERM')
  } catch {
    process.kill(pid, 'SIGTERM')
  }
}

/** 后台起 jar（复用 start-backend.mjs 那个脚本，不另写一套），并等健康检查通过。 */
async function startBackend() {
  // 日志重定向到文件：后台跑没法看 stdout，起不来时让用户有地方查
  const log = openSync(STACK_LOG, 'a')
  const child = spawn('node', [path.join(E2E_DIR, 'start-backend.mjs')], {
    detached: true,
    stdio: ['ignore', log, log],
  })
  closeSync(log)
  child.unref()
  writeStackMarker({ pid: child.pid, startedAt: new Date().toISOString(), appPort: APP_PORT })

  process.stdout.write('[e2e-stack] 起栈中（MySQL 首次初始化 20–40s）')
  for (let attempt = 0; attempt < BOOT_ATTEMPTS; attempt++) {
    await sleep(2000)
    if (await healthOk()) {
      console.log(`\n[e2e-stack] 就绪（:${APP_PORT}，pid ${child.pid}）`)
      return
    }
    process.stdout.write('.')
  }
  fail(`起栈超时（${(BOOT_ATTEMPTS * 2) / 60} 分钟）——看日志 ${STACK_LOG}`)
}

/** 停掉 jar 并等端口放开（否则新实例绑不上端口）。 */
async function stopBackend() {
  const marker = readStackMarker()
  if (!marker?.pid) {
    return
  }
  killTree(marker.pid)
  for (let attempt = 0; attempt < 30; attempt++) {
    if (!(await healthOk())) {
      return
    }
    await sleep(500)
  }
  fail('旧进程 15s 内没退干净——手工确认后重来')
}

async function up() {
  if ((await healthOk()) && readStackMarker()?.pid) {
    console.log(`[e2e-stack] 栈已在跑（pid ${readStackMarker().pid}），直接复用`)
    return
  }
  await startBackend()
  console.log(`[e2e-stack] 迭代：npx playwright test e2e/<file>；重置：npm run e2e:reset；收摊：npm run e2e:down`)
}

/** 重置：容器留着，把库 drop 掉重建——jar 重启时 Flyway + 种子重跑，库回到干净态。 */
async function reset() {
  if (!readStackMarker()?.pid) {
    fail('没有常驻栈可重置——先 npm run e2e:stack')
  }
  await stopBackend()
  const drop = spawnSync(
    'docker',
    [
      'exec', CONTAINER,
      'mysql', `-u${DB_USER}`, `-p${DB_PASSWORD}`,
      '-e', `DROP DATABASE IF EXISTS ${DB_NAME}; CREATE DATABASE ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`,
    ],
    { encoding: 'utf8' },
  )
  if (drop.status !== 0) {
    fail(`清库失败：${drop.stderr}`)
  }
  console.log(`[e2e-stack] 库 ${DB_NAME} 已重建，重启 jar 重跑迁移与种子`)
  await startBackend()
}

function down() {
  const marker = readStackMarker()
  if (marker?.pid) {
    killTree(marker.pid)
    console.log(`[e2e-stack] 已杀进程树 pid ${marker.pid}`)
  }
  spawnSync('docker', ['rm', '-f', CONTAINER], { stdio: 'ignore' })
  clearStackMarker()
  console.log(`[e2e-stack] 容器 ${CONTAINER} 已删、标记已清，收摊完成`)
}

const command = process.argv[2]
if (command === 'up') {
  await up()
} else if (command === 'reset') {
  await reset()
} else if (command === 'down') {
  down()
} else {
  fail('用法：node e2e/stack.mjs <up|reset|down>')
}
