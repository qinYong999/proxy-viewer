package com.proxyviewer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * HTTP Basic 认证过滤器。
 *
 * <p>本应用能读到订阅里的全部节点（含 UUID，等同代理凭据），因此默认必须带认证。
 * 未引入 Spring Security，用最小的过滤器实现，避免额外依赖。</p>
 *
 * <p>口令来源：{@code app.security.password}（推荐用环境变量 {@code APP_PASSWORD} 注入）。
 * 若开启认证但口令为空，则启动时随机生成一个并打印到日志，避免"默认弱口令"。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);
    private static final String REALM = "proxy-viewer";
    private static final String CHARSET = "UTF-8";

    private final boolean enabled;
    private final String username;
    private final byte[] expectedCredential;

    public BasicAuthFilter(AppProperties props) {
        AppProperties.Security sec = props.getSecurity();
        this.enabled = sec.isEnabled();
        this.username = sec.getUsername() == null || sec.getUsername().isBlank() ? "admin" : sec.getUsername().trim();

        String password = sec.getPassword();
        if (enabled && (password == null || password.isBlank())) {
            password = randomPassword();
            log.warn("========================================================");
            log.warn("  未配置访问口令，已随机生成（仅本次启动有效）");
            log.warn("  用户名: {}", username);
            log.warn("  口  令: {}", password);
            log.warn("  如需固定口令，请设置环境变量 APP_PASSWORD");
            log.warn("  如需彻底关闭认证（不建议），请设置 APP_AUTH_ENABLED=false");
            log.warn("========================================================");
        }
        this.expectedCredential = (username + ":" + (password == null ? "" : password))
                .getBytes(StandardCharsets.UTF_8);

        if (!enabled) {
            log.warn("⚠ 访问认证已被禁用（app.security.enabled=false），任何能访问端口的人都可以读取/删除节点");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || isAuthenticated(request)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"" + CHARSET + "\"");
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("401 需要认证：请输入用户名与口令\n");
    }

    private boolean isAuthenticated(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // 定长比较，避免时序侧信道
        return MessageDigest.isEqual(expectedCredential, decoded.getBytes(StandardCharsets.UTF_8));
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
