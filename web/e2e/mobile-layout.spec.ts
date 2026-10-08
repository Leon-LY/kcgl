import { expect, test, type Page } from '@playwright/test'

/**
 * 移动壳版面回归（docs/07 §1 三档表、§6 触控）：每条断言各锁一类缺陷。
 *
 * 1) 到货核对页的批量操作条写成 `position: fixed; bottom: 0; z-index: 20`，
 *    而壳的 van-tabbar 也在 bottom: 0 且 z-index 只有 1——操作条整条盖在导航上，
 *    编辑者选中任意一行后从这页**走不掉**（五页导航全在操作条底下点不到）。
 * 2) 触控目标：移动端可点元素 <44px 在手机上落在拇指热区边缘，误触率高。
 * 3) 平板档：UA 带 iPad → detectShell 判 mobile，平板走移动壳但宽度到 768+。
 *    原先零 @media、9 个页面各写一份 560px → 平板下"居中一条窄柱 + 大片空白"。
 * 4) 电脑版专属页（meta.shell='desktop'）在手机壳里不再原样渲染：守卫拦回首页
 *    并带上来路，首页给一句白话说明 + 一键切到电脑版再打开。
 *
 * 为什么放在 e2e：全是真实几何（盒子位置/尺寸）、命中测试与整条导航链（守卫 →
 * 地址栏 → 首页 → 切壳），jsdom 不做布局也不跑路由守卫，单测复现不了。
 * 各用例只读：登录、量盒子、点导航、切壳偏好（localStorage），不写业务数据，
 * 故不加清理。项目守卫写在**每条用例内**（不是 beforeEach）——平板档要跑第 3 条。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

/** 移动端触控下限 44px；容差 1px 吸收亚像素取整（实测 43.99x 会出现）。 */
const MIN_TAP_TARGET = 44

async function loginAsEditor(page: Page): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', 'editor')
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome, .dashboard-view')).toBeVisible()
}

/**
 * 等目标页面真正挂载、且换页动效走完，再量盒子。
 *
 * 两道门缺一不可：
 *  ① 页面根必须已在（ready 选择器可见）。`page.goto` 是全页加载，RouterView 的
 *    异步块尚未解析时 `.kcgl-view-slot` 是个**空壳**——此时量什么都是 0。实测踩过：
 *     只等 transform 的版本在空壳上直接放行，44px 扫描扫到 **0 个元素**，
 *     把一个 30px 的按钮放进去照样"通过"。
 *  ② transform 必须是 none。kcgl-view 的入场帧给 .kcgl-view-slot 挂了 transform
 *     （brand.css），带 transform 的祖先会成为 fixed 元素的包含块——动效进行中
 *     fixed 元素的 bottom 是相对挂载点算的（比定型后高约 68px），动效结束才回视口。
 */
async function waitForViewSettled(page: Page, ready: string): Promise<void> {
  await expect(page.locator(ready)).toBeVisible()
  await expect
    .poll(() =>
      page.evaluate(() => getComputedStyle(document.querySelector('.kcgl-view-slot')!).transform),
    )
    .toBe('none')
}

test.describe('mobile layout', () => {
  test('the arrival action bar sits above the bottom nav instead of covering it', async ({
    page,
  }) => {
    test.skip(test.info().project.name !== 'mobile-chromium', '仅 mobile-chromium 项目执行')
    await loginAsEditor(page)

    await page.goto('/arrival')
    const actionbar = page.locator('.arrival-actionbar')
    const tabbar = page.locator('.van-tabbar')
    await expect(actionbar).toBeVisible()
    await expect(tabbar).toBeVisible()
    await waitForViewSettled(page, '.arrival-filter')

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

  test('every tappable control in the content area meets the 44px floor', async ({ page }) => {
    test.skip(test.info().project.name !== 'mobile-chromium', '仅 mobile-chromium 项目执行')
    await loginAsEditor(page)

    // 只扫有常驻可点控件的页：
    //   /scan   无摄像头的无头环境拿不到画面，整页起不来
    //   /today  空库下只有空态文字、无任何 button（唯一控件 .today-retry 只在
    //           加载失败时出现，且它是 .kcgl-btn，基元已锁 44px）——扫它恒为空，
    //           收进来只会让这条断言重新变成空转。不扫是如实，不是漏。
    // 第二个元素是每页的"内容已挂载"信号，喂给 waitForViewSettled 的第一道门——
    // 少了它，全页加载的空壳会被当成"已就绪"，扫描区间为空、断言恒真。
    const PAGES: Array<[path: string, ready: string]> = [
      ['/', '.home-welcome'],
      ['/arrival', '.arrival-filter'],
      ['/entry', '.entry-form'],
    ]

    for (const [path, ready] of PAGES) {
      await page.goto(path)
      await waitForViewSettled(page, ready)

      // 量"可点元素"的命中盒：button/[role=button]、以及带 href 的锚点。
      // van-field 这类是 div（整行可点，真实高度本就 >44），不在扫描范围。
      // 隐藏元素（display:none / 0×0）跳过——空态、条件渲染都靠这个筛掉。
      const { offenders, scanned } = await page.evaluate((min) => {
        const nodes = document.querySelectorAll<HTMLElement>(
          '.shell-main button, .shell-main [role="button"], .shell-main a[href]',
        )
        const bad: Array<{ text: string; height: number; cls: string }> = []
        let scanned = 0
        for (const el of nodes) {
          const rect = el.getBoundingClientRect()
          if (rect.height === 0 || rect.width === 0) {
            continue // 未渲染/隐藏
          }
          if (getComputedStyle(el).pointerEvents === 'none') {
            continue // 只作展示，不可点
          }
          if (el.hasAttribute('disabled')) {
            continue
          }
          scanned += 1
          bad.push({
            text: (el.textContent ?? '').trim().slice(0, 24),
            height: Math.round(rect.height * 100) / 100,
            cls: el.className,
          })
        }
        return { offenders: bad.filter((item) => item.height < min), scanned }
      }, MIN_TAP_TARGET)

      // 防空转：这一页必须真扫到东西。范围选择器改错、或页面没挂载就进来，
      // 上面的 offenders 恒为空数组、断言恒真——这条先把它顶掉。
      expect(scanned, `${path} 一个可点元素都没扫到，扫描范围选择器写错了`).toBeGreaterThan(0)

      expect(
        offenders,
        `${path} 上有 ${offenders.length} 个可点元素低于 ${MIN_TAP_TARGET}px：` +
          offenders.map((o) => `「${o.text}」${o.height}px (.${o.cls})`).join('、'),
      ).toEqual([])
    }
  })

  test('a desktop-only page bounces back home with a way out, not a dead end', async ({ page }) => {
    test.skip(test.info().project.name !== 'mobile-chromium', '仅 mobile-chromium 项目执行')
    await loginAsEditor(page)

    // 直敲电脑版专属页（meta.shell='desktop'）：手机壳不渲染它，拦回首页并带上来路。
    // 拦之前这里是"把 24 吋屏版面塞进手机渲染"——手机上的 PC 缩小版。
    await page.goto('/print')
    await expect(page).toHaveURL(/desktopOnly=/)

    const notice = page.locator('.home-notice')
    await expect(notice).toBeVisible()
    await expect(notice).toContainText('ラベル印刷') // 说清用户点的是哪个页面
    await expect(notice).toContainText('パソコン表示')

    // 一键切到电脑版并直接打开目标页（壳切换可逆：桌面壳侧栏有切回入口）
    await page.locator('.home-notice-action').click()
    await expect(page).toHaveURL(/\/print$/)
    await expect(page.locator('.shell-desktop')).toBeVisible()
    await expect(notice).toBeHidden()
  })

  test('the tablet tier widens the content column instead of centring a narrow one', async ({
    page,
  }) => {
    test.skip(test.info().project.name !== 'tablet-chromium', '仅 tablet-chromium 项目执行')
    await loginAsEditor(page)

    const PAGES: Array<[path: string, ready: string]> = [
      ['/', '.home-welcome'],
      ['/today', '.today-view'],
    ]

    for (const [path, ready] of PAGES) {
      await page.goto(path)
      await waitForViewSettled(page, ready)

      // 平板档（≥768）内容列放宽到 880：不再是 560 的窄柱
      const slotWidth = await page.evaluate(
        () => document.querySelector('.kcgl-view-slot')!.getBoundingClientRect().width,
      )
      expect(slotWidth, `${path} 的内容列宽度仍是手机档（平板档未生效）`).toBeGreaterThan(560)

      // 版面不得横向溢出（固定宽度/不换行的行会撑破视口）
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      )
      expect(overflow, `${path} 出现横向滚动 ${overflow}px`).toBeLessThanOrEqual(1)
    }
  })
})
