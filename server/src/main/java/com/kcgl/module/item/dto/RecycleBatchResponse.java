package com.kcgl.module.item.dto;

import java.util.List;

/**
 * 批量软删 / 批量恢复的结果：逐件成败。
 *
 * <p>为什么逐件报告而不是整批回滚：批内各件之间**没有共同不变量**——每件各落一行流水、
 * 一条 operation_log、走一次自己的版本守卫，跨件的原子性没有业务含义。而列表页勾选后，
 * 「选中期间他人已删/已改」的陈旧行几乎必然出现；整批回滚会让一行陈旧数据废掉整次操作，
 * 用户只能在一屏里逐行去猜是哪一行。故按件独立事务、逐件报告，由前端汇总成
 * 「N 件成功、M 件失败及原因」。
 *
 * <p>失败只收业务拒绝（BizException）；DB 故障等非业务异常照常上抛，不伪装成「批内一件失败」。
 */
public record RecycleBatchResponse(int succeeded, List<Failure> failures) {

    /** 单件失败：itemId + 错误码。前端按 {@code errors.<code>} 三语渲染，不传后端散文。 */
    public record Failure(long itemId, int code) {
    }
}
