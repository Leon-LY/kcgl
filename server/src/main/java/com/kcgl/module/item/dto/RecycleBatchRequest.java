package com.kcgl.module.item.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量软删 / 批量恢复请求（POST /api/items/recycle-delete、POST /api/items/recycle-restore）。
 *
 * <p>逐件携带 {@code clientReqId} 而非批次级单键，是被列宽逼出来的、也是更对的做法：
 * {@code stock_ledger.client_req_id} 是 CHAR(36) 且带 uk_client_req，键长卡死 36 字符，
 * 批次键无法拼接派生出各件子键（UUID 已占满）。改为前端逐件生成后，每件仍各自幂等读回、
 * 各自落流水、各自记审计——批量只是外壳，内核与单件端点完全一致。
 *
 * <p>{@code reason} 批次级共用（软删可选，同 {@link RecycleActionRequest}：软删是数据治理
 * 动作而非业务追责，场景=测试件清理/重复件合并）。
 */
public record RecycleBatchRequest(
        @NotEmpty @Size(max = RecycleBatchRequest.MAX_BATCH) List<@Valid Entry> items,
        @Size(max = 255) String reason) {

    /** 单批上限。与回收站列表页尺寸上限同档（{@code RecycleService.MAX_PAGE_SIZE}=100）： */
    /** 一屏能勾选的上限即一次批量的上限，超出说明调用方越过了 UI。 */
    public static final int MAX_BATCH = 100;

    /** 批内单件：目标 id + 该件自己的幂等键。 */
    public record Entry(@NotNull Long id, @NotBlank @Size(max = 36) String clientReqId) {
    }
}
