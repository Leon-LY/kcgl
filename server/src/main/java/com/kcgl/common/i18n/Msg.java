package com.kcgl.common.i18n;

import java.util.Map;

/**
 * 面向使用者的提示文案（语言无关的三件套）：{@code code}=i18n 消息键，
 * {@code params}=插值参数，{@code text}=日文兜底原文。
 *
 * <p>与 {@link com.kcgl.common.web.ErrorCode}（码+日文，前端 {@code errors.<code>}
 * 覆盖）同构，差别在于这里的文案带**可变参数**（字段名、原值、上限…），
 * 固定文案表达不了，故必须把参数一并留存。
 *
 * <p>三者并存而不是只留 code：落库字段（导入报告 note/error_rows、系统告警 message）
 * 同时被旧数据行、服务端日志、诊断导出消费——改成纯键会让这些读者拿到一串裸键。
 * 前端按当前语言渲染，{@code code} 缺失或键未覆盖三语时回退 {@code text}，
 * 于是老数据自然老化、新数据即时跟随语言（web/src/utils/errors.ts renderMessage）。
 */
public record Msg(String code, Map<String, Object> params, String text) {

    public Msg {
        params = params == null ? Map.of() : params;
    }

    /** 无插值参数的提示。 */
    public static Msg of(String code, String text) {
        return new Msg(code, Map.of(), text);
    }

    public static Msg of(String code, Map<String, Object> params, String text) {
        return new Msg(code, params, text);
    }
}
