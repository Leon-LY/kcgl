package com.kcgl.module.inventory;

import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;

import java.util.List;

/**
 * 双状态机五元组边表（docs/01 7.2 唯一定义）：库存 0在途/1在库/2已出库，销售 0未上架/1在售/2成交/3取消。
 *
 * <p>语义约定：from=null 表示当前值任意（不校验）；to=null 表示保持当前值不变。
 * 边表一次定型全部动作（含 M3 端点），非法组合一律 {@link ErrorCode#INVALID_TRANSITION}——
 * 「机器可读边表 + 全合法边/全非法边枚举单测」是状态机正确性的全部证明。
 */
public final class InventoryStateMachine {

    private InventoryStateMachine() {
    }

    /** 一条合法迁移边。 */
    public record Transition(InventoryAction action, Integer stockFrom, Integer stockTo,
            Integer saleFrom, Integer saleTo) {
    }

    /** 动作结果：目标库存/销售态（to=null 已解出为当前值）。 */
    public record Outcome(int stockTo, int saleTo) {
    }

    private static final List<Transition> EDGES = List.of(
            // 到仓：在途→在库，销售态保持（入库前不应有在售态，但 CSV 时序差下不强校验）
            new Transition(InventoryAction.ARRIVAL, 0, 1, null, null),
            // 卖出：在库→已出库，销售任意→成交（A10：未上架线下直卖允许）
            new Transition(InventoryAction.SELL, 1, 2, null, 2),
            // 报废：在库→已出库，任意销售态→取消
            new Transition(InventoryAction.SCRAP, 1, 2, null, 3),
            // 调拨：在库→在库（stock 不变，wh A→B 由 ledger 双向入账表达）
            new Transition(InventoryAction.TRANSFER, 1, 1, null, null),
            // 退货·顾客退回：已出库→在库，成交→取消
            new Transition(InventoryAction.RETURN_CUSTOMER, 2, 1, 2, 3),
            // 退货·退回拍卖场：在途/在库→已出库，→取消
            new Transition(InventoryAction.RETURN_VENUE, 0, 2, null, 3),
            new Transition(InventoryAction.RETURN_VENUE, 1, 2, null, 3),
            // 上架标记：在库（实物未动），未上架→在售；取消→在售（重新出品——
            // 受注表无在售信息，手动标记是 LIST_UP 唯一来源，D-069）
            new Transition(InventoryAction.LIST_UP, 1, 1, 0, 1),
            new Transition(InventoryAction.LIST_UP, 1, 1, 3, 1),
            // 成交标记（受注导入）：在库，在售→成交；未上架→成交（受注直报——
            // 首次导入晚于出品时系统从未记录 LIST_UP，语义同 SELL 的 A10 未上架直卖）；
            // 取消→成交（受注表=成交事实集：流拍后重新出品落札的订单，不补此边
            // 则该件永不进出荷待ち，D-069）
            new Transition(InventoryAction.SOLD_MARK, 1, 1, 1, 2),
            new Transition(InventoryAction.SOLD_MARK, 1, 1, 0, 2),
            new Transition(InventoryAction.SOLD_MARK, 1, 1, 3, 2),
            // 取消标记（手动，D-069）：在库，在售→取消（流拍/出品取消的登记口——
            // 受注表无取消信息，手动标记是 CANCEL_MARK 唯一来源；
            // 旧 CSV「取消→在售=重新出品」边随之退场，重上走 LIST_UP）
            new Transition(InventoryAction.CANCEL_MARK, 1, 1, 1, 3),
            // 盘点调整：盘亏 在库→已出库；盘盈 已出库/在途→在库；仓错 在库→在库（仅仓变化）
            new Transition(InventoryAction.STOCKTAKE_LOSS, 1, 2, null, null),
            new Transition(InventoryAction.STOCKTAKE_GAIN, 2, 1, null, null),
            new Transition(InventoryAction.STOCKTAKE_GAIN, 0, 1, null, null),
            new Transition(InventoryAction.STOCKTAKE_WH, 1, 1, null, null)
            );

    /** 校验并应用动作：当前 (stock, sale) 命中某条边→返回目标态；未命中任何边→409008。 */
    public static Outcome apply(InventoryAction action, int stock, int sale) {
        for (Transition edge : EDGES) {
            if (edge.action() == action
                    && (edge.stockFrom() == null || edge.stockFrom() == stock)
                    && (edge.saleFrom() == null || edge.saleFrom() == sale)) {
                return new Outcome(
                        edge.stockTo() != null ? edge.stockTo() : stock,
                        edge.saleTo() != null ? edge.saleTo() : sale);
            }
        }
        throw new BizException(ErrorCode.INVALID_TRANSITION);
    }

    /** 边表只读视图（单测全合法边枚举用）。 */
    public static List<Transition> edges() {
        return EDGES;
    }
}
