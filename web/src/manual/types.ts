/**
 * 操作手册的数据契约（M7 追加交付物）。
 *
 * 手册正文只写一份，两处共用：应用内手册页（按登录者角色 + 当前壳过滤后渲染）
 * 与甲方 PDF（scripts/build-manual-pdf.mjs 读同一份数据出中日英三份）。
 * 之所以是 JSON 而不是 TS：Vite 与 Node 都能原生读，不必转译、不添依赖；
 * 形状正确性由 manual.spec.ts 在运行时校验（比 TS 编译更严——能断言
 * 「每段文案三语齐全」「roles 只能是 1/2/3」这类跨字段约束）。
 *
 * 三语文案直接写在本文件里，不进 i18n 的 locales/*.json：手册正文是长文，
 * 塞进 key:value 会把 locales 的键树撑爆，也会让 locales.spec 的三语对拍
 * 报出一堆无意义的差异。
 */

/** 与 auth store 的 SUPPORTED_LOCALES 一致的三种界面语言（ja 是默认） */
export type Lang = 'ja' | 'zh' | 'en'

/** 两种壳：手机（含平板）与电脑。平板按 docs/07 §1 走移动壳 */
export type Device = 'mobile' | 'desktop'

/** 角色编号，与后端 UserRole 一致：1 管理员 / 2 编辑者 / 3 查看者 */
export type Role = 1 | 2 | 3

/** 一段三语文案 */
export type Phrase = Record<Lang, string>

/** 一段分条的三语文案（数组长度各语言一致，由 spec 校验） */
export type PhraseList = Record<Lang, string[]>

/**
 * 一条步骤。devices/roles 缺省表示「双端」/「全员」；
 * 步骤级也带 roles，故像「Excel 导入」这种页面能在查看者面前只留导出步骤。
 */
export interface ManualStep {
  /** 这一步要做什么（动词开头，一句话） */
  title: Phrase
  /** 补充说明：界面在哪、会看到什么（可略） */
  body?: Phrase
  /** 分条的详细信息，适合字段清单一类（可略） */
  bullets?: PhraseList
  /** 白话提醒：注意点、常见误区（可略） */
  note?: Phrase
  roles?: Role[]
  devices?: Device[]
}

/** 一个功能（一个页面或一组操作） */
export interface ManualFeature {
  /** 稳定标识，供测试与锚点用（英文小写连字符） */
  id: string
  title: Phrase
  /** 这一功能是干什么的（可略） */
  intro?: Phrase
  roles?: Role[]
  devices?: Device[]
  steps: ManualStep[]
}

/** 一章 */
export interface ManualSection {
  id: string
  title: Phrase
  intro?: Phrase
  roles?: Role[]
  devices?: Device[]
  features: ManualFeature[]
}
