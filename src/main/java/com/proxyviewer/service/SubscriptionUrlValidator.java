package com.proxyviewer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 订阅地址校验 —— 防 SSRF。
 *
 * <p>历史实现里 {@code /refresh?url=} 会把任意 URL 交给服务端发起请求，可被用来探测内网。
 * 这里在发起请求前、以及跟随重定向后都要校验：</p>
 * <ul>
 *   <li>只允许 http / https，禁止携带用户信息的 URL</li>
 *   <li>禁止解析到回环 / 私有 / 链路本地 / 组播 / 保留地址（除非显式配置允许）</li>
 *   <li>本机 DNS 解析失败时放行 —— 本项目的使用场景正是"本地 DNS 被污染"，
 *       真正的域名解析发生在 SOCKS/HTTP 代理或 DoH 侧，此时无法在本地判定</li>
 * </ul>
 */
public final class SubscriptionUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionUrlValidator.class);

    private SubscriptionUrlValidator() {
    }

    /**
     * 校验并规范化订阅地址。
     *
     * @throws IllegalArgumentException 校验不通过（消息可直接展示给用户）
     */
    public static URI validate(String rawUrl, boolean allowPrivateHosts) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("订阅链接不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("订阅链接格式非法: " + e.getMessage());
        }
        validateUri(uri, allowPrivateHosts);
        return uri;
    }

    /** 校验一个已经解析出来的 URI（用于重定向后的最终地址） */
    public static void validateUri(URI uri, boolean allowPrivateHosts) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("只允许 http/https 订阅链接，收到: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("订阅链接不允许携带用户名/口令");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("订阅链接缺少主机名");
        }
        // URI.getHost() 对 IPv6 字面量会带上方括号，去掉后才能交给 InetAddress
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (allowPrivateHosts) {
            return;
        }
        if (isBlockedLiteral(host)) {
            throw new IllegalArgumentException("订阅链接指向内网/保留地址，已拒绝: " + host
                    + "（确需访问请设置 app.subscription.allow-private-hosts=true）");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // 本地 DNS 不可用/被污染时无法判定：放行，由代理侧或 DoH 侧解析
            log.warn("本地无法解析订阅域名 {}（{}），跳过内网校验，交由代理/DoH 解析", host, e.getMessage());
            return;
        }
        for (InetAddress address : resolved) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("订阅域名 " + host + " 解析到内网/保留地址 "
                        + address.getHostAddress() + "，已拒绝（防 SSRF）");
            }
        }
    }

    /** 字面量地址（IP 形式的 host）快速判断；非 IP 返回 false */
    static boolean isBlockedLiteral(String host) {
        try {
            return isBlockedAddress(InetAddress.getByName(host));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0) return true;                       // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // 100.64/10 运营商 NAT
            if (b0 == 192 && b1 == 0 && (bytes[2] & 0xFF) == 0) return true; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true; // 198.18/15 基准测试
            return b0 >= 240;                               // 240.0.0.0/4 保留
        }
        if (bytes.length == 16) {
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) 需还原为 IPv4 再判断
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { mapped = false; break; }
            }
            if (mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                try {
                    return isBlockedAddress(InetAddress.getByAddress(
                            new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
            int b0 = bytes[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true;           // fc00::/7 唯一本地地址
            if (b0 == 0x20 && (bytes[1] & 0xFF) == 0x01 && (bytes[2] & 0xFF) == 0x0D
                    && (bytes[3] & 0xFF) == 0xB8) return true; // 2001:db8::/32 文档地址
            if (b0 == 0x01 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0) return true; // 100::/64 丢弃前缀
        }
        return false;
    }
}
