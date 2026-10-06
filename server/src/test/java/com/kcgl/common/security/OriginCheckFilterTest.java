package com.kcgl.common.security;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OriginCheckFilter 单元测试（B2）：把「白名单留空」的语义钉死成契约。
 *
 * <p>原 javadoc 称留空时该层「退化为仅 SameSite=Strict 防线」，实际是**全拒**——空集里
 * 当然不含任何来源，于是任何带非空 Origin 的非幂等请求都 403。同源部署下浏览器登录与
 * 全部写操作必带 Origin，故留空 = 这些请求全废。行为本身是对的（fail-closed），错的是
 * 描述；此处把两种取值的取舍都锁住，免得后来者照旧文档「修」成退化。
 *
 * <p>含 Origin 的白名单命中/未命中两支由 {@code AuthIntegrationTest#csrf_originNotAllowlisted_403}
 * 经真实过滤链覆盖，此处只补单元级的三支：留空全拒、留空放行无 Origin、安全方法不拦。
 */
class OriginCheckFilterTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ORIGIN = "https://kcgl.example.com";

    private static MockHttpServletResponse run(Set<String> allowed, String method, String origin)
            throws Exception {
        OriginCheckFilter filter = new OriginCheckFilter(allowed, MAPPER);
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/items");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void emptyAllowlist_rejectsAnyRequestCarryingAnOrigin() throws Exception {
        MockHttpServletResponse response = run(Set.of(), "POST", ORIGIN);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("403002");
    }

    @Test
    void emptyAllowlist_stillPassesRequestsWithoutOrigin() throws Exception {
        // 非浏览器客户端（脚本 / 拨测 / 反向代理）不带 Origin：本层不拦，认证与授权照常把关
        assertThat(run(Set.of(), "POST", null).getStatus()).isEqualTo(200);
        assertThat(run(Set.of(), "POST", "  ").getStatus()).isEqualTo(200);
    }

    @Test
    void safeMethods_areNeverChecked() throws Exception {
        assertThat(run(Set.of(), "GET", ORIGIN).getStatus()).isEqualTo(200);
        assertThat(run(Set.of("https://other.example"), "HEAD", ORIGIN).getStatus()).isEqualTo(200);
    }
}
