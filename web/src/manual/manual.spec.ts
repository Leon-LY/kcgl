import { describe, expect, test } from 'vitest'
import { getVisibleSections, manualSections, pick, pickList, toLang } from './index'
import type { Device, ManualSection, Phrase, PhraseList, Role } from './types'

// 手册数据的运行时契约。选 JSON 做源的代价是编译期不再管形状，这一层补回来，
// 并且比 TS 更严——能断言跨字段的约束（三语齐全、数组长度对齐、roles ∈ {1,2,3}），
// 这些是 TS 类型写不出来的。改动 sections/*.json 后跑本文件即可守住下面每条。

const LANGS = ['ja', 'zh', 'en'] as const
const ROLES: Role[] = [1, 2, 3]
const DEVICES: Device[] = ['mobile', 'desktop']

/** 展平成「路径 → 三语文案」，让断言能报出是哪一条出的问题 */
function walk(
  section: ManualSection,
): { path: string; phrase?: Phrase; list?: PhraseList }[] {
  const out: { path: string; phrase?: Phrase; list?: PhraseList }[] = []
  out.push({ path: `${section.id}.title`, phrase: section.title })
  if (section.intro) out.push({ path: `${section.id}.intro`, phrase: section.intro })
  for (const feature of section.features) {
    const base = `${section.id}.${feature.id}`
    out.push({ path: `${base}.title`, phrase: feature.title })
    if (feature.intro) out.push({ path: `${base}.intro`, phrase: feature.intro })
    feature.steps.forEach((step, i) => {
      const stepPath = `${base}[${i}]`
      out.push({ path: `${stepPath}.title`, phrase: step.title })
      if (step.body) out.push({ path: `${stepPath}.body`, phrase: step.body })
      if (step.note) out.push({ path: `${stepPath}.note`, phrase: step.note })
      if (step.bullets) out.push({ path: `${stepPath}.bullets`, list: step.bullets })
    })
  }
  return out
}

const allNodes = manualSections.flatMap(walk)

describe('manual content shape', () => {
  test('section ids are unique and follow the declared file order', () => {
    const ids = manualSections.map((section) => section.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids).toEqual([
      'start',
      'mobile',
      'desktop',
      'yahoo',
      'excel',
      'admin',
      'monitor',
      'concepts',
    ])
  })

  test('feature ids are unique within each section and carry no steps-less features', () => {
    for (const section of manualSections) {
      const ids = section.features.map((feature) => feature.id)
      expect(new Set(ids).size, `${section.id} 功能 id 重复`).toBe(ids.length)
      for (const feature of section.features) {
        expect(feature.steps.length, `${section.id}.${feature.id} 没有步骤`).toBeGreaterThan(0)
      }
    }
  })

  test('role and device tags stay inside their allowed domains', () => {
    const check = (roles: Role[] | undefined, devices: Device[] | undefined, path: string) => {
      for (const role of roles ?? []) {
        expect(ROLES.includes(role), `${path} 角色取值非法：${role}`).toBe(true)
      }
      for (const devices_ of devices ?? []) {
        expect(DEVICES.includes(devices_), `${path} 设备取值非法：${devices_}`).toBe(true)
      }
    }
    for (const section of manualSections) {
      check(section.roles, section.devices, section.id)
      for (const feature of section.features) {
        check(feature.roles, feature.devices, `${section.id}.${feature.id}`)
        feature.steps.forEach((step, i) =>
          check(step.roles, step.devices, `${section.id}.${feature.id}[${i}]`),
        )
      }
    }
  })

  test('every phrase exists in all three languages and is non-empty', () => {
    for (const node of allNodes) {
      if (node.phrase) {
        for (const lang of LANGS) {
          const text = node.phrase[lang]
          expect(typeof text, `${node.path}.${lang} 缺失`).toBe('string')
          expect(text.trim().length, `${node.path}.${lang} 为空`).toBeGreaterThan(0)
        }
      }
      if (node.list) {
        for (const lang of LANGS) {
          const items = node.list[lang]
          expect(Array.isArray(items), `${node.path}.${lang} 不是数组`).toBe(true)
          expect(items.length, `${node.path}.${lang} 为空列表`).toBeGreaterThan(0)
          for (const item of items) {
            expect(item.trim().length, `${node.path}.${lang} 有空条目`).toBeGreaterThan(0)
          }
        }
        // 三语条数必须一致：分条列表错位会让某语言少讲一步，肉眼很难发现
        expect(node.list.zh.length, `${node.path} 中日条数不一致`).toBe(node.list.ja.length)
        expect(node.list.en.length, `${node.path} 英日条数不一致`).toBe(node.list.ja.length)
      }
    }
  })
})

describe('manual filtering by role and device', () => {
  /** 收集过滤结果里所有出现过的、带限定标签的节点路径 */
  function visibleSectionIds(role: number, device: Device): string[] {
    return getVisibleSections(role, device).map((section) => section.id)
  }

  test('a viewer on desktop never receives an administrator-only section', () => {
    const ids = visibleSectionIds(3, 'desktop')
    expect(ids).not.toContain('admin')
    expect(ids).not.toContain('monitor')
    // 运营类章节对他仍在（看得到才谈得上「只看自己有的」）
    expect(ids).toContain('desktop')
    expect(ids).toContain('yahoo')
  })

  test('an administrator on desktop receives the sections a viewer does not', () => {
    const ids = visibleSectionIds(1, 'desktop')
    expect(ids).toContain('admin')
    expect(ids).toContain('monitor')
  })

  test('a viewer never sees a step or feature reserved for writers', () => {
    const sections = getVisibleSections(3, 'desktop')
    for (const section of sections) {
      for (const feature of section.features) {
        expect(
          feature.roles === undefined || feature.roles.includes(3),
          `${section.id}.${feature.id} 对查看者可见却标了写权限`,
        ).toBe(true)
        for (const step of feature.steps) {
          expect(
            step.roles === undefined || step.roles.includes(3),
            `${section.id}.${feature.id} 的某步骤对查看者可见却标了写权限`,
          ).toBe(true)
        }
      }
    }
  })

  test('a phone shell never receives a desktop-only section, and vice versa', () => {
    // devices 缺省=双端，故用 ?? [] 归一后再断言「不含另一端」
    for (const section of getVisibleSections(1, 'mobile')) {
      expect(section.devices ?? [], `${section.id} 出现在手机壳却标了桌面`).not.toContain(
        'desktop',
      )
    }
    for (const section of getVisibleSections(1, 'desktop')) {
      expect(section.devices ?? [], `${section.id} 出现在桌面壳却标了手机`).not.toContain(
        'mobile',
      )
    }
    expect(visibleSectionIds(1, 'mobile')).not.toContain('monitor')
    expect(visibleSectionIds(1, 'mobile')).not.toContain('yahoo')
  })

  test('empty features and sections are dropped rather than rendered as headings', () => {
    for (const role of ROLES) {
      for (const device of DEVICES) {
        for (const section of getVisibleSections(role, device)) {
          expect(section.features.length, `${section.id} 是空章`).toBeGreaterThan(0)
          for (const feature of section.features) {
            expect(feature.steps.length, `${section.id}.${feature.id} 是空功能`).toBeGreaterThan(0)
          }
        }
      }
    }
  })
})

describe('manual rendering helpers', () => {
  test('pick falls back to Japanese when a language is absent', () => {
    expect(pick({ ja: 'あ', zh: '', en: 'A' }, 'zh')).toBe('あ')
    expect(pick({ ja: 'あ', zh: '甲', en: 'A' }, 'zh')).toBe('甲')
    expect(pick(undefined, 'en')).toBe('')
  })

  test('pickList falls back to Japanese when a language is absent', () => {
    expect(pickList({ ja: ['あ'], zh: [], en: ['A'] }, 'zh')).toEqual(['あ'])
    expect(pickList(undefined, 'en')).toEqual([])
  })

  test('toLang maps every locale the app ships down to a manual language code', () => {
    expect(toLang('ja-JP')).toBe('ja')
    expect(toLang('zh-CN')).toBe('zh')
    expect(toLang('en-US')).toBe('en')
    expect(toLang('fr-FR')).toBe('ja')
  })
})
