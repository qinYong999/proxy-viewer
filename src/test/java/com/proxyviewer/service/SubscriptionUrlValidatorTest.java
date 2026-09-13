package com.proxyviewer.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionUrlValidatorTest {

    @Test
    void acceptsPublicHttpAndHttps() {
        URI uri = SubscriptionUrlValidator.validate("https://93.184.216.34/sub/abc", false);
        assertThat(uri.getHost()).isEqualTo("93.184.216.34");
    }

    @Test
    void rejectsNonHttpSchemesAndUserInfo() {
        assertThatThrownBy(() -> SubscriptionUrlValidator.validate("file:///etc/passwd", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionUrlValidator.validate("ftp://example.com/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionUrlValidator.validate("http://user:pass@example.com/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionUrlValidator.validate("   ", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionUrlValidator.validate(null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPrivateAndLoopbackLiterals() {
        for (String url : new String[]{
                "http://127.0.0.1:8080/sub",
                "http://10.1.2.3/sub",
                "http://172.16.5.5/sub",
                "http://192.168.1.1/sub",
                "http://169.254.169.254/latest/meta-data/",
                "http://100.64.0.1/sub",
                "http://0.0.0.0/sub",
                "http://240.0.0.1/sub",
                "http://[::1]/sub",
                "http://[fe80::1]/sub",
                "http://[fc00::1]/sub"}) {
            assertThatThrownBy(() -> SubscriptionUrlValidator.validate(url, false))
                    .as("应拒绝 %s", url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allowsPrivateHostsWhenExplicitlyConfigured() {
        URI uri = SubscriptionUrlValidator.validate("http://127.0.0.1:8080/sub", true);
        assertThat(uri.getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    void blockedAddressClassificationCoversReservedRanges() throws Exception {
        String[] blocked = {"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.1.1",
                "100.64.0.1", "198.18.0.1", "192.0.0.1", "0.0.0.0", "255.255.255.255",
                "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1"};
        for (String ip : blocked) {
            assertThat(SubscriptionUrlValidator.isBlockedAddress(InetAddress.getByName(ip)))
                    .as("%s 应被判为内部地址", ip).isTrue();
        }

        String[] allowed = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111"};
        for (String ip : allowed) {
            assertThat(SubscriptionUrlValidator.isBlockedAddress(InetAddress.getByName(ip)))
                    .as("%s 应被判为公网地址", ip).isFalse();
        }
    }

    /**
     * 本项目的使用前提就是"本地 DNS 被污染"，因此本地解析失败时必须放行，
     * 由代理侧 / DoH 侧完成真正的解析（否则默认订阅链接会被自己拦掉）。
     */
    @Test
    void allowsHostWhenLocalDnsCannotResolveIt() {
        URI uri = SubscriptionUrlValidator.validate("https://no-such-host.invalid/sub", false);
        assertThat(uri.getHost()).isEqualTo("no-such-host.invalid");
    }
}
