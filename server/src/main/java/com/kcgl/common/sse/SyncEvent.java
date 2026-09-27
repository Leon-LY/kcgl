package com.kcgl.common.sse;

import java.time.LocalDateTime;

/**
 * SSE 事件信封（docs/01 7.6 唯一定义）：{seq, type, entity, operatorId, at}。
 * seq=集线器级单调递增；type=粗粒度资源域（客户端按域失效重取，不传增量补丁）；
 * entity=资源标识（如 item id/管理号），供提示文案定位；operatorId=null 表示系统事件。
 */
public record SyncEvent(long seq, String type, String entity, Long operatorId, LocalDateTime at) {

    /** 初始事件：连接确认（连接即达，客户端据此时钟判定断线时长与全量刷新）。 */
    public static final String TYPE_HELLO = "HELLO";

    /** 商品域：新增/作废/重录/字段更新。 */
    public static final String TYPE_ITEM = "ITEM";

    /** 库存域：到仓/卖出/报废/调拨/退货/盘点调整。 */
    public static final String TYPE_INVENTORY = "INVENTORY";

    /** 图片域：绑定/解绑/排序。 */
    public static final String TYPE_IMAGE = "IMAGE";

    /** 盘点域：发起/扫码/close/差异确认。 */
    public static final String TYPE_STOCKTAKE = "STOCKTAKE";

    /** 雅虎导入域：批次完成。 */
    public static final String TYPE_YAHOO_IMPORT = "YAHOO_IMPORT";

    /** 设置域：sys_setting 变更。 */
    public static final String TYPE_SETTING = "SETTING";

    /** 字典域：会场/档位/年代号变更。 */
    public static final String TYPE_DICT = "DICT";
}
