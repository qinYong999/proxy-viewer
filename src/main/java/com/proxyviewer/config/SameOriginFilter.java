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
import java.net.URI;
import java.util.Set;

/**
 * 同源校验过滤器（CSRF 防护）。
 *
 * <p>Basic 认证凭据由浏览器按"目标源"自动附带，跨站表单同样会带上，
 * 因此仅靠认证挡不住 CSRF。这里对非安全方法（POST/PUT/PATCH/DELETE）
 * 校验 {@code Origin} / {@code Referer} / {@code Sec-Fetch-Site}：</p>
 * <ul>
 *   <li>浏览器明确声明跨站（Sec-Fetch-Site: cross-site）→ 拒绝</li>
 *   <li>Origin/Referer 存在但与 Host 不一致 → 拒绝</li>
 *   <li>两者都不存在（curl / 脚本调用）→ 放行</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SameOriginFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SameOriginFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            reject(request, response, "Sec-Fetch-Site: cross-site");
            return;
        }

        String source = request.getHeader("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) {
            source = request.getHeader("Referer");
        }
        if (source != null && !source.isBlank() && !"null".equals(source)) {
            // HTTP/1.1 一定有 Host；缺失时退回容器解析出的 server 名与端口
            String hostHeader = request.getHeader("Host");
            if (hostHeader == null || hostHeader.isBlank()) {
                hostHeader = request.getServerName() + ":" + request.getServerPort();
            }
            if (!isSameHost(source, hostHeader)) {
                reject(request, response, "Origin/Referer 与 Host 不一致: " + source);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static boolean isSameHost(String source, String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(source);
            String sourceHost = uri.getHost();
            if (sourceHost == null) {
                return false;
            }
            int sourcePort = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
            String hostOnly = hostHeader;
            int hostPort = -1;
            int colon = hostHeader.lastIndexOf(':');
            if (colon > 0 && hostHeader.indexOf(']') < colon) {
                hostOnly = hostHeader.substring(0, colon);
                try {
                    hostPort = Integer.parseInt(hostHeader.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    hostPort = -1;
                }
            }
            if (hostOnly.startsWith("[") && hostOnly.endsWith("]")) {
                hostOnly = hostOnly.substring(1, hostOnly.length() - 1);
            }
            if (hostPort <= 0) {
                hostPort = defaultPort("https".equalsIgnoreCase(uri.getScheme()) ? "https" : "http");
            }
            return hostOnly.equalsIgnoreCase(sourceHost) && sourcePort == hostPort;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        log.warn("已拦截跨站请求: {} {} ({})", request.getMethod(), request.getRequestURI(), reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("403 已拒绝跨站请求\n");
    }
}
