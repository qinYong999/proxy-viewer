package com.proxyviewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 启动完成后打印系统访问地址。
 *
 * <p>监听 {@link ApplicationReadyEvent}（此时内嵌 Web 服务器已就绪，端口已确定），
 * 因此即使配置了 {@code server.port=0}（随机端口）也能打印出真实地址。</p>
 *
 * <p>整个打印过程被 try/catch 包裹，任何异常都不会影响应用启动。</p>
 */
@Component
public class StartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);
    private static final String LINE = "========================================================";

    @Value("${spring.application.name:proxy-subscription-viewer}")
    private String appName;

    @Value("${server.address:}")
    private String serverAddress;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.ssl.key-store:}")
    private String sslKeyStore;

    @Value("${app.security.enabled:true}")
    private boolean authEnabled;

    @Value("${app.security.username:admin}")
    private String authUsername;

    @Value("${app.security.password:}")
    private String authPassword;

    @Value("${app.subscription.default-url:}")
    private String defaultSubscriptionUrl;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (!(event.getApplicationContext() instanceof WebServerApplicationContext webContext)) {
                return; // 非 Web 运行环境（例如 MockMvc 测试）不打印
            }
            WebServer webServer = webContext.getWebServer();
            if (webServer == null || webServer.getPort() <= 0) {
                return; // Mock 环境或端口未确定
            }
            logStartupInfo(webServer.getPort());
        } catch (Exception e) {
            log.debug("打印启动访问地址失败（不影响运行）: {}", e.getMessage());
        }
    }

    private void logStartupInfo(int port) {
        String scheme = isHttps() ? "https" : "http";
        boolean allInterfaces = isAllInterfaces(serverAddress);
        String host = formatHost(serverAddress, allInterfaces);
        String path = normalizeContextPath(contextPath);

        log.info(LINE);
        log.info("  {} 启动完成", appName);
        log.info("  系统访问地址: {}", buildAccessUrl(scheme, host, port, path));
        if (allInterfaces) {
            for (String ip : localIpv4Addresses()) {
                log.info("  局域网访问地址: {}", buildAccessUrl(scheme, ip, port, path));
            }
        }
        if (authEnabled) {
            log.info("  访问认证: 已开启（表单登录 /login，用户名 {}）", authUsername);
            if (authPassword == null || authPassword.isBlank()) {
                log.info("  访问口令: 未配置，请使用上方启动日志中随机生成的口令（或用 APP_PASSWORD 注入）");
            }
        } else {
            log.warn("  访问认证: 已关闭（任何能访问该端口的人都可以读取/删除节点）");
        }
        if (defaultSubscriptionUrl == null || defaultSubscriptionUrl.isBlank()) {
            log.info("  订阅链接: 未配置（请在页面填写，或用 SUBSCRIPTION_URL 注入）");
        } else {
            // 只打印主机名：订阅链接的路径本身就是凭据，不写进日志
            log.info("  订阅链接: 已配置（{}）", subscriptionHost(defaultSubscriptionUrl));
        }
        log.info(LINE);
    }

    private boolean isHttps() {
        return sslEnabled || (sslKeyStore != null && !sslKeyStore.isBlank());
    }

    // ======================== 纯函数（便于单测） ========================

    /** 未指定监听地址、或监听通配地址时视为"监听所有网卡" */
    static boolean isAllInterfaces(String address) {
        if (address == null) {
            return true;
        }
        String trimmed = address.trim();
        return trimmed.isEmpty() || "0.0.0.0".equals(trimmed) || "::".equals(trimmed) || "[::]".equals(trimmed);
    }

    /** 生成可点击地址中的主机部分：通配地址回退为 localhost，IPv6 补方括号 */
    static String formatHost(String address, boolean allInterfaces) {
        if (allInterfaces || address == null || address.isBlank()) {
            return "localhost";
        }
        String host = address.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            return host;
        }
        return host.contains(":") ? "[" + host + "]" : host;
    }

    /** context-path 规范化：空→空串，否则确保以 / 开头且不以 / 结尾 */
    static String normalizeContextPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String buildAccessUrl(String scheme, String host, int port, String contextPath) {
        return scheme + "://" + host + ":" + port + normalizeContextPath(contextPath);
    }

    /** 提取订阅链接的主机名用于日志提示；链接里的路径含凭据，绝不打印 */
    static String subscriptionHost(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return (host == null || host.isBlank()) ? "已配置" : host;
        } catch (Exception e) {
            return "已配置";
        }
    }

    /** 列出本机可用于局域网访问的 IPv4 地址（失败时返回空列表） */
    static List<String> localIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("枚举本机地址失败: {}", e.getMessage());
        }
        return addresses;
    }
}
