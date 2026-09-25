package com.proxyviewer;

import com.proxyviewer.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Security 表单登录 / 登出 / CSRF Token / 401 JSON 的行为验证。
 *
 * <p>这里走的是<b>真实的认证链路</b>（POST /login 提交用户名口令，凭会话继续访问），
 * 不用 {@code user()} 绕过，以便覆盖 UserDetailsService + PasswordEncoder 的真实校验。</p>
 */
@IntegrationTest
class SecurityFlowTest {

    @Autowired
    private MockMvc mockMvc;

    /** 用真实表单登录，返回携带登录态的会话 */
    private MockHttpSession loginSuccessfully() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "tester")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void loginPageIsPublicAndRendersForm() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户名")))
                .andExpect(content().string(containsString("口令")))
                // 表单登录同样需要 CSRF Token（Thymeleaf 自动注入隐藏域）
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("action=\"/login\"")));
    }

    @Test
    void unauthenticatedPageRedirectsToLoginPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void wrongPasswordShowsErrorAndDoesNotGrantAccess() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "tester")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户名或口令不正确")));
    }

    @Test
    void correctPasswordGrantsAccessToPagesAndApi() throws Exception {
        MockHttpSession session = loginSuccessfully();

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("退出登录")))
                .andExpect(content().string(containsString("tester")));

        mockMvc.perform(get("/api/test/status").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"running\"")));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = loginSuccessfully();

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        // 登出后（新会话）访问任何页面都回到登录页
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRejected() throws Exception {
        MockHttpSession session = loginSuccessfully();

        mockMvc.perform(post("/api/purge-failed").session(session)
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/purge-failed").session(session)
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }

    @Test
    void apiWithoutLoginGetsJson401() throws Exception {
        mockMvc.perform(get("/api/test/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("未登录")));
    }

    /** 页面同时暴露表单隐藏域与 _csrf meta（fetch 用），且请求头名是 X-CSRF-TOKEN */
    @Test
    void pagesExposeCsrfTokenForFormsAndFetch() throws Exception {
        String html = mockMvc.perform(get("/").with(user("tester")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("name=\"_csrf\"")          // 表单隐藏域（刷新订阅 / 退出登录）
                .contains("meta name=\"_csrf\"")     // fetch 请求头用
                .contains("X-CSRF-TOKEN");           // 请求头名
    }

    /** 迁移后 HTTP Basic 不再生效：带 Basic 头也只会被带到登录页 */
    @Test
    void basicAuthHeaderIsNoLongerAccepted() throws Exception {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString("tester:secret".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/").header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
