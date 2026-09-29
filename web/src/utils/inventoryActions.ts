/**
 * 扫码页动作可用性表（M3-④）：后端 InventoryStateMachine 五元组边表的前端镜像
 * （docs/01 7.2 唯一定义）。菜单只渲染当前态合法的动作——非法组合后端仍兜底 409008，
 * 前端表只负责不给出会失败的入口，两表由集成测试+本模块单测共同锚定一致性。
 *
 * 库存态：0在途 1在库 2已出库｜销售态：0未上架 1在售 2成交 3取消
 */

/** 扫码页可执行的动作（盘点与受注导入的 SOLD_MARK 不在此页入口）。 */
export type ScanAction =
  | 'sell'
  | 'scrap'
  | 'transfer'
  | 'markListed'
  | 'markCanceled'
  | 'returnCustomer'
  | 'returnVenue'

/** 一条可用性边：stock/sale=null 表示任意当前值。 */
interface ActionEdge {
  action: ScanAction
  stock: number | null
  sale: number | null
}

const EDGES: ActionEdge[] = [
  // 卖出：在库→已出库，销售任意→成交（A10：未上架线下直卖允许）
  { action: 'sell', stock: 1, sale: null },
  // 报废：在库→已出库，任意销售态→取消
  { action: 'scrap', stock: 1, sale: null },
  // 调拨：在库↔在库（仓 A→B）
  { action: 'transfer', stock: 1, sale: null },
  // 上架标记：在库未上架→在售；流拍后重上（在库已取消→在售，D-069）
  { action: 'markListed', stock: 1, sale: 0 },
  { action: 'markListed', stock: 1, sale: 3 },
  // 取消标记（手动）：在售→取消（流拍/出品取消登记口；SOLD_MARK 仅受注导入，D-069）
  { action: 'markCanceled', stock: 1, sale: 1 },
  // 退货·顾客退回：已出库→在库，须成交态
  { action: 'returnCustomer', stock: 2, sale: 2 },
  // 退货·退回拍卖场：在途/在库→已出库
  { action: 'returnVenue', stock: 0, sale: null },
  { action: 'returnVenue', stock: 1, sale: null },
]

/** 菜单渲染顺序=现场使用频率（卖出最常用，退货双向最少用；取消标记紧随上架标记成对出现）。 */
const MENU_ORDER: ScanAction[] = ['sell', 'transfer', 'scrap', 'markListed', 'markCanceled', 'returnCustomer', 'returnVenue']

/** 当前态可执行动作（按现场频率排序）；无可执行动作返回空数组（已出库未成交等终态场景）。 */
export function availableActions(stockStatus: number, saleStatus: number): ScanAction[] {
  return MENU_ORDER.filter((action) =>
    EDGES.some(
      (edge) =>
        edge.action === action &&
        (edge.stock === null || edge.stock === stockStatus) &&
        (edge.sale === null || edge.sale === saleStatus),
    ),
  )
}
