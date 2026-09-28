#!/usr/bin/env node
/**
 * E2E 栈启动器（Playwright webServer 命令，web/ 为工作目录）：
 *   1. 起一次性 MySQL 8.4 容器（随机宿主端口，容器名固定——与开发库 kcgl-mysql 完全隔离）
 *   2. 轮询容器内 mysqladmin ping 直至健康
 *   3. 前台启动 kcgl-server jar（e2e profile：三角色种子账号；Origin 白名单=Vite 端口）
 * 前置条件：server/target 下已有 jar（./mvnw -DskipTests package）。
 * Playwright 停止时杀本进程树；容器由 globalTeardown / 下次运行开头清理。
 */
import { spawn, spawnSync } from 'node:child_process'
import { readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const E2E_DIR = path.dirname(fileURLToPath(import.meta.url))
const TARGET_DIR = path.resolve(E2E_DIR, '../../server/target')

const APP_PORT = process.env.E2E_APP_PORT ?? '18080'
const WEB_PORT = process.env.E2E_WEB_PORT ?? '5173'
const CONTAINER = 'kcgl-e2e-mysql'
const DB_PASSWORD = 'kcgl_e2e_pass'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function fail(message) {
  console.error(`[e2e-stack] ${message}`)
  process.exit(1)
}

// ---- 0. Docker 可用性 ----
if (spawnSync('docker', ['--version'], { encoding: 'utf8' }).status !== 0) {
  fail('docker 不可用——E2E 需要本机 Docker（一次性 MySQL 容器）')
}

// ---- 1. 清理上次残留 + 起一次性容器（随机宿主端口防占用冲突） ----
spawnSync('docker', ['rm', '-f', CONTAINER], { stdio: 'ignore' })
const run = spawnSync(
  'docker',
  [
    'run', '-d', '--name', CONTAINER,
    '-p', '127.0.0.1::3306',
    '-e', 'MYSQL_DATABASE=kcgl',
    '-e', 'MYSQL_USER=kcgl',
    '-e', `MYSQL_PASSWORD=${DB_PASSWORD}`,
    '-e', 'MYSQL_ROOT_PASSWORD=kcgl_e2e_root',
    '-e', 'TZ=Asia/Tokyo',
    'mysql:8.4',
    '--character-set-server=utf8mb4',
    '--collation-server=utf8mb4_0900_ai_ci',
    '--default-time-zone=+09:00',
    '--performance-schema=OFF',
  ],
  { encoding: 'utf8' },
)
if (run.status !== 0) {
  fail(`MySQL 容器启动失败：${run.stderr}`)
}

const portOut = spawnSync('docker', ['port', CONTAINER, '3306'], { encoding: 'utf8' })
const hostPort = portOut.stdout.match(/127\.0\.0\.1:(\d+)/)?.[1]
if (!hostPort) {
  fail(`无法发现容器映射端口：${portOut.stdout} ${portOut.stderr}`)
}
console.log(`[e2e-stack] MySQL 容器就绪（127.0.0.1:${hostPort}）`)

// ---- 2. 等健康（首次初始化 20-40s） ----
for (let attempt = 0; attempt < 60; attempt++) {
  const ping = spawnSync(
    'docker',
    ['exec', CONTAINER, 'mysqladmin', 'ping', '-h', '127.0.0.1', '-ukcgl', `-p${DB_PASSWORD}`],
    { encoding: 'utf8' },
  )
  if (ping.status === 0) {
    break
  }
  if (attempt === 59) {
    fail('MySQL 健康等待超时（120s）')
  }
  await sleep(2000)
}

// ---- 3. 定位 jar ----
const jar = readdirSync(TARGET_DIR).find((file) => file.endsWith('.jar') && !file.endsWith('.original'))
if (!jar) {
  fail(`未找到后端 jar（${TARGET_DIR}）——先执行 cd server && ./mvnw -B -DskipTests package`)
}

// ---- 4. 前台启动应用（stdout 直接透传，便于排障） ----
console.log(`[e2e-stack] 启动 kcgl-server（:${APP_PORT}，e2e profile）`)
const child = spawn('java', ['-jar', path.join(TARGET_DIR, jar)], {
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'e2e',
    SERVER_PORT: APP_PORT,
    DB_HOST: '127.0.0.1',
    DB_PORT: hostPort,
    DB_NAME: 'kcgl',
    DB_USER: 'kcgl',
    DB_PASSWORD,
    // Vite 代理转发保留浏览器 Origin 头——后端 Origin 白名单需放行 dev server 端口
    KCGL_ALLOWED_ORIGINS: `http://127.0.0.1:${WEB_PORT},http://localhost:${WEB_PORT}`,
    // CSV 原始文件（sha 命名）落系统临时目录——不污染 web/ 工作区
    KCGL_YAHOO_IMPORTS_DIR: path.join(tmpdir(), 'kcgl-e2e-imports'),
  },
  stdio: 'inherit',
})
child.on('exit', (code) => process.exit(code ?? 1))
