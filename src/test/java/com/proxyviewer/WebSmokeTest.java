package com.proxyviewer;

import com.proxyviewer.model.ProxyNodeRepository;
import com.proxyviewer.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端冒烟测试：完整 Spring 上下文 + H2，验证认证过滤器、同源过滤器、
 * 控制器以及三个 Thymeleaf 模板都能正常工作。
 */
@IntegrationTest
class WebSmokeTest {

    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("tester:secret".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProxyNodeRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void rejectsRequestsWithoutCredentials() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> {
                    String header = result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);
                    if (header == null || !header.startsWith("Basic")) {
                        throw new AssertionError("缺少 WWW-Authenticate: Basic 响应头");
                    }
                });
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        String bad = "Basic " + Base64.getEncoder()
                .encodeToString("tester:wrong".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/").header(HttpHeaders.AUTHORIZATION, bad))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rendersIndexTemplateWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/").header(HttpHeaders.AUTHORIZATION, AUTH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("代理订阅节点信息")))
                .andExpect(content().string(containsString("暂无节点数据")))
                .andExpect(content().string(containsString("刷新订阅是状态变更操作")))
                .andExpect(content().string(containsString("清理失败节点")));
    }

    @Test
    void rendersLogsAndTestLogsTemplates() throws Exception {
        mockMvc.perform(get("/logs").header(HttpHeaders.AUTHORIZATION, AUTH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("操作日志")));

        mockMvc.perform(get("/test-logs").header(HttpHeaders.AUTHORIZATION, AUTH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("节点真实可用性测试")))
                .andExpect(content().string(containsString("手动测试")))
                .andExpect(content().string(containsString("测试失败只标记")));
    }

    @Test
    void rejectsCrossSiteStateChangingRequest() throws Exception {
        mockMvc.perform(post("/api/purge-failed")
                        .header(HttpHeaders.AUTHORIZATION, AUTH)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsSameOriginStateChangingRequest() throws Exception {
        mockMvc.perform(post("/api/purge-failed")
                        .header(HttpHeaders.AUTHORIZATION, AUTH)
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }

    @Test
    void refreshRejectsPrivateSubscriptionUrl() throws Exception {
        mockMvc.perform(post("/refresh")
                        .header(HttpHeaders.AUTHORIZATION, AUTH)
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .param("url", "http://169.254.169.254/latest/meta-data/"))
                .andExpect(status().is3xxRedirection());
        // 内网地址被拒后不应写入任何节点
        assertThat(repository.count()).isZero();
    }

    /** 仓库不再内置默认订阅链接：未配置且页面未填写时应直接报错，而不是去联网抓取 */
    @Test
    void refreshWithoutAnyConfiguredUrlIsRejected() throws Exception {
        mockMvc.perform(post("/refresh")
                        .header(HttpHeaders.AUTHORIZATION, AUTH)
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().is3xxRedirection());
        assertThat(repository.count()).isZero();
    }

    /** 未配置订阅链接时，首页应提示去填写，而不是给出一个会失败的刷新入口 */
    @Test
    void indexHintsToFillSubscriptionUrlWhenUnset() throws Exception {
        mockMvc.perform(get("/").header(HttpHeaders.AUTHORIZATION, AUTH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请先在顶部填写订阅链接")))
                .andExpect(content().string(containsString("SUBSCRIPTION_URL")));
    }

    @Test
    void refreshIsNotReachableByGet() throws Exception {
        mockMvc.perform(get("/refresh").header(HttpHeaders.AUTHORIZATION, AUTH))
                .andExpect(status().isMethodNotAllowed());
    }
}
