package com.kcgl.module.checklist;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.module.dict.PriceBandEntity;
import com.kcgl.module.dict.PriceBandMapper;
import com.kcgl.module.dict.VenueEntity;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.item.ItemMapper;
import com.kcgl.module.setting.SettingService;
import com.kcgl.module.user.SysUserMapper;
import org.springframework.stereotype.Service;

/**
 * 首启 checklist 聚合（docs/01 4.3）：五步全真前首页对管理员置顶引导。
 * 计数口径刻意宽松：hasItem=任意商品曾登记（含作废/回收站——「试录一件」的目的是
 * 验证取号链路可用，非库存口径）；hasStaffUser=除初始管理员外至少一人。
 */
@Service
public class ChecklistService {

    static final String PRINT_DONE_KEY = "checklist.print_done";
    static final String PRINT_DONE_VALUE = "1";

    private final SysUserMapper userMapper;
    private final VenueMapper venueMapper;
    private final PriceBandMapper priceBandMapper;
    private final ItemMapper itemMapper;
    private final SettingService settingService;

    public ChecklistService(SysUserMapper userMapper,
                            VenueMapper venueMapper,
                            PriceBandMapper priceBandMapper,
                            ItemMapper itemMapper,
                            SettingService settingService) {
        this.userMapper = userMapper;
        this.venueMapper = venueMapper;
        this.priceBandMapper = priceBandMapper;
        this.itemMapper = itemMapper;
        this.settingService = settingService;
    }

    public ChecklistResponse get() {
        return new ChecklistResponse(
                userMapper.selectCount(null) > 1,
                venueMapper.selectCount(new LambdaQueryWrapper<VenueEntity>()
                        .eq(VenueEntity::getEnabled, 1)) > 0,
                priceBandMapper.selectCount(new LambdaQueryWrapper<PriceBandEntity>()
                        .eq(PriceBandEntity::getEnabled, 1)) > 0,
                itemMapper.selectCount(null) > 0,
                PRINT_DONE_VALUE.equals(settingService.findValue(PRINT_DONE_KEY).orElse(null)));
    }

    /** 试打标签完成标记（幂等 upsert，重复调用返回同一状态）。 */
    public ChecklistResponse markPrintDone(Long userId) {
        settingService.putValue(PRINT_DONE_KEY, PRINT_DONE_VALUE, userId);
        return get();
    }
}
