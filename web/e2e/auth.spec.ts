import { expect, test, type Page } from '@playwright/test'

/**
 * M1 认证冒烟（docs/03 G1）：登录/锁定文案/语言切换/三角色/登出回跳/
 * 首登强制改密/双壳渲染。种子账号由 e2e profile 的 E2eDataSeeder 提供。
 * 通用用例在 desktop-chromium 执行一次；壳专属用例按项目过滤——
 * taro 会改密（跨项目重复执行必失败），钉在 desktop 单次执行。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

/** 壳/流程专属用例的项目过滤器。 */
function onlyOn(project: string): void {
  test.beforeEach(async () => {
    test.skip(test.info().project.name !== project, `仅 ${project} 项目执行`)
  })
}

async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', password)
  await page.getByRole('button', { name: 'ログイン' }).click()
}

test.describe('auth flows (desktop-chromium)', () => {
  onlyOn('desktop-chromium')

  test('login page renders in Japanese by default', async ({ page }) => {
    await page.goto('/login')
    await expect(page.locator('.login-title')).toHaveText('在庫管理システム')
    await expect(page.locator('.login-subtitle')).toHaveText('在庫管理システムへサインイン')
    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible()
  })

  test('wrong password shows the Japanese error message', async ({ page }) => {
    await login(page, 'viewer', 'wrong-password-1')
    await expect(page.locator('.kcgl-error-box')).toContainText(
      'ユーザー名またはパスワードが正しくありません',
    )
  })

  test('login page language switch (zh→en→ja) applies instantly to signed-out users, syncing title and lang attribute', async ({ page }) => {
    await page.goto('/login')
    await expect(page).toHaveTitle('ログイン｜在庫管理システム')
    await expect(page.locator('html')).toHaveAttribute('lang', 'ja-JP')

    await page.selectOption('.lang-switch', 'zh-CN')
    await expect(page.locator('.login-title')).toHaveText('库存管理系统')
    await expect(page).toHaveTitle('登录｜库存管理系统')
    await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN')

    await page.selectOption('.lang-switch', 'en-US')
    await expect(page.locator('.login-title')).toHaveText('Inventory Management')
    await expect(page).toHaveTitle('Sign in | Inventory Management')
    await expect(page.locator('html')).toHaveAttribute('lang', 'en-US')

    await page.selectOption('.lang-switch', 'ja-JP')
    await expect(page.locator('.login-title')).toHaveText('在庫管理システム')
    await expect(page).toHaveTitle('ログイン｜在庫管理システム')
    await expect(page.locator('html')).toHaveAttribute('lang', 'ja-JP')
  })

  test('viewer login shows the desktop shell with account info (viewer role label, home title = system name)', async ({ page }) => {
    await login(page, 'viewer', E2E_PASSWORD)
    await expect(page.locator('.shell-desktop')).toBeVisible()
    await expect(page.locator('.home-welcome')).toContainText('閲覧者 次郎')
    await expect(page.locator('.home-info')).toContainText('閲覧者')
    await expect(page).toHaveTitle('在庫管理システム')
  })

  test('role label reflects the logged-in account for admin and editor', async ({ page }) => {
    await login(page, 'admin', E2E_PASSWORD)
    await expect(page.locator('.home-info')).toContainText('管理者')
    await page.getByRole('button', { name: 'ログアウト' }).click()
    await expect(page).toHaveURL(/\/login/)

    await login(page, 'editor', E2E_PASSWORD)
    await expect(page.locator('.home-info')).toContainText('編集者')
  })

  test('visiting a protected page after logout redirects back to login, with a redirect param on non-root paths', async ({ page }) => {
    await login(page, 'viewer', E2E_PASSWORD)
    await expect(page.locator('.home-welcome')).toBeVisible()
    await page.getByRole('button', { name: 'ログアウト' }).click()
    await expect(page).toHaveURL(/\/login/)

    // 根路径守卫不带 redirect（回登录后直落首页，无多余参数）
    await page.goto('/')
    await expect(page).toHaveURL(/\/login$/)

    // 受保护路径带 redirect，登录后回跳
    await page.goto('/change-password')
    await expect(page).toHaveURL(/\/login\?redirect=/)
  })

  test('first login with mustChangePwd is held on the change-password page and enters home after updating it', async ({ page }) => {
    await login(page, 'taro', E2E_PASSWORD)
    await expect(page).toHaveURL(/\/change-password/)
    await expect(page.locator('.kcgl-info-box')).toContainText('初回ログイン')

    await page.fill('#pwd-current', E2E_PASSWORD)
    await page.fill('#pwd-new', 'taro-new-pass-456')
    await page.fill('#pwd-confirm', 'taro-new-pass-456')
    await page.getByRole('button', { name: '変更する' }).click()

    await expect(page).toHaveURL(/\/$/)
    await expect(page.locator('.home-welcome')).toContainText('田中太郎')
  })
})

test.describe('mobile shell (Pixel 5 viewport)', () => {
  onlyOn('mobile-chromium')

  test('mobile UA renders the mobile shell and completes the login flow', async ({ page }) => {
    await login(page, 'viewer', E2E_PASSWORD)
    await expect(page.locator('.shell-mobile')).toBeVisible()
    await expect(page.locator('.shell-desktop')).toHaveCount(0)
    await expect(page.locator('.home-welcome')).toContainText('閲覧者 次郎')
  })
})
