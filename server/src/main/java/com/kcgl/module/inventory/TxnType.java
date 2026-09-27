package com.kcgl.module.inventory;

/**
 * 流水类型（stock_ledger.txn_type，docs/01 5.3/7.2 唯一定义）。
 * 只增不改不删；业务账号 DB 级仅 SELECT+INSERT。
 */
public enum TxnType {

    CREATE(1),
    ARRIVAL(2),
    SELL(3),
    SCRAP(4),
    TRANSFER(5),
    RETURN(6),
    STOCKTAKE_ADJUST(7),
    VOID(8),
    LIST_UP(9),
    SOLD_MARK(10),
    CANCEL_MARK(11),
    ADJUST(12),
    RECYCLE_DELETE(13),
    RECYCLE_RESTORE(14);

    private final int id;

    TxnType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
