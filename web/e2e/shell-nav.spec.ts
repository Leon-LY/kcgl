import { expect, test, type Page } from '@playwright/test'

/**
 * 壳层换页回归（D-104）：内容区在任何时候都不该"变空"。
 *
 * 背景：两壳的换页动效写成 <Transition mode="out-in"> 直接包 <component :is>，
 * 而 Excel／ラベル印刷 两页的根是 <el-config-provider>（渲染出 fragment 根），
 * Vue 无法为它做离场——out-in 便卡在"旧页已离场、新页还没入场"，内容区整片
 * 空白且只能刷新恢复。修法是包一层单元素挂载点 .kcgl-view-slot。
 *
 * 为什么放在 e2e：该缺陷依赖真实浏览器的 CSS 过渡完成事件，jsdom 下过渡不跑，
 * 单测复现不了（单测里遮罩/过渡都停在"类名已加、动画未走"的中间态）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

test.beforeEach(() => {
  test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
})

async function login(page: Page): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', 'admin')
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.dashboard-view')).toBeVisible()
}

/**
 * 内容区是否有"可见且有字"的子元素。判定要求三件同时成立：占位高度 > 0、
 * opacity ≠ 0、有文本——过渡中途（opacity:0）不算空，那是动效本身。
 */
async function paneHasContent(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const main = document.querySelector('.shell-main')
    if (!main) {
      return false
    }
    return Array.from(main.children).some((child) => {
      const style = getComputedStyle(child)
      const box = child.getBoundingClientRect()
      return (
        box.height > 0
        && style.opacity !== '0'
        && (child.textContent ?? '').trim().length > 0
      )
    })
  })
}

test.describe('shell navigation (desktop-chromium)', () => {
  test('leaving the Excel page still renders the next page (fragment root must not stall out-in)', async ({ page }) => {
    await login(page)

    // Excel 页的根是 el-config-provider（fragment 根）——旧实现里"从这里离开"就会卡住
    await page.goto('/excel')
    await expect(page.locator('.excel-view')).toBeVisible()

    await page.locator('.shell-nav-link').filter({ hasText: 'システム設定' }).click()

    await expect(page).toHaveURL(/\/admin\/settings$/)
    await expect(page.locator('.settings-view')).toBeVisible()
    expect(await paneHasContent(page)).toBe(true)
  })

  test('rapid menu clicks never leave the content pane empty', async ({ page }) => {
    await login(page)

    const labels = await page.locator('.shell-nav-link').allTextContents()
    expect(labels.length).toBeGreaterThan(5)

    // 连点：不等上一页的换页动效走完就切下一页（真实用户"连点几个菜单"的手速）
    for (let i = 0; i < 24; i += 1) {
      await page
        .locator('.shell-nav-link')
        .nth((i * 5 + 1) % labels.length)
        .click({ noWaitAfter: true })
      await page.waitForTimeout(80)
    }

    // 动效收尾后，内容区必须仍然有内容（旧实现会永久空白，刷新才恢复）
    await page.waitForTimeout(600)
    expect(await paneHasContent(page)).toBe(true)
  })
})
