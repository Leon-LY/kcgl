package com.kcgl.module.excel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Excel 管线配置（docs/01 3.1，D-058 E）。列名放 yml 而非 admin UI——与雅虎 CSV
 * 同理：格式变更本需发版验证，列名需调整时改配置段重启即适配。
 * 19 列固定索引契约：前 13 列对齐录入表单字段，后 6 列为扩展属性（D-017）。
 */
@ConfigurationProperties(prefix = "kcgl.excel")
public record ExcelProperties(
        String importsDir, Long maxFileBytes, Integer maxRows, Integer zombieMinutes,
        Columns columns) {

    public ExcelProperties {
        if (importsDir == null || importsDir.isBlank()) {
            importsDir = "./data/excel-imports";
        }
        if (maxFileBytes == null) {
            maxFileBytes = 20_971_520L; // 20MB：2 万行 xlsx 上限
        }
        if (maxRows == null) {
            maxRows = 20_000; // 两周日量（1000 件/天）余量
        }
        if (zombieMinutes == null) {
            zombieMinutes = 30; // 僵尸批次自愈阈值：processing 超此时长标记失败
        }
        if (columns == null) {
            columns = new Columns(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 测试/默认实例工厂（全走 compact 构造器的兜底值）。
     * 必须是静态方法而非额外无参构造器：Boot 的 deduceBindConstructor 遇到第二个
     * 非 private 构造器即放弃构造器绑定→退化为 JavaBean 绑定→record 无 setter→
     * 整个属性类静默不绑定（kcgl.excel.max-rows 等全部失效、恒为默认值）。
     */
    public static ExcelProperties defaults() {
        return new ExcelProperties(null, null, null, null, null);
    }

    public Path importsRoot() {
        return Path.of(importsDir);
    }

    /** 19 列模板表头（固定索引顺序——表头校验与模板生成共用同一来源）。 */
    public record Columns(String itemCode, String venueCode, String buyDate, String purchasePrice,
            String fee, String shippingFee, String tax, String warehouse, String warehouseInDate,
            String shelfNo, String groupNo, String photoDate, String remark, String itemName,
            String category, String authorKiln, String sizeText, String weightG,
            String salesChannel) {

        public Columns {
            if (itemCode == null || itemCode.isBlank()) {
                itemCode = "管理番号";
            }
            if (venueCode == null || venueCode.isBlank()) {
                venueCode = "会場コード";
            }
            if (buyDate == null || buyDate.isBlank()) {
                buyDate = "落札日";
            }
            if (purchasePrice == null || purchasePrice.isBlank()) {
                purchasePrice = "仕入単価";
            }
            if (fee == null || fee.isBlank()) {
                fee = "手数料";
            }
            if (shippingFee == null || shippingFee.isBlank()) {
                shippingFee = "送料";
            }
            if (tax == null || tax.isBlank()) {
                tax = "消費税";
            }
            if (warehouse == null || warehouse.isBlank()) {
                warehouse = "倉庫";
            }
            if (warehouseInDate == null || warehouseInDate.isBlank()) {
                warehouseInDate = "入庫日";
            }
            if (shelfNo == null || shelfNo.isBlank()) {
                shelfNo = "棚番号";
            }
            if (groupNo == null || groupNo.isBlank()) {
                groupNo = "グループ番号";
            }
            if (photoDate == null || photoDate.isBlank()) {
                photoDate = "撮影日";
            }
            if (remark == null || remark.isBlank()) {
                remark = "備考";
            }
            if (itemName == null || itemName.isBlank()) {
                itemName = "商品名";
            }
            if (category == null || category.isBlank()) {
                category = "分類";
            }
            if (authorKiln == null || authorKiln.isBlank()) {
                authorKiln = "作者・窯元";
            }
            if (sizeText == null || sizeText.isBlank()) {
                sizeText = "サイズ";
            }
            if (weightG == null || weightG.isBlank()) {
                weightG = "重量（g）";
            }
            if (salesChannel == null || salesChannel.isBlank()) {
                salesChannel = "販売チャネル";
            }
        }

        /** 19 列名固定顺序（表头校验消息与模板生成共用）。 */
        public List<String> headerOrder() {
            return List.of(itemCode, venueCode, buyDate, purchasePrice, fee, shippingFee, tax,
                    warehouse, warehouseInDate, shelfNo, groupNo, photoDate, remark, itemName,
                    category, authorKiln, sizeText, weightG, salesChannel);
        }
    }
}
