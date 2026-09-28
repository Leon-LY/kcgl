package com.kcgl.module.yahoo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * 雅虎 CSV 管线配置（docs/01 7.4）。列名/状态文本映射放 yml 而非 admin UI——
 * 雅虎格式变更本需发版验证，JSON 编辑框是给非技术甲方的陷阱；A4 样张到位前
 * 按公开常识默认，到位后改配置重启即适配（列映射校准不阻塞开发的架构前提）。
 */
@ConfigurationProperties(prefix = "kcgl.yahoo")
public record YahooProperties(
        String importsDir, Long maxFileBytes, Integer maxRows, Integer zombieMinutes,
        Integer shipmentDelayWarnDays, Columns columns, StatusValues statusValues) {

    public YahooProperties {
        if (importsDir == null || importsDir.isBlank()) {
            importsDir = "./data/imports";
        }
        if (maxFileBytes == null) {
            maxFileBytes = 52_428_800L; // 50MB（docs/01 7.4：5 万商品全量导出约 12-20MB，余量覆盖）
        }
        if (maxRows == null) {
            maxRows = 200_000;
        }
        if (zombieMinutes == null) {
            zombieMinutes = 30; // 僵尸批次自愈阈值：processing 超此时长标记失败
        }
        if (shipmentDelayWarnDays == null) {
            shipmentDelayWarnDays = 7; // 出荷待ち滞留标红阈值（对账视角）
        }
        if (columns == null) {
            columns = new Columns(null, null, null, null, null, null, null);
        }
        if (statusValues == null) {
            statusValues = new StatusValues(null, null, null);
        }
    }

    /** 测试/默认构造（全走 compact 构造器的兜底值）。 */
    public YahooProperties() {
        this(null, null, null, null, null, null, null);
    }

    public Path importsRoot() {
        return Path.of(importsDir);
    }

    /** CSV 列名映射（雅虎出品管理 CSV 的日文表头）。 */
    public record Columns(String auctionId, String itemCode, String listPrice, String soldPrice,
            String status, String listedAt, String closedAt) {

        public Columns {
            if (auctionId == null || auctionId.isBlank()) {
                auctionId = "オークションID";
            }
            if (itemCode == null || itemCode.isBlank()) {
                itemCode = "商品コード";
            }
            if (listPrice == null || listPrice.isBlank()) {
                listPrice = "現在価格";
            }
            if (soldPrice == null || soldPrice.isBlank()) {
                soldPrice = "落札価格";
            }
            if (status == null || status.isBlank()) {
                status = "状態";
            }
            if (listedAt == null || listedAt.isBlank()) {
                listedAt = "出品日時";
            }
            if (closedAt == null || closedAt.isBlank()) {
                closedAt = "終了日時";
            }
        }

        /** 七列名固定顺序（缺列校验消息与测试夹具共用）。 */
        public List<String> headerOrder() {
            return List.of(auctionId, itemCode, listPrice, soldPrice, status, listedAt, closedAt);
        }
    }

    /**
     * 状态文本映射（contains 判定）。顺序敏感：取消先于成交——
     * 「落札されませんでした」含「落札」但绝不能判成成交。
     */
    public record StatusValues(List<String> onSale, List<String> sold, List<String> canceled) {

        public StatusValues {
            if (onSale == null || onSale.isEmpty()) {
                onSale = List.of("出品中");
            }
            if (sold == null || sold.isEmpty()) {
                sold = List.of("落札されました", "落札");
            }
            if (canceled == null || canceled.isEmpty()) {
                canceled = List.of("落札されませんでした", "取消", "取り消し");
            }
        }
    }
}
