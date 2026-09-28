package com.kcgl.module.yahoo;

import java.time.LocalDateTime;

/**
 * 合并策略纯函数（docs/01 7.4 唯一定义，状态与价格两条独立规则）：
 * - 状态单调只前进：成交(2)为终态禁回退（陈旧 CSV 不会把成交刷回在售/取消）；
 *   取消(3)→在售(1)=重新出品例外（新拍卖通常换 ID，此边防御同名 ID 数据异常）
 * - 价格 recency：事件时间（GREATEST(closed_at, listed_at)）新者胜；
 *   同刻=后批胜（批次序 tie-break）；无时间戳视为最旧
 */
public final class YahooMergePolicy {

    private YahooMergePolicy() {
    }

    public static int clampStatus(int current, int incoming) {
        if (current == incoming || current == 2) {
            return current;
        }
        return incoming;
    }

    public static boolean incomingIsNewer(LocalDateTime current, LocalDateTime incoming) {
        if (incoming == null) {
            return false;
        }
        return current == null || !incoming.isBefore(current);
    }

    /** 事件时间＝GREATEST(closed_at, listed_at)，价格 recency 判据。 */
    public static LocalDateTime eventTime(LocalDateTime listedAt, LocalDateTime closedAt) {
        if (listedAt == null) {
            return closedAt;
        }
        if (closedAt == null) {
            return listedAt;
        }
        return listedAt.isAfter(closedAt) ? listedAt : closedAt;
    }
}
