package com.kcgl.module.excel;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import cn.idev.excel.write.style.column.SimpleColumnWidthStyleStrategy;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 模板下载（D-058 E）：双 Sheet——①商品=19 列表头（无样例行：样例数据被原样导入是
 * 经典事故，说明放第二 Sheet）；②記入方法=逐列填写规范。列名取自部署配置
 * （ExcelProperties），模板与导入表头校验同源——改配置即同时改两处。
 */
@Service
public class ExcelTemplateService {

    private final ExcelProperties props;

    public ExcelTemplateService(ExcelProperties props) {
        this.props = props;
    }

    public void writeTemplate(OutputStream out) {
        List<List<String>> header = head(props.columns().headerOrder());
        List<List<String>> guideHead = List.of(List.of("項目"), List.of("記入方法"));
        List<List<Object>> guideRows = guideRows();

        try (ExcelWriter writer = FastExcel.write(out)
                .registerWriteHandler(new SimpleColumnWidthStyleStrategy(20))
                .build()) {
            // 商品 Sheet：仅表头（空数据写出表头行）
            WriteSheet data = FastExcel.writerSheet(0, "商品")
                    .head(header)
                    .build();
            writer.write(List.of(), data);
            // 記入方法 Sheet：静态说明文本
            WriteSheet guide = FastExcel.writerSheet(1, "記入方法")
                    .head(guideHead)
                    .registerWriteHandler(new SimpleColumnWidthStyleStrategy(60))
                    .build();
            writer.write(guideRows, guide);
        }
    }

    private static List<List<String>> head(List<String> names) {
        return names.stream().map(List::of).toList();
    }

    /** 逐列填写规范（与 ExcelRowParser 校验口径一一对应，两处须同步改）。 */
    private List<List<Object>> guideRows() {
        ExcelProperties.Columns cols = props.columns();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("（共通）", "1行目の見出しは変更・削除しないでください。列の追加は右端以降にのみ可能です。"));
        rows.add(row("（共通）", "「商品」シートの2行目以降にデータを入力し、「記入方法」シートは削除しないでください。"));
        rows.add(row(cols.itemCode(),
                "空欄=自動採番（新規登録）。記入=既存の管理番号として登録（例：HT9-A1X）。半角大文字で入力してください。"));
        rows.add(row(cols.venueCode(), "必須。登録済みの会場コードを半角英字2桁で入力してください（例：HT）。"));
        rows.add(row(cols.buyDate(), "必須。2026-09-15 または 2026/9/15 形式。未来日は登録できません。"));
        rows.add(row(cols.purchasePrice(), "必須。1～99,999,999（円）。￥やカンマ付きでも読み取れます。"));
        rows.add(row(cols.fee(), "任意。0～99,999,999（円）。"));
        rows.add(row(cols.shippingFee(), "任意。0～99,999,999（円）。"));
        rows.add(row(cols.tax(), "任意。0～99,999,999（円）。"));
        rows.add(row(cols.warehouse(), "必須。1（名古屋）または 2（福岡）。倉庫名でも入力できます。"));
        rows.add(row(cols.warehouseInDate(), "任意。日付形式は落札日と同じ。未来日も入力できます。"));
        rows.add(row(cols.shelfNo(), "任意。32文字以内。"));
        rows.add(row(cols.groupNo(), "任意。32文字以内。"));
        rows.add(row(cols.photoDate(), "任意。日付形式は落札日と同じ。未来日は登録できません。"));
        rows.add(row(cols.remark(), "任意。500文字以内。"));
        rows.add(row(cols.itemName(), "任意。200文字以内。"));
        rows.add(row(cols.category(), "任意。64文字以内。"));
        rows.add(row(cols.authorKiln(), "任意。128文字以内。"));
        rows.add(row(cols.sizeText(), "任意。64文字以内。"));
        rows.add(row(cols.weightG(), "任意。1～2,000,000（グラム）。"));
        rows.add(row(cols.salesChannel(), "任意。32文字以内。"));
        return rows;
    }

    private static List<Object> row(String item, String description) {
        return List.of(item, description);
    }
}
