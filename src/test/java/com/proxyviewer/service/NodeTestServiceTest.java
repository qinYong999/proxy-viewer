package com.proxyviewer.service;

import com.proxyviewer.model.ProxyNode;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试编排的纯逻辑单元测试（不联网、不启内核）。
 */
class NodeTestServiceTest {

    /**
     * 回归：{@code last_test_result} 列长 200，超长会让整批 saveAll 因
     * "Data too long" 整体回滚，导致本批次所有测试结果丢失。
     */
    @Test
    void failureReasonIsTruncatedToFitColumnWidth() {
        String huge = "X".repeat(5000);
        String shortened = NodeTestService.shortenReason(huge);
        assertThat(shortened).hasSize(180);
        // 加上 "FAILED:" 前缀后仍必须放得进 200 字符的列
        assertThat(("FAILED:" + shortened).length()).isLessThanOrEqualTo(200);
    }

    @Test
    void failureReasonCollapsesWhitespaceAndHandlesBlanks() {
        assertThat(NodeTestService.shortenReason("a\r\nb   c")).isEqualTo("a b c");
        assertThat(NodeTestService.shortenReason(null)).isEqualTo("UNKNOWN");
        assertThat(NodeTestService.shortenReason("   ")).isEqualTo("UNKNOWN");
        assertThat(NodeTestService.shortenReason("RESET")).isEqualTo("RESET");
    }

    @Test
    void shortenReasonKeepsCommonReasonsIntact() {
        for (String reason : new String[]{"TIMEOUT", "REFUSED", "RESET", "HTTP_403",
                "REALITY_MISSING_PUBLIC_KEY", "NO_CORE"}) {
            assertThat(NodeTestService.shortenReason(reason)).isEqualTo(reason);
        }
    }

    @Test
    void tcpingFailsForBlankServerOrBadPort() {
        ProxyNode blank = new ProxyNode();
        blank.setServer("  ");
        blank.setPort(443);
        assertThat(NodeTestService.tcping(blank, 1000)).isEqualTo(-1);

        ProxyNode badPort = new ProxyNode();
        badPort.setServer("127.0.0.1");
        badPort.setPort(0);
        assertThat(NodeTestService.tcping(badPort, 1000)).isEqualTo(-1);
    }

    @Test
    void tcpingFailsForUnresolvableHost() {
        ProxyNode node = new ProxyNode();
        node.setServer("no-such-host-anywhere.invalid");
        node.setPort(443);
        assertThat(NodeTestService.tcping(node, 1000)).isEqualTo(-1);
    }

    @Test
    void tcpingSucceedsAgainstAListeningLocalPort() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            ProxyNode node = new ProxyNode();
            node.setServer("127.0.0.1");
            node.setPort(server.getLocalPort());
            long ms = NodeTestService.tcping(node, 2000);
            assertThat(ms).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void tcpingTraversesAllResolvedAddressesInsteadOfOnlyTheFirst() throws Exception {
        // localhost 通常解析出 IPv4/IPv6 多个地址；只要有一个能连通就应返回成功，
        // 这正是相对 v2rayN（只取第一个 IP）的修正点
        assertThat(InetAddress.getAllByName("localhost")).isNotEmpty();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            ProxyNode node = new ProxyNode();
            node.setServer("localhost");
            node.setPort(server.getLocalPort());
            assertThat(NodeTestService.tcping(node, 2000)).isGreaterThanOrEqualTo(0);
        }
    }
}
