package com.kcgl.module.item.dto;

/**
 * 扫码定位响应（M3-⑤）：item=管理号命中的商品——含作废/软删件（deleted/voided 标志
 * 驱动扫码页按角色呈现禁操作态，docs/01 六节 by-code 行）；reEntry=作废重录链终点
 * （docs/01 7.1「扫旧码必须能查到新号」，void_re_entry 反链逐跳），未重录=null。
 */
public record ItemByCodeResponse(ItemResponse item, ReEntry reEntry) {

    /** 重录新件（扫码页提示「重录后的新号是 XX」，盘点时新旧对得上）。 */
    public record ReEntry(long itemId, String itemCode) {
    }
}
