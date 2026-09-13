package com.proxyviewer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 启动地址拼装的纯函数测试（不需要启动 Spring 上下文） */
class StartupBannerTest {

    @Test
    void treatsBlankAndWildcardAddressAsAllInterfaces() {
        assertThat(StartupBanner.isAllInterfaces(null)).isTrue();
        assertThat(StartupBanner.isAllInterfaces("")).isTrue();
        assertThat(StartupBanner.isAllInterfaces("  ")).isTrue();
        assertThat(StartupBanner.isAllInterfaces("0.0.0.0")).isTrue();
        assertThat(StartupBanner.isAllInterfaces("::")).isTrue();

        assertThat(StartupBanner.isAllInterfaces("127.0.0.1")).isFalse();
        assertThat(StartupBanner.isAllInterfaces("192.168.1.10")).isFalse();
    }

    @Test
    void formatsHostForUrl() {
        assertThat(StartupBanner.formatHost("127.0.0.1", false)).isEqualTo("127.0.0.1");
        assertThat(StartupBanner.formatHost("192.168.1.10", false)).isEqualTo("192.168.1.10");
        // 通配地址回退 localhost，否则地址不可点击
        assertThat(StartupBanner.formatHost("0.0.0.0", true)).isEqualTo("localhost");
        assertThat(StartupBanner.formatHost("", true)).isEqualTo("localhost");
        // IPv6 需要方括号才能拼进 URL
        assertThat(StartupBanner.formatHost("::1", false)).isEqualTo("[::1]");
        assertThat(StartupBanner.formatHost("[::1]", false)).isEqualTo("[::1]");
    }

    @Test
    void normalizesContextPath() {
        assertThat(StartupBanner.normalizeContextPath(null)).isEmpty();
        assertThat(StartupBanner.normalizeContextPath("")).isEmpty();
        assertThat(StartupBanner.normalizeContextPath("/")).isEmpty();
        assertThat(StartupBanner.normalizeContextPath("app")).isEqualTo("/app");
        assertThat(StartupBanner.normalizeContextPath("/app")).isEqualTo("/app");
        assertThat(StartupBanner.normalizeContextPath("/app/")).isEqualTo("/app");
        assertThat(StartupBanner.normalizeContextPath(" /app/ ")).isEqualTo("/app");
    }

    @Test
    void buildsAccessUrl() {
        assertThat(StartupBanner.buildAccessUrl("http", "127.0.0.1", 8080, ""))
                .isEqualTo("http://127.0.0.1:8080");
        assertThat(StartupBanner.buildAccessUrl("http", "127.0.0.1", 8080, "/app/"))
                .isEqualTo("http://127.0.0.1:8080/app");
        assertThat(StartupBanner.buildAccessUrl("https", "[::1]", 8443, "/proxy"))
                .isEqualTo("https://[::1]:8443/proxy");
    }

    @Test
    void subscriptionHostHidesTheSecretPath() {
        assertThat(StartupBanner.subscriptionHost("https://example.com/sub/secret-token"))
                .isEqualTo("example.com");
        assertThat(StartupBanner.subscriptionHost("  https://example.com:8443/a/b?token=x  "))
                .isEqualTo("example.com");
        // 解析不出来时也不能把原文写进日志
        assertThat(StartupBanner.subscriptionHost("not a url")).isEqualTo("已配置");
        assertThat(StartupBanner.subscriptionHost("")).isEqualTo("已配置");
    }
}
