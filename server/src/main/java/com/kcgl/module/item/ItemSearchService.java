package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.util.CodeNormalizer;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.VenueEntity;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.image.FirstThumbReader;
import com.kcgl.module.item.dto.ItemSearchResponse;
import com.kcgl.module.itemcode.ItemCodeFormatter;
import com.kcgl.module.setting.SettingService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 全局搜索（GET /api/items/search，D-061 独立端点——打印契约零改动）。
 *
 * kw 优先级链（D-062）：
 * ① NFKC+大写后整串命中管理号正则 → uk 精确快路径（探测不加 voided/deleted
 *    过滤=号身份语义，探测落空回落模糊——部分码如 HTK9-A1 亦命中正则因频段组可选）；
 * ② yyyy-M-d / yyyy/M/d 日期双格式 → buy_date 等值（LocalDate.parse 不支持单数位
 *    月日，正则捕获后 LocalDate.of 构造）；
 * ③ 模糊：会场名预解析（venue_id IN）与 7 列 LIKE OR 组合；%/_/\ 字面化
 *    （ESCAPE 子句——「50%」不当通配符）；假名宽松匹配（ア/ぁ 命中 あ——平/片
 *    假名与小仮名同权重）是 utf8mb4_0900_ai_ci 的既知仕様=契约而非意外，集成测试锁定。
 *
 * 滞销（D-065）：行徽标 Java 派生与 warnLevel SQL 谓词共用同一边界计算
 * （warehouse_in_date <= 今天−N 天）；阈值 sys_setting 可覆写、脏值防御回退 30/90。
 * 排除作废/软删件（死件走回收站端点）；V 可见成本利润（A19）。
 */
@Service
public class ItemSearchService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_WARN_DAYS = 30;
    private static final int DEFAULT_ALARM_DAYS = 90;
    private static final String WARN_DAYS_KEY = "slow_move.warn_days";
    private static final String ALARM_DAYS_KEY = "slow_move.alarm_days";
    private static final String[] FUZZY_COLUMNS = {
            "item_code", "remark", "shelf_no", "group_no", "item_name", "category", "author_kiln"};
    private static final Pattern DATE_KW = Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$");

    private final ItemMapper itemMapper;
    private final VenueMapper venueMapper;
    private final FirstThumbReader firstThumbReader;
    private final SettingService settingService;
    private final Clock clock;

    public ItemSearchService(ItemMapper itemMapper, VenueMapper venueMapper,
            FirstThumbReader firstThumbReader, SettingService settingService, Clock clock) {
        this.itemMapper = itemMapper;
        this.venueMapper = venueMapper;
        this.firstThumbReader = firstThumbReader;
        this.settingService = settingService;
        this.clock = clock;
    }

    public ItemSearchResponse search(String kw, Integer warehouse, Integer stockStatus,
            Integer saleStatus, Long venueId, LocalDate buyDateFrom, LocalDate buyDateTo,
            Integer warnLevel, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LocalDate today = LocalDate.now(clock);
        SlowMoveThresholds thresholds = thresholds();

        LambdaQueryWrapper<ItemEntity> wrapper = new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getVoided, 0)
                .eq(ItemEntity::getDeleted, 0);
        if (warehouse != null) {
            wrapper.eq(ItemEntity::getWarehouse, warehouse);
        }
        if (stockStatus != null) {
            wrapper.eq(ItemEntity::getStockStatus, stockStatus);
        }
        if (saleStatus != null) {
            wrapper.eq(ItemEntity::getSaleStatus, saleStatus);
        }
        if (venueId != null) {
            wrapper.eq(ItemEntity::getVenueId, venueId);
        }
        if (buyDateFrom != null) {
            wrapper.ge(ItemEntity::getBuyDate, buyDateFrom);
        }
        if (buyDateTo != null) {
            wrapper.le(ItemEntity::getBuyDate, buyDateTo);
        }
        if (kw != null && !kw.isBlank()) {
            applyKw(wrapper, kw.trim());
        }
        if (warnLevel != null) {
            if (warnLevel != 1 && warnLevel != 2) {
                throw new BizException(ErrorCode.VALIDATION, "warnLevel は 1 または 2 を指定してください");
            }
            applyWarnLevel(wrapper, warnLevel, thresholds, today);
        }
        wrapper.orderByDesc(ItemEntity::getId);

        Page<ItemEntity> result = itemMapper.selectPage(Page.of(safePage, safeSize), wrapper);
        List<ItemEntity> items = result.getRecords();
        Map<Long, String> thumbs = firstThumbReader.byItemIds(
                items.stream().map(ItemEntity::getId).toList());
        Map<Long, String> venueNames = venueNames(items);

        List<ItemSearchResponse.Row> rows = items.stream()
                .map(item -> new ItemSearchResponse.Row(
                        item.getId(),
                        item.getItemCode(),
                        thumbs.get(item.getId()),
                        item.getItemName(),
                        venueNames.get(item.getVenueId()),
                        item.getBuyDate(),
                        item.getPurchasePrice(),
                        item.getTotalCost(),
                        item.getProfit(),
                        item.getWarehouse(),
                        item.getStockStatus(),
                        item.getSaleStatus(),
                        item.getSoldPrice(),
                        item.getShelfNo(),
                        item.getWarehouseInDate(),
                        slowMoveLevel(item, thresholds, today)))
                .toList();
        return new ItemSearchResponse(result.getTotal(), safePage, safeSize, rows);
    }

    private void applyKw(LambdaQueryWrapper<ItemEntity> wrapper, String kw) {
        String normalized = CodeNormalizer.normalize(kw);
        if (ItemCodeFormatter.matches(normalized)) {
            ItemEntity exact = itemMapper.selectOne(new LambdaQueryWrapper<ItemEntity>()
                    .eq(ItemEntity::getItemCode, normalized));
            if (exact != null) {
                wrapper.eq(ItemEntity::getItemCode, normalized);
                return;
            }
        }
        LocalDate dateKw = parseDateKw(kw);
        if (dateKw != null) {
            wrapper.eq(ItemEntity::getBuyDate, dateKw);
            return;
        }
        applyFuzzyKw(wrapper, kw);
    }

    private void applyFuzzyKw(LambdaQueryWrapper<ItemEntity> wrapper, String kw) {
        String like = "%" + escapeLike(kw) + "%";
        List<Long> venueIds = venueMapper.selectList(new LambdaQueryWrapper<VenueEntity>()
                        .apply("name LIKE {0} ESCAPE '\\\\'", like)).stream()
                .map(VenueEntity::getId)
                .toList();
        wrapper.and(w -> {
            if (!venueIds.isEmpty()) {
                w.in(ItemEntity::getVenueId, venueIds);
            }
            boolean started = !venueIds.isEmpty();
            for (String column : FUZZY_COLUMNS) {
                if (started) {
                    w.or();
                }
                w.apply(column + " LIKE {0} ESCAPE '\\\\'", like);
                started = true;
            }
        });
    }

    private String escapeLike(String kw) {
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private LocalDate parseDateKw(String kw) {
        Matcher m = DATE_KW.matcher(kw);
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private void applyWarnLevel(LambdaQueryWrapper<ItemEntity> wrapper, int warnLevel,
            SlowMoveThresholds thresholds, LocalDate today) {
        int days = warnLevel == 2 ? thresholds.alarmDays() : thresholds.warnDays();
        wrapper.eq(ItemEntity::getStockStatus, 1)
                .ne(ItemEntity::getSaleStatus, 2)
                .isNotNull(ItemEntity::getWarehouseInDate)
                .le(ItemEntity::getWarehouseInDate, today.minusDays(days));
    }

    private int slowMoveLevel(ItemEntity item, SlowMoveThresholds thresholds, LocalDate today) {
        boolean inStock = Objects.equals(item.getStockStatus(), 1);
        boolean sold = Objects.equals(item.getSaleStatus(), 2);
        if (!inStock || sold || item.getWarehouseInDate() == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(item.getWarehouseInDate(), today);
        if (days >= thresholds.alarmDays()) {
            return 2;
        }
        if (days >= thresholds.warnDays()) {
            return 1;
        }
        return 0;
    }

    private SlowMoveThresholds thresholds() {
        return new SlowMoveThresholds(
                settingDays(WARN_DAYS_KEY, DEFAULT_WARN_DAYS),
                settingDays(ALARM_DAYS_KEY, DEFAULT_ALARM_DAYS));
    }

    private int settingDays(String key, int fallback) {
        return settingService.findValue(key)
                .map(String::trim)
                .flatMap(this::parsePositiveDays)
                .orElse(fallback);
    }

    private Optional<Integer> parsePositiveDays(String value) {
        try {
            int days = Integer.parseInt(value);
            return days > 0 ? Optional.of(days) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Map<Long, String> venueNames(List<ItemEntity> items) {
        List<Long> venueIds = items.stream()
                .map(ItemEntity::getVenueId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        return venueMapper.selectBatchIds(venueIds).stream()
                .collect(Collectors.toMap(VenueEntity::getId, VenueEntity::getName));
    }

    private record SlowMoveThresholds(int warnDays, int alarmDays) {
    }
}
