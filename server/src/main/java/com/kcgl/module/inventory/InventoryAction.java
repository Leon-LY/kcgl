package com.kcgl.module.inventory;

/**
 * 库存动作（docs/01 7.2 状态机矩阵的动作轴）。
 * M2-8 实装 ARRIVAL；其余动作随 M3 各端点逐个消费——边表在 InventoryStateMachine 一次定型，
 * 端点只是边表的展开（先表后端点防口径分叉）。
 *
 * <p>VOID 不在本枚举：作废是「冻结」语义（禁一切迁移+在库件仓账 −1），由 ItemService 专用路径处理；
 * ADJUST（手工修正）是管理员任意态覆盖（reason 必填），不走边表校验。
 */
public enum InventoryAction {

    /** 到仓确认：在途→在库（到货核对页/扫码入库共用）。 */
    ARRIVAL,
    /** 卖出：在库→已出库，销售态→成交（未上架线下直卖允许，A10）。 */
    SELL,
    /** 报废：在库→已出库，任意销售态→取消。 */
    SCRAP,
    /** 调拨：在库↔在库，仓 A→B（stock 不变，wh 在 ledger 双向入账）。 */
    TRANSFER,
    /** 退货·顾客退回：已出库→在库，成交→取消。 */
    RETURN_CUSTOMER,
    /** 退货·退回拍卖场：在途/在库→已出库，→取消。 */
    RETURN_VENUE,
    /** 上架标记：在库，未上架→在售。 */
    LIST_UP,
    /** 成交标记（CSV 回传）：在库，在售→成交。 */
    SOLD_MARK,
    /** 取消标记（CSV 回传）：在库，在售→取消；取消→在售（重新出品）。 */
    CANCEL_MARK,
    /** 盘点调整·盘亏：在库→已出库。 */
    STOCKTAKE_LOSS,
    /** 盘点调整·盘盈：已出库/在途→在库。 */
    STOCKTAKE_GAIN,
    /** 盘点调整·仓错：在库→在库，仅仓变化。 */
    STOCKTAKE_WH
}
