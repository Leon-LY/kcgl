package com.kcgl.common.i18n;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link Msg#params()} → 落库用的 JSON 文本（sys_alert.message_params、
 * stock_ledger.reason_params 等 JSON 列）。
 *
 * <p>无参消息存 NULL 而非 {@code "{}"}：空对象与「无结构化键」在列上应当可区分
 * （D-128 口径）——前者是「这条文案本来就没有参数」，后者是「这条文案没有结构化键，
 * 前端回退原文」。两者在前端的分支不同，混在一列里就分不出来了。
 */
public final class MsgJson {

    private MsgJson() {
    }

    /** 参数为空 → null；否则 Jackson 序列化。 */
    public static String paramsOf(Msg msg, ObjectMapper objectMapper) {
        return msg.params().isEmpty() ? null : objectMapper.writeValueAsString(msg.params());
    }
}
