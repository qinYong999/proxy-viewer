package com.proxyviewer.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * 认证与 CSRF 防护配置（Spring Security）。
 *
 * <p>登录方式为<b>表单登录</b>（{@code /login}），会话由容器 Cookie 保存；
 * 状态变更请求必须携带 Spring Security 的 CSRF Token（表单走隐藏域，页面里的
 * {@code fetch} 走 {@code X-CSRF-TOKEN} 请求头，见模板中的 {@code _csrf} meta 标签）。</p>
 *
 * <p>配置键沿用 {@code app.security.*}（{@link AppProperties.Security}）：
 * 口令留空时启动随机生成一个并打印到日志，避免"默认弱口令"。
 * {@code app.security.enabled=false} 时整体放行并关闭 CSRF 校验，
 * 供本机调试脚本直接调用（不建议对外使用）。</p>
 *
 * <p>{@code /api/**} 由页面里的 JS 轮询/调用，未登录或被拒绝时返回 JSON（401/403），
 * 而不是把登录页 HTML 当成响应体塞给 {@code resp.json()}；其余请求跳转登录页。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    static final String LOGIN_PAGE = "/login";

    private final boolean authEnabled;
    private final String username;
    private final String password;

    public SecurityConfig(AppProperties props) {
        AppProperties.Security sec = props.getSecurity();
        this.authEnabled = sec.isEnabled();
        this.username = sec.getUsername() == null || sec.getUsername().isBlank() ? "admin" : sec.getUsername().trim();

        String rawPassword = sec.getPassword();
        if (authEnabled && (rawPassword == null || rawPassword.isBlank())) {
            rawPassword = randomPassword();
            log.warn("========================================================");
            log.warn("  未配置访问口令，已随机生成（仅本次启动有效）");
            log.warn("  用户名: {}", username);
            log.warn("  口  令: {}", rawPassword);
            log.warn("  如需固定口令，请设置环境变量 APP_PASSWORD");
            log.warn("  如需彻底关闭认证（不建议），请设置 APP_AUTH_ENABLED=false");
            log.warn("========================================================");
        }
        this.password = rawPassword == null ? "" : rawPassword;

        if (!authEnabled) {
            log.warn("⚠ 访问认证已被禁用（app.security.enabled=false），任何能访问端口的人都可以读取/删除节点");
        }
    }

    /** 口令用委托编码器存储（当前为 bcrypt），配置里写明文口令即可 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 单用户：与配置一致的内存用户；将来需要多用户时替换为数据库 UserDetailsService 即可 */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails user = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (!authEnabled) {
            // 认证关闭：完全放行，且不再要求 CSRF Token（否则本机脚本无法 POST）
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            http.csrf(csrf -> csrf.disable());
            return http.build();
        }

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(LOGIN_PAGE, "/error").permitAll()
                .anyRequest().authenticated());

        http.formLogin(form -> form
                .loginPage(LOGIN_PAGE)
                .loginProcessingUrl(LOGIN_PAGE)
                .defaultSuccessUrl("/", false)
                .failureUrl(LOGIN_PAGE + "?error")
                .permitAll());

        http.logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl(LOGIN_PAGE + "?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"));

        // CSRF Token：默认实现（HttpSession 仓储 + BREACH 掩码），表单隐藏域与
        // X-CSRF-TOKEN 请求头都取模板里 ${_csrf.token} 的值即可，无需额外配置
        http.csrf(Customizer.withDefaults());

        http.exceptionHandling(handling -> handling
                .authenticationEntryPoint(new ApiAwareAuthenticationEntryPoint(LOGIN_PAGE))
                .accessDeniedHandler(new ApiAwareAccessDeniedHandler()));

        return http.build();
    }

    /** 未登录：页面跳登录页，{@code /api/**} 与 JSON 请求返回 401 JSON */
    static final class ApiAwareAuthenticationEntryPoint extends LoginUrlAuthenticationEntryPoint {

        ApiAwareAuthenticationEntryPoint(String loginFormUrl) {
            super(loginFormUrl);
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             org.springframework.security.core.AuthenticationException authException)
                throws IOException, ServletException {
            if (expectsJson(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"未登录或会话已过期\"}");
                return;
            }
            super.commence(request, response, authException);
        }
    }

    /** 已登录但被拒绝（如 CSRF Token 缺失/失效）：页面交给容器错误页，API 返回 403 JSON */
    static final class ApiAwareAccessDeniedHandler implements AccessDeniedHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           org.springframework.security.access.AccessDeniedException accessDeniedException)
                throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            if (expectsJson(request)) {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请求被拒绝（缺少或无效的 CSRF Token）\"}");
                return;
            }
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("403 请求被拒绝\n");
        }
    }

    private static boolean expectsJson(HttpServletRequest request) {
        if (request.getRequestURI() != null && request.getRequestURI().startsWith("/api/")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase().contains("application/json");
    }

    private static String randomPassword() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
