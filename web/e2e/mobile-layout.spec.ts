import { expect, test, type Page } from '@playwright/test'

/**
 * 移动壳版面回归：页面自带的固定底条不得压住壳的底部导航。
 *
 * 背景：到货核对页的批量操作条写成 `position: fixed; bottom: 0; z-index: 20`，
 * 而壳的 van-tabbar 也在 bottom: 0 且 z-index 只有 1——操作条整条盖在导航上，
 * 编辑者选中任意一行后从这页**走不掉**（五页导航全在操作条底下点不到）。
 * 修法是操作条按 `--kcgl-tabbar-height` 上抬到导航之上；壳级变量在 MobileShell。
 *
 * 为什么放在 e2e：这是纯几何（两层 fixed 元素的盒子位置）＋真实点击命中测试，
 * jsdom 不做布局也不做命中判定，单测复现不了。
 * 本用例只读：编辑器会话打开页面、量盒子、点一次导航，不写业务数据，故不加清理。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

test.beforeEach(() => {
  test.skip(test.info().project.name !== 'mobile-chromium', '仅 mobile-chromium 项目执行')
})

async function loginAsEditor(page: Page): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', 'editor')
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome, .dashboard-view')).toBeVisible()
}

test.describe('mobile layout (mobile-chromium)', () => {
  test('the arrival action bar sits above the bottom nav instead of covering it', async ({ page }) => {
    await loginAsEditor(page)

    await page.goto('/arrival')
    const actionbar = page.locator('.arrival-actionbar')
    const tabbar = page.locator('.van-tabbar')
    await expect(actionbar).toBeVisible()
    await expect(tabbar).toBeVisible()

    // 先等换页动效走完再量盒子。kcgl-view 的入场帧给 .kcgl-view-slot 挂了
    // transform（brand.css），带 transform 的祖先会成为 fixed 元素的包含块——
    // 动效进行中操作条的 bottom:0 是相对挂载点算的（比定型后高约 68px），
    // 动效一结束才回到视口。不设这道门，量到的是"动效中"的瞬时布局，
    // 缺陷在场也照样通过（实测踩过：bottom:0 的旧写法连跑两次全绿）。
    await expect
      .poll(() =>
        page.evaluate(
          () => getComputedStyle(document.querySelector('.kcgl-view-slot')!).transform,
        ),
      )
      .toBe('none')

    // 几何：操作条底边不越过导航顶边（留 1px 容差吸收亚像素取整）
    const bar = (await actionbar.boundingBox())!
    const nav = (await tabbar.boundingBox())!
    expect(bar.y + bar.height).toBeLessThanOrEqual(nav.y + 1)

    // 命中：导航项此刻仍可点，点了真的换页（旧实现这里会被操作条拦下）。
    // van-tabbar-item 是 div + 点击换路由（不是锚点），故按末位取「本日」——
    // 位置由 MobileShell 的模板顺序固定，比按文案取更抗 i18n 与图标改动。
    await tabbar.locator('.van-tabbar-item').last().click()
    await expect(page).toHaveURL(/\/today$/)
  })
})
