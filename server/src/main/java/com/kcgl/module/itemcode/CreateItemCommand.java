package com.kcgl.module.itemcode;

import java.time.LocalDate;

/**
 * 录入指令（管理号引擎入口）。operator 显式传参——异步/重放路径无 SecurityContext 可依赖；
 * 审计操作人仍取 SecurityContext，与台账快照双轨（docs/01 5.3）。
 *
 * @param clientReqId 幂等键（可空=不启用重放读回；前端连续录入恒携带）
 */
public record CreateItemCommand(
        String clientReqId,
        Long venueId,
        LocalDate buyDate,
        LocalDate photoDate,
        Long purchasePrice,
        Long fee,
        Long shippingFee,
        Long tax,
        Integer warehouse,
        String shelfNo,
        LocalDate warehouseInDate,
        String groupNo,
        String remark,
        String itemName,
        String category,
        String authorKiln,
        String sizeText,
        Integer weightG,
        String salesChannel,
        Long operatorId,
        String operatorName) {
}
