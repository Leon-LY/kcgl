package com.kcgl.module.excel;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import cn.idev.excel.write.style.column.SimpleColumnWidthStyleStrategy;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemService;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Excel 流式导出（M4-⑤，D-058 F）：报告口径 25 列（非往返格式——导入 19 列无销售态/
 * 利润列，导出是经营报表；两格式刻意不互通，误把导出文件再导入=表头契约校验拦下）。
 *
 * <p>筛选语义与打印列表完全一致（{@link ItemService#forEachItemForExport} 单一数据源，
 * 两处查询口径不可能漂移）；keyset 分页逐批 500 行写 sheet——5 万行不整表进堆。
 *
 * <p>文案口径与前端界面同词（D-058 F）：库存态 移動中/在庫/出庫済み、销售态
 * 未出品/出品中/落札済み/キャンセル、仓库 名古屋/福岡——导出文件与屏幕对照不产生
 * 第二套词汇。表头固定日文（报告格式，与导入列名的部署可配置不同源——经营报表无
 * 变更诉求）。自由文本单元格过 {@link ExcelSanitizer}（公式注入消毒）；状态/日期/
 * 数值为系统生成值不经消毒。V 角色可见成本/利润（A19 需求字面默认）。
 */
@Service
public class ExcelExportService {

    /** 分批写 sheet 批大小（与 keyset 分页同量级，写盘粒度而非堆上限）。 */
    private static final int WRITE_BATCH = 500;
    /** 登録日時格式（分粒度——秒/毫秒对报表无价值）。 */
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILEDATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 25 列表头（固定顺序，rowOf 必须与之同步）。 */
    private static final List<String> EXPORT_COLUMNS = List.of(
            "管理番号", "会場コード", "落札日", "仕入単価", "手数料", "送料", "消費税", "原価合計",
            "倉庫", "入庫日", "棚番号", "グループ番号", "撮影日", "備考", "商品名", "分類",
            "作者・窯元", "サイズ", "重量（g）", "販売チャネル", "販売状況", "在庫状況",
            "売却価格", "利益", "登録日時");
    private static final List<List<String>> EXPORT_HEADS =
            EXPORT_COLUMNS.stream().map(List::of).toList();

    private final ItemService itemService;
    private final Clock clock;

    public ExcelExportService(ItemService itemService, Clock clock) {
        this.itemService = itemService;
        this.clock = clock;
    }

    /** 下载文件名：商品一覧_YYYYMMDD.xlsx（JST 当日）。 */
    public String exportFilename() {
        return "商品一覧_" + LocalDate.now(clock).format(FILEDATE) + ".xlsx";
    }

    /**
     * 筛选事前校验（流式响应的响应头提交前必须完成——StreamingResponseBody 异步体内
     * 抛 BizException 时状态码已不可改，会变成半截 200 坏文件）。码条件优先于日期
     * （単票抽出与打印列表同语义）：code 非空时不校验区间。
     */
    public void requireValidFilters(LocalDate createdFrom, LocalDate createdTo, String code) {
        if (code == null || code.isBlank()) {
            if (createdFrom.isAfter(createdTo)) {
                throw new BizException(ErrorCode.VALIDATION, "作成日範囲の開始が終了より後になっています");
            }
        }
    }

    /** 流式写出：筛选同打印列表；0 行也写出合法带表头文件。 */
    public void writeExport(OutputStream out, LocalDate createdFrom, LocalDate createdTo,
            Long venueId, String code) {
        requireValidFilters(createdFrom, createdTo, code);
        try (ExcelWriter writer = FastExcel.write(out)
                .registerWriteHandler(new SimpleColumnWidthStyleStrategy(16))
                .build()) {
            WriteSheet sheet = FastExcel.writerSheet(0, "商品一覧")
                    .head(EXPORT_HEADS)
                    .build();
            List<List<Object>> buffer = new ArrayList<>(WRITE_BATCH);
            itemService.forEachItemForExport(createdFrom, createdTo, venueId, code, item -> {
                buffer.add(rowOf(item));
                if (buffer.size() >= WRITE_BATCH) {
                    writer.write(buffer, sheet);
                    buffer.clear();
                }
            });
            // 无条件终批：空结果也落表头行（0 行导出≠坏文件）
            writer.write(buffer, sheet);
        }
    }

    // ------------------------------------------------------------- 行组装

    /** 25 列行组装（与 EXPORT_COLUMNS 顺序一一对应；Arrays.asList 容 null=空单元格）。 */
    private static List<Object> rowOf(ItemEntity item) {
        return Arrays.asList(
                ExcelSanitizer.sanitize(item.getItemCode()),
                ExcelSanitizer.sanitize(item.getVenueCode()),
                dateText(item.getBuyDate()),
                item.getPurchasePrice(),
                item.getFee(),
                item.getShippingFee(),
                item.getTax(),
                item.getTotalCost(),
                warehouseText(item.getWarehouse()),
                dateText(item.getWarehouseInDate()),
                ExcelSanitizer.sanitize(item.getShelfNo()),
                ExcelSanitizer.sanitize(item.getGroupNo()),
                dateText(item.getPhotoDate()),
                ExcelSanitizer.sanitize(item.getRemark()),
                ExcelSanitizer.sanitize(item.getItemName()),
                ExcelSanitizer.sanitize(item.getCategory()),
                ExcelSanitizer.sanitize(item.getAuthorKiln()),
                ExcelSanitizer.sanitize(item.getSizeText()),
                item.getWeightG(),
                ExcelSanitizer.sanitize(item.getSalesChannel()),
                saleText(item.getSaleStatus()),
                stockText(item.getStockStatus()),
                item.getSoldPrice(),
                item.getProfit(),
                dateTimeText(item.getCreatedAt()));
    }

    /** 倉庫（与录入/列表同词：1=名古屋 2=福岡）。 */
    private static String warehouseText(Integer warehouse) {
        if (warehouse == null) {
            return null;
        }
        return warehouse == 1 ? "名古屋" : "福岡";
    }

    /** 在庫状況（前端 scan.stock 同词）。 */
    private static String stockText(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "移動中";
            case 1 -> "在庫";
            case 2 -> "出庫済み";
            default -> null;
        };
    }

    /** 販売状況（前端 scan.sale 同词）。 */
    private static String saleText(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "未出品";
            case 1 -> "出品中";
            case 2 -> "落札済み";
            case 3 -> "キャンセル";
            default -> null;
        };
    }

    private static String dateText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String dateTimeText(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATETIME);
    }
}
