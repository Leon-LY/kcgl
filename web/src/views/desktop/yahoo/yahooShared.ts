import type { Composer } from 'vue-i18n'

/**
 * 雅虎页两个页签共用的行内文案取法（出荷待ち与照合各有一张表要显示仓库）。
 *
 * 取 t 返回取法的形式，与 itemListShared.ts 的 itemListDisplay 同形：模板里
 * 调用点写成 warehouseOf(row.warehouse)，不多传一个 t，与拆分前逐字一样。
 *
 * 放 .ts 而不是 composable：没有响应式状态、没有生命周期，给个 t 就能算。
 */
export function yahooDisplay(t: Composer['t']) {
  return {
    /**
     * 仓库列的文案。空值必须兜底：el-table-column 渲染列时会以 {row:{}} 探测
     * 嵌套列（TableColumnRenderer），动态 i18n key 不兜底的话，空数据页也刷
     * missing-key 告警（D-056）。
     */
    warehouseOf(warehouse: number | null | undefined): string {
      return warehouse == null ? '—' : t(`common.warehouse.${warehouse}`)
    },
  }
}
