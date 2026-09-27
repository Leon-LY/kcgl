package com.kcgl.module.itemcode;

/** 预览号（无锁只读）：预览≠保留，最终以保存响应为准（docs/01 7.1）。 */
public record ItemCodePreviewResponse(String code, String bandCode, String seqPrefix, int seqNo) {
}
