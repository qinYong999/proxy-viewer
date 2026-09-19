package com.proxyviewer.service.test;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内核日志提取测试。
 *
 * <p>内核启动失败时会把版本横幅一并打印出来；若整段塞进页面，
 * 用户看到的是一堆版本信息而看不到真正的原因。</p>
 */
class CoreErrorExtractionTest {

    private static final String REAL_FAILURE_LOG = """
            Xray 26.9.9 (Xray, Penetrates Everything.) 52a412d (go1.27.1 windows/amd64)
            A unified platform for anti-censorship.
            2026/09/19 20:25:42.140459 [Info] infra/conf/serial: Reading config: &{Name:pv-test-1.json Format:json}
            2026/09/19 20:25:42.150000 [Warning] common/errors: The feature WebSocket transport is deprecated.
            2026/09/19 20:25:42.160000 [Error] infra/conf: failed to parse port: invalid port 0333
            """;

    @Test
    void extractsErrorLineInsteadOfVersionBanner() {
        String extracted = XrayCoreService.extractCoreError(REAL_FAILURE_LOG);
        assertThat(extracted).contains("invalid port 0333");
        // 不应把版本横幅当成原因
        assertThat(extracted).doesNotContain("Penetrates Everything");
        assertThat(extracted).doesNotContain("A unified platform");
    }

    @Test
    void stripsTimestampAndLevelMarkers() {
        String extracted = XrayCoreService.extractCoreError(REAL_FAILURE_LOG);
        assertThat(extracted).doesNotContain("2026/09/19");
        assertThat(extracted).doesNotContain("[Error]");
        assertThat(extracted).startsWith("infra/conf:");
    }

    @Test
    void skipsWarningsAndFindsFailure() {
        String log = """
                Xray 1.0 (test)
                2026/01/01 00:00:00 [Warning] something deprecated
                2026/01/01 00:00:01 [Warning] another deprecation
                2026/01/01 00:00:02 Failed to start: address already in use
                """;
        assertThat(XrayCoreService.extractCoreError(log))
                .isEqualTo("Failed to start: address already in use");
    }

    @Test
    void fallsBackToFirstMeaningfulLineWhenNoErrorMarker() {
        String log = """
                Xray 26.9.9 (Xray, Penetrates Everything.)
                A unified platform for anti-censorship.
                panic: runtime error
                """;
        // panic 命中错误特征
        assertThat(XrayCoreService.extractCoreError(log)).contains("panic");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(XrayCoreService.extractCoreError(null)).isEmpty();
        assertThat(XrayCoreService.extractCoreError("")).isEmpty();
        assertThat(XrayCoreService.extractCoreError("   ")).isEmpty();
    }

    @Test
    void bannerOnlyOutputFallsBackToSomethingRatherThanNothing() {
        // 没有可识别的错误行时，宁可返回原文也不要丢失诊断信息
        String log = "Proxyman: failed to listen\n";
        assertThat(XrayCoreService.extractCoreError(log))
                .isEqualTo("Proxyman: failed to listen");
    }

    @Test
    void versionBannerLinesAreNotMistakenForTheReason() {
        // 只输出横幅时没有可用的诊断信息，返回空串，由上层显示"(内核无输出)"
        String extracted = XrayCoreService.extractCoreError(
                "Xray 26.9.9 (Xray, Penetrates Everything.)\nA unified platform for anti-censorship.");
        assertThat(extracted).isEmpty();
    }
}
