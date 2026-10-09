/**
 * E2E 栈的共用常量与常驻栈标记。四处共用（故单列一个文件，避免复制粘贴漂移）：
 * e2e/start-backend.mjs（起一次性栈）、e2e/stack.mjs（常驻栈 up/重置/down）、
 * playwright.config.ts 与 e2e/global-teardown.ts（判断能否复用、要不要留容器）。
 */
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

export const CONTAINER = 'kcgl-e2e-mysql'
export const DB_NAME = 'kcgl'
export const DB_USER = 'kcgl'
// 口令须过 SecretStrengthGuard（验收 12 弱值拒启）：E2E 起的是打包 jar、e2e profile
// 不关守卫——弱口令会让整条 E2E 栈起不来，故这里给一个够长的夹具口令（与一次性容器
// 同生共死，无复用面）。
export const DB_PASSWORD = 'kcgl-e2e-fixture-Kq7x41bP'
export const APP_PORT = process.env.E2E_APP_PORT ?? '18080'
export const WEB_PORT = process.env.E2E_WEB_PORT ?? '5173'

/**
 * 常驻栈标记：落**系统临时目录**而不是工作区——否则每台机器都要往 .gitignore 里加
 * 一条路径，而这只是本机迭代期的临时状态。
 */
export const STACK_MARKER = path.join(tmpdir(), 'kcgl-e2e-stack.json')
export const STACK_LOG = path.join(tmpdir(), 'kcgl-e2e-stack.log')

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 常驻栈在跑（标记存在）——只有这种栈才允许 Playwright 复用，避免撞上开发用的 5173/18080。 */
export function hasStackMarker() {
  return existsSync(STACK_MARKER)
}

/** 读标记；文件坏了或不存在都返回 null（调用方按"没有常驻栈"处理）。 */
export function readStackMarker() {
  if (!existsSync(STACK_MARKER)) {
    return null
  }
  try {
    return JSON.parse(readFileSync(STACK_MARKER, 'utf8'))
  } catch {
    return null
  }
}

export function writeStackMarker(value) {
  writeFileSync(STACK_MARKER, JSON.stringify(value, null, 2))
}

export function clearStackMarker() {
  rmSync(STACK_MARKER, { force: true })
}
