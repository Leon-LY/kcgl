/**
 * 手册内容的读取与过滤（M7）。
 *
 * 章节顺序由下面 import 的先后决定，文件名前缀 NN- 只是排序提示（Vite 不保证
 * 目录遍历顺序，必须显式列出）。新增一章 = 加一个 sections/NN-*.json + 在这里
 * 加一行 import + 一行数组元素。
 *
 * 过滤口径（用户 2026-10-09 定）：**角色 + 设备**。
 * 「角色」= 当前登录者（1管理者/2编辑者/3閲覧者），「设备」= 当前壳
 * （手机含平板 / 电脑）。三级节点（章/功能/步骤）都可带 roles/devices，
 * 缺省表示全员/双端，就近取最细的那一级——所以同一功能能对管理者显示完整
 * 步骤、对查看者只留只读步骤。
 */
import type { Device, Lang, ManualSection, Phrase, PhraseList, Role } from './types'
import start from './sections/01-start.json'
import mobile from './sections/02-mobile.json'
import desktop from './sections/03-desktop.json'
import yahoo from './sections/04-yahoo.json'
import excel from './sections/05-excel.json'
import admin from './sections/06-admin.json'
import monitor from './sections/07-monitor.json'
import concepts from './sections/08-concepts.json'

export * from './types'

/**
 * 全部章节（未过滤）。JSON 里 roles 写成 number[]、devices 写成 string[]，
 * 过不了字面量收窄，故用 unknown 中转断言——形状正确性由 manual.spec.ts
 * 在运行时逐条校验（包括值域），比这行断言更可信。
 */
export const manualSections = [
  start,
  mobile,
  desktop,
  yahoo,
  excel,
  admin,
  monitor,
  concepts,
] as unknown as ManualSection[]

/** 节点可见 ⇔ 角色在允许之列（缺省=全员） */
function roleAllowed(roles: Role[] | undefined, role: number): boolean {
  return roles === undefined || roles.includes(role as Role)
}

/** 节点可见 ⇔ 当前壳在允许之列（缺省=双端） */
function deviceAllowed(devices: Device[] | undefined, device: Device): boolean {
  return devices === undefined || devices.includes(device)
}

/**
 * 按当前角色与当前壳过滤出可显示的手册。
 *
 * 步骤为空的功能整条丢弃：查看者在「Excel 导入」下只剩「看历史」这么一步，
 * 若某功能对他一步都不剩，留个空标题只会让人以为加载失败。
 * 章同理由此收敛——手机上不会出现只有桌面内容的空章。
 */
export function getVisibleSections(role: number, device: Device): ManualSection[] {
  return manualSections
    .filter(
      (section) =>
        roleAllowed(section.roles, role) && deviceAllowed(section.devices, device),
    )
    .map((section) => ({
      ...section,
      features: section.features
        .filter(
          (feature) =>
            roleAllowed(feature.roles, role) && deviceAllowed(feature.devices, device),
        )
        .map((feature) => ({
          ...feature,
          steps: feature.steps.filter(
            (step) =>
              roleAllowed(step.roles, role) && deviceAllowed(step.devices, device),
          ),
        }))
        .filter((feature) => feature.steps.length > 0),
    }))
    .filter((section) => section.features.length > 0)
}

/**
 * 取一段三语文案。缺当前语言时退回日文（默认语言）——手册正文由人工维护，
 * spec 已断言三语齐全，这里只是渲染端的最后一道兜底，不抛错。
 */
export function pick(phrase: Phrase | undefined, lang: Lang): string {
  if (!phrase) return ''
  return phrase[lang] || phrase.ja
}

/** 取一段分条的三语文案（同上兜底；空数组也算缺，避免某语言漏写时静默少讲一步） */
export function pickList(list: PhraseList | undefined, lang: Lang): string[] {
  if (!list) return []
  const items = list[lang]
  return items && items.length > 0 ? items : list.ja
}

/** 界面语言标识（'ja-JP' 等）收敛到手册的三种语言码 */
export function toLang(locale: string): Lang {
  const head = locale.slice(0, 2).toLowerCase()
  return head === 'zh' || head === 'en' ? head : 'ja'
}
