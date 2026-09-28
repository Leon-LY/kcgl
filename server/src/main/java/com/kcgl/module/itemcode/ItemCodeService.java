package com.kcgl.module.itemcode;

import com.kcgl.common.obs.AlertService;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.item.ItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 管理号引擎·外层（无 @Transactional）：重试在事务边界之外——每次经独立事务代理
 * {@link ItemCodeTxService} 全新进入（C1 审查修正）。重试信号仅限并发类异常
 * （uk 冲突/锁等待超时/死锁败者），业务校验异常原样上抛不消耗重试。
 * 重试耗尽=INTERNAL+sys_alert 落库（持久红点，非在线即逝的日志行）。
 */
@Service
public class ItemCodeService {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ItemCodeService.class);

    private final ItemCodeTxService txService;
    private final AlertService alertService;
    private final SseHub sseHub;

    public ItemCodeService(ItemCodeTxService txService, AlertService alertService, SseHub sseHub) {
        this.txService = txService;
        this.alertService = alertService;
        this.sseHub = sseHub;
    }

    public ItemEntity create(CreateItemCommand cmd) {
        ItemEntity item = createWithRetry(cmd);
        // 提交后广播（事务边界之外，同 InventoryActionService 模式）：他端的
        // 在途清单/本日会话按 ITEM 域失效重取。重放读回同样走到这里——
        // 多一次广播=他端多一次无害重取，不为省它给事务体加签名
        sseHub.broadcast(SyncEvent.TYPE_ITEM, item.getItemCode(), cmd.operatorId());
        return item;
    }

    /**
     * 生成但不再广播（Excel 批量生成模式专用，D-058 B）：2 万行逐件广播=事件风暴，
     * 由批次完成时的批次级单次广播（TYPE_EXCEL_IMPORT+TYPE_ITEM）替代；
     * 重试/幂等语义与 {@link #create} 完全一致。
     */
    public ItemEntity createQuietly(CreateItemCommand cmd) {
        return createWithRetry(cmd);
    }

    private ItemEntity createWithRetry(CreateItemCommand cmd) {
        for (int attempt = 1; ; attempt++) {
            try {
                return txService.allocateAndInsert(cmd);
            } catch (DuplicateKeyException | CannotAcquireLockException
                    | DeadlockLoserDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    String detail = "clientReqId=" + cmd.clientReqId()
                            + ", venueId=" + cmd.venueId() + ", buyDate=" + cmd.buyDate();
                    log.error("管理号生成重试耗尽 attempts={} {} {}", MAX_ATTEMPTS,
                            e.getClass().getSimpleName(), detail, e);
                    alertService.record("ITEM_CODE", AlertService.LEVEL_ERROR,
                            "管理号生成のリトライ回数が上限に達しました（" + MAX_ATTEMPTS + "回）",
                            "item-code-retry-exhausted",
                            "{\"cause\":\"" + e.getClass().getSimpleName() + "\","
                                    + "\"clientReqId\":\"" + cmd.clientReqId() + "\"}");
                    throw new BizException(ErrorCode.INTERNAL);
                }
                log.warn("管理号生成冲突重试 attempt={}/{} type={} clientReqId={}",
                        attempt, MAX_ATTEMPTS, e.getClass().getSimpleName(), cmd.clientReqId());
                backoff();
            }
        }
    }

    /** 微抖动退避：死锁双败者同时重进会再次相撞，随机化错开。 */
    private static void backoff() {
        try {
            Thread.sleep(20 + ThreadLocalRandom.current().nextInt(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL);
        }
    }
}
