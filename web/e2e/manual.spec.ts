import { expect, test, type Page } from '@playwright/test'

/**
 * 应用内操作手册（M7）的角色 + 设备过滤回归。
 *
 * 这三条断言锁的是同一件事：手册内容按「当前角色 + 当前壳」收窄，收不窄就等于
 * 把越权步骤白纸黑字印在查看者眼前。断言的锚点是各章/各功能的 DOM id（ASCII，
 * 与三语文案无关），换文案不会把测试改红。
 *
 * 为什么放在 e2e：过滤的输入是「登录者的角色」与「useShell 解析出的壳」——两者
 * 都要真浏览器里跑完整的登录/路由/UA 链才拿得到；单测只能喂死参数，验证不了
 * 这条链本身通不通（manual.spec.ts 已在单测层守住过滤函数）。
 *
 * 项目守卫写在**每条用例内**（不是 beforeEach）：桌面两条走 desktop-chromium，
 * 手机一条要走 mobile-chromium 的真实手机 UA/视口。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome, .dashboard-view')).toBeVisible()
}

test.describe('in-app manual filtering', () => {
  test('an administrator on desktop sees the administration and monitoring chapters', async ({
    page,
  }) => {
    test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
    await login(page, 'admin')

    await page.goto('/help')
    await expect(page.locator('.manual-view')).toBeVisible()

    await expect(page.locator('#manual-admin')).toHaveCount(1)
    await expect(page.locator('#manual-monitor')).toHaveCount(1)
    await expect(page.locator('#manual-desktop')).toHaveCount(1)
    // 桌面壳里不该出现手机章（设备过滤）
    await expect(page.locator('#manual-mobile')).toHaveCount(0)
  })

  test('a viewer on desktop never receives an administrator-only chapter', async ({ page }) => {
    test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
    await login(page, 'viewer')

    await page.goto('/help')
    await expect(page.locator('.manual-view')).toBeVisible()

    // 账号管理／价格档位／系统设置／台账／操作日志／系统状况 对查看者全部不可见
    await expect(page.locator('#manual-admin')).toHaveCount(0)
    await expect(page.locator('#manual-monitor')).toHaveCount(0)
    await expect(page.locator('[data-feature-id="users"]')).toHaveCount(0)
    await expect(page.locator('[data-feature-id="ledgers"]')).toHaveCount(0)
    // 运营类章节对他仍在——「只看自己有的」不是「什么都看不到」
    await expect(page.locator('#manual-desktop')).toHaveCount(1)
    await expect(page.locator('#manual-excel')).toHaveCount(1)

    // 步骤级过滤：Excel 导入对编辑者有 5 步（下载模板/编号留空/选择文件/编号备注/
    // 修错重导），查看者只剩「编号备注」与「查看者请留意」2 步——写入步骤不出现
    const steps = page.locator('[data-feature-id="excel-import"] .manual-step')
    await expect(steps).toHaveCount(2)
    await expect(page.locator('[data-feature-id="excel-import"]')).not.toContainText(
      'ファイルを選択',
    )
  })

  test('every figure on the administrator desktop manual is a real, loaded screenshot', async ({
    page,
  }) => {
    test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
    await login(page, 'admin')

    await page.goto('/help')
    await expect(page.locator('.manual-view')).toBeVisible()

    // 26 = 管理员桌面可见的配图功能数：start 4（首回パスワード/言語/切替/ログイン，
    // 手机专属的「添加到主屏幕」无图且在手机章）+ desktop 4 + yahoo 3 + excel 2
    // + admin 4 + monitor 3 + concepts 6。
    const figures = page.locator('.manual-shot img')
    await expect(figures).toHaveCount(26)

    // 逐张滚进视口再验字节。img 带 loading="lazy"，没进视口就不会真的去取图，
    // naturalWidth 停在 0——只验第一张的话，后面 25 张是死是活根本不知道。
    // 这里同时守住 glob 路径约定：键写错则 img 压根不渲染（计数就不对），
    // 或渲染成碎图（naturalWidth 恒 0）。
    const broken: number[] = []
    for (let i = 0; i < 26; i += 1) {
      const img = figures.nth(i)
      await img.scrollIntoViewIfNeeded()
      const ok = await expect
        .poll(async () => img.evaluate((el: HTMLImageElement) => el.naturalWidth), {
          timeout: 5000,
        })
        .toBeGreaterThan(0)
        .then(() => true)
        .catch(() => false)
      if (!ok) broken.push(i)
    }
    expect(broken, `第 ${broken.join('、')} 张图没取到字节`).toEqual([])
  })

  test('a phone shell sees mobile chapters only, and hides desktop-only ones', async ({
    page,
  }) => {
    test.skip(test.info().project.name !== 'mobile-chromium', '仅 mobile-chromium 项目执行')
    await login(page, 'editor')

    await page.goto('/help')
    await expect(page.locator('.manual-view')).toBeVisible()

    await expect(page.locator('#manual-mobile')).toHaveCount(1)
    await expect(page.locator('#manual-start')).toHaveCount(1)
    // 电脑壳专属章（大盘/商品列表/雅虎联动/Excel 联动/管理/監視）在手机上不出现
    await expect(page.locator('#manual-desktop')).toHaveCount(0)
    await expect(page.locator('#manual-yahoo')).toHaveCount(0)
    await expect(page.locator('#manual-monitor')).toHaveCount(0)

    // 编辑者：手机章里「商品登録」在（roles [1,2]），且进入章节的顶栏入口在
    await expect(page.locator('[data-feature-id="entry"]')).toHaveCount(1)
    await expect(page.locator('.shell-help')).toBeVisible()
  })
})
