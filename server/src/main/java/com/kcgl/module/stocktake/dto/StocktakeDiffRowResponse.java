package com.kcgl.module.stocktake.dto;

import com.kcgl.module.stocktake.StocktakeDiffEntity;

/** 差异行（diffType 1盘亏/2盘盈/3仓库不符/4冻结品；note 非空=盘点期间有变动流水）。 */
public record StocktakeDiffRowResponse(
        long id,
        long itemId,
        String itemCode,
        int diffType,
        Integer expectedWarehouse,
        Integer actualWarehouse,
        String note,
        int confirmStatus,
        String thumbUrl) {

    public static StocktakeDiffRowResponse from(StocktakeDiffEntity diff, String thumbUrl) {
        return new StocktakeDiffRowResponse(
                diff.getId(), diff.getItemId(), diff.getItemCode(), diff.getDiffType(),
                diff.getExpectedWh(), diff.getActualWh(), diff.getNote(),
                diff.getConfirmStatus(), thumbUrl);
    }
}
