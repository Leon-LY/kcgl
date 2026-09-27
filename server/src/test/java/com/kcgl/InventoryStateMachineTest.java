package com.kcgl;

import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.inventory.InventoryAction;
import com.kcgl.module.inventory.InventoryStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机边表全量证明（docs/01 7.2 矩阵 ↔ 边表 ↔ 测试三方一致）：
 * - 全合法边：边表每条边取一个命中当前态，apply 成功且目标态与边定义一致（to=null 解为当前值）
 * - 全非法边：动作 × 状态全空间（3 库存 × 4 销售）枚举，未被任何边覆盖的组合必 409008
 * 边表改动（新增动作/改边）必然改变两个枚举的覆盖集，测试随之更新——单测即矩阵的活文档。
 */
class InventoryStateMachineTest {

    private static final List<Integer> STOCKS = List.of(0, 1, 2);
    private static final List<Integer> SALES = List.of(0, 1, 2, 3);

    @Test
    void apply_everyLegalEdge_matchesCurrentState_returnsExpectedOutcome() {
        for (InventoryStateMachine.Transition edge : InventoryStateMachine.edges()) {
            int stock = edge.stockFrom() != null ? edge.stockFrom() : 1;
            int sale = edge.saleFrom() != null ? edge.saleFrom() : 0;
            InventoryStateMachine.Outcome outcome =
                    InventoryStateMachine.apply(edge.action(), stock, sale);
            assertThat(outcome.stockTo())
                    .as("%s stock %d→%d", edge.action(), stock, edge.stockTo())
                    .isEqualTo(edge.stockTo() != null ? edge.stockTo() : stock);
            assertThat(outcome.saleTo())
                    .as("%s sale %d→%d", edge.action(), sale, edge.saleTo())
                    .isEqualTo(edge.saleTo() != null ? edge.saleTo() : sale);
        }
    }

    @ParameterizedTest
    @EnumSource(InventoryAction.class)
    void apply_fullStateSpace_noMatchingEdge_throws409008(InventoryAction action) {
        Set<String> legal = InventoryStateMachine.edges().stream()
                .filter(edge -> edge.action() == action)
                .flatMap(edge -> {
                    List<Integer> stocks = edge.stockFrom() != null ? List.of(edge.stockFrom()) : STOCKS;
                    List<Integer> sales = edge.saleFrom() != null ? List.of(edge.saleFrom()) : SALES;
                    return stocks.stream().flatMap(s -> sales.stream().map(v -> s + ":" + v));
                })
                .collect(Collectors.toSet());
        assertThat(legal)
                .as("%s 至少有一条合法边（无合法边的动作不该存在）", action)
                .isNotEmpty();

        for (int stock : STOCKS) {
            for (int sale : SALES) {
                if (legal.contains(stock + ":" + sale)) {
                    continue;
                }
                assertThatThrownBy(() -> InventoryStateMachine.apply(action, stock, sale))
                        .as("%s 于 stock=%d sale=%d 应为非法边", action, stock, sale)
                        .isInstanceOf(BizException.class)
                        .extracting(e -> ((BizException) e).errorCode().code())
                        .isEqualTo(ErrorCode.INVALID_TRANSITION.code());
            }
        }
    }

    @Test
    void apply_keyMatrixRows_matchDocs0172() {
        // 抽查矩阵中语义最易错的行（完整覆盖由上面两个枚举测试保证，这里是矩阵文档锚点）
        // 卖出：在库→已出库，销售任意→成交（A10 未上架直卖允许）
        assertThat(InventoryStateMachine.apply(InventoryAction.SELL, 1, 0))
                .isEqualTo(new InventoryStateMachine.Outcome(2, 2));
        // 到仓：在途→在库，销售态保持
        assertThat(InventoryStateMachine.apply(InventoryAction.ARRIVAL, 0, 1))
                .isEqualTo(new InventoryStateMachine.Outcome(1, 1));
        // 取消标记：取消→在售（重新出品）允许；未上架→取消不存在
        assertThat(InventoryStateMachine.apply(InventoryAction.CANCEL_MARK, 1, 3))
                .isEqualTo(new InventoryStateMachine.Outcome(1, 1));
        // 在途退回拍卖场：在途→已出库合法；在库卖出前的报废同边不同动作
        assertThat(InventoryStateMachine.apply(InventoryAction.RETURN_VENUE, 0, 1))
                .isEqualTo(new InventoryStateMachine.Outcome(2, 3));
    }
}
