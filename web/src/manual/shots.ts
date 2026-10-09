/**
 * 手册截图的 URL 解析（M7）。
 *
 * 图片是 src/manual/screenshots/** 下的静态资产，被 vite 处理成带 hash 的 URL；
 * JSON 里存的是可读路径（如 "03-desktop/items.jpg"），渲染时经这里查表换 URL。
 * 之所以不把 URL 直接写进 JSON：JSON 是跨 Vite 与 Node 两侧共用的内容源，
 * 塞进构建期才存在的 URL 会让 PDF 侧（直接 fs 读文件）拿不到。
 *
 * 开发/构建与打包都走同一张表，缺图（写错路径）时返回 undefined，渲染端据此
 * 不画 figure——静默降级好过一张碎图。存在性由 manual.spec.ts 在磁盘上硬校验。
 */
const modules = import.meta.glob('./screenshots/**/*.{jpg,jpeg,png}', {
  eager: true,
  query: '?url',
  import: 'default',
}) as Record<string, string>

/** JSON 里的 `image` 值 → 实际资源 URL；未收录返回 undefined */
export function shotUrl(image: string | undefined): string | undefined {
  if (!image) return undefined
  return modules[`./screenshots/${image}`]
}
