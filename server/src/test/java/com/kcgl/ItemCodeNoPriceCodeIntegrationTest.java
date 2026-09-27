package com.kcgl;

import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.itemcode.CreateItemCommand;
import com.kcgl.module.itemcode.ItemCodeService;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.user.SysUserEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * includePriceCode=false 口径（A1，yml 属性切换）：管理号不含档位字母，档位快照仍落
 * price_band_code 列。独立上下文（属性不同），单用例最小化容器成本。
 */
@SpringBootTest(properties = {
        "kcgl.security.allowed-origins=https://kcgl.example.com",
        "kcgl.item-code.include-price-code=false"
})
@Testcontainers
class ItemCodeNoPriceCodeIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    @Autowired
    ItemCodeService itemCodeService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long venueId;
    long operatorId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2026,'K')");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        operatorId = 1L;
        runAs();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_codeExcludesBandLetter_bandSnapshotStillPersisted() {
        ItemEntity first = itemCodeService.create(command("npc-1"));
        ItemEntity second = itemCodeService.create(command("npc-2"));
        assertThat(first.getItemCode()).isEqualTo("HTK9-A1");
        assertThat(second.getItemCode()).isEqualTo("HTK9-A2");
        // 档位照常推导（供内部展示与导出），仅不进号
        assertThat(first.getPriceBandCode()).isEqualTo("X");
    }

    private CreateItemCommand command(String clientReqId) {
        return new CreateItemCommand(clientReqId, null, venueId, LocalDate.of(2026, 9, 15), null,
                1000L, null, null, null, 1, null, null, null, null,
                null, null, null, null, null, null, operatorId, "早瀬");
    }

    /** 引擎无条件写 ITEM_CREATE 审计（读 SecurityContext），直接调服务层须显式装填。 */
    private static void runAs() {
        SysUserEntity user = new SysUserEntity();
        user.setId(1L);
        user.setUsername("hase");
        user.setPasswordHash("x");
        user.setDisplayName("早瀬");
        user.setRole(2);
        user.setEnabled(1);
        user.setMustChangePwd(0);
        user.setLocale("ja-JP");
        KcglUserDetails details = KcglUserDetails.of(user, Clock.systemDefaultZone());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
