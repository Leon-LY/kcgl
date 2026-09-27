package com.kcgl.module.itemcode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理号格式配置（A1 口径：价格码是否进编号）。
 * yml 部署级属性而非 admin 开关——对外业务格式变更须与甲方确认后随发版固化（docs/04 D-031）。
 */
@ConfigurationProperties(prefix = "kcgl.item-code")
public record ItemCodeProperties(Boolean includePriceCode) {

    public ItemCodeProperties {
        if (includePriceCode == null) {
            includePriceCode = Boolean.TRUE;
        }
    }

    public boolean withPriceCode() {
        return includePriceCode;
    }
}
