package com.proxyviewer.service.test;

import com.proxyviewer.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探测工具方法测试：SOCKS5 UDP 帧编解码与 UDP 探测目标解析。
 *
 * <p>这些是 UDP 可用性测试的正确性基础——帧格式错一位就会把可用节点判成不可用。</p>
 */
class NodeTesterTest {

    private final NodeTester tester = new NodeTester(new AppProperties());

    // ======================== SOCKS5 UDP 帧 ========================

    @Test
    void wrapsIpv4TargetIntoUdpFrame() {
        byte[] payload = {0x1B, 0x00, 0x01};
        ByteBuffer frame = NodeTester.buildUdpFrame("1.2.3.4", 123, payload);
        byte[] raw = new byte[frame.remaining()];
        frame.get(raw);

        // RSV(2) + FRAG(1) + ATYP(1) + IPv4(4) + PORT(2) + payload
        assertThat(raw).hasSize(3 + 1 + 4 + 2 + payload.length);
        assertThat(raw[0]).isEqualTo((byte) 0x00);
        assertThat(raw[1]).isEqualTo((byte) 0x00);
        assertThat(raw[2]).isEqualTo((byte) 0x00);   // FRAG 必须为 0
        assertThat(raw[3]).isEqualTo((byte) 0x01);   // ATYP = IPv4
        assertThat(new byte[]{raw[4], raw[5], raw[6], raw[7]})
                .containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        assertThat(raw[8]).isEqualTo((byte) 0x00);   // 端口高位
        assertThat(raw[9]).isEqualTo((byte) 123);    // 端口低位
        assertThat(raw[10]).isEqualTo((byte) 0x1B);
    }

    @Test
    void resolvableDomainIsSentAsIpToAvoidProxySideLookup() {
        ByteBuffer frame = NodeTester.buildUdpFrame("pool.ntp.org", 123, new byte[]{0x1B});
        byte[] raw = new byte[frame.remaining()];
        frame.get(raw);

        // 本地能解析时就发 IP，省掉代理侧一次 DNS
        assertThat(raw[3]).isEqualTo((byte) 0x01);
    }

    @Test
    void unresolvableHostFallsBackToDomainAtyp() {
        String host = "no-such-host.invalid";
        ByteBuffer frame = NodeTester.buildUdpFrame(host, 123, new byte[]{0x1B});
        byte[] raw = new byte[frame.remaining()];
        frame.get(raw);

        assertThat(raw[3]).isEqualTo((byte) 0x03);   // ATYP = 域名
        assertThat(raw[4] & 0xFF).isEqualTo(host.length());
        assertThat(new String(raw, 5, host.length(), StandardCharsets.UTF_8)).isEqualTo(host);
        assertThat(raw[5 + host.length()]).isEqualTo((byte) 0x00);              // 端口高位
        assertThat(raw[6 + host.length()]).isEqualTo((byte) 123);               // 端口低位
        assertThat(raw[7 + host.length()]).isEqualTo((byte) 0x1B);              // 载荷
    }

    @Test
    void stripsUdpHeaderForIpv4Response() {
        // RSV RSV FRAG ATYP=1 + 1.2.3.4 + port 123 + 载荷 "hello"
        byte[] frame = new byte[]{0, 0, 0, 1, 1, 2, 3, 4, 0, 123, 'h', 'e', 'l', 'l', 'o'};
        byte[] payload = NodeTester.stripUdpHeader(frame, frame.length);
        assertThat(payload).isNotNull();
        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void stripsUdpHeaderForDomainResponse() {
        byte[] host = "ntp.example.com".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[3 + 1 + 1 + host.length + 2 + 2];
        int i = 0;
        frame[i++] = 0;
        frame[i++] = 0;
        frame[i++] = 0;
        frame[i++] = 3;                  // ATYP = 域名
        frame[i++] = (byte) host.length;
        System.arraycopy(host, 0, frame, i, host.length);
        i += host.length;
        frame[i++] = 0;
        frame[i++] = 123;
        frame[i++] = 0x1B;
        frame[i] = 0x11;

        byte[] payload = NodeTester.stripUdpHeader(frame, frame.length);
        assertThat(payload).containsExactly((byte) 0x1B, (byte) 0x11);
    }

    @Test
    void stripRejectsMalformedFrames() {
        assertThat(NodeTester.stripUdpHeader(new byte[]{0, 0, 0}, 3)).isNull();
        // ATYP 非法
        assertThat(NodeTester.stripUdpHeader(new byte[]{0, 0, 0, 9, 1, 2}, 6)).isNull();
        // 头部完整但无载荷
        assertThat(NodeTester.stripUdpHeader(
                new byte[]{0, 0, 0, 1, 1, 2, 3, 4, 0, 123}, 10)).isNull();
    }

    // ======================== NTP 报文 ========================

    @Test
    void buildsStandard48ByteNtpRequest() {
        byte[] req = NodeTester.buildNtpRequest();
        assertThat(req).hasSize(48);
        // LI=0, VN=3, Mode=3 → 0b00_011_011 = 0x1B
        assertThat(req[0]).isEqualTo((byte) 0x1B);
        for (int i = 1; i < req.length; i++) {
            assertThat(req[i]).isZero();
        }
    }

    @Test
    void validatesOnlyRealNtpServerResponses() {
        byte[] serverReply = new byte[48];
        serverReply[0] = 0x24;   // LI=0, VN=4, Mode=4（服务端）
        assertThat(NodeTester.isValidNtpResponse(serverReply)).isTrue();

        // 过短：不是合法 NTP 报文
        assertThat(NodeTester.isValidNtpResponse(new byte[10])).isFalse();
        // Mode=3（客户端）：说明收到的不是服务端应答
        byte[] clientMode = new byte[48];
        clientMode[0] = 0x23;
        assertThat(NodeTester.isValidNtpResponse(clientMode)).isFalse();
        assertThat(NodeTester.isValidNtpResponse(null)).isFalse();
    }

    // ======================== 目标解析 ========================

    @Test
    void parsesUdpTestTargets() {
        assertThat(NodeTester.parseUdpTarget("ntp:pool.ntp.org"))
                .containsExactly("pool.ntp.org", "123", "ntp");
        assertThat(NodeTester.parseUdpTarget("ntp:time.google.com:1234"))
                .containsExactly("time.google.com", "1234", "ntp");
        assertThat(NodeTester.parseUdpTarget("dns:1.1.1.1"))
                .containsExactly("1.1.1.1", "53", "dns");
        // 未知类型与空值都回落到 ntp，避免配置写错就整批失败
        assertThat(NodeTester.parseUdpTarget("weird:host.example"))
                .containsExactly("host.example", "123", "ntp");
        assertThat(NodeTester.parseUdpTarget(""))
                .containsExactly("pool.ntp.org", "123", "ntp");
        assertThat(NodeTester.parseUdpTarget(null))
                .containsExactly("pool.ntp.org", "123", "ntp");
    }

    @Test
    void parsesDnsQueryPacket() {
        byte[] q = NodeTester.buildDnsQuery();
        // 头部 12 字节 + QNAME(7+"example"+3+"com"+0) + QTYPE/QCLASS
        assertThat(q).hasSize(12 + 1 + 7 + 1 + 3 + 1 + 4);
        assertThat(q[5]).isEqualTo((byte) 0x01);   // QDCOUNT = 1
    }

    // ======================== 路径归一化 ========================

    @Test
    void normalizesPathsForProbing() {
        assertThat(NodeTester.normalizePath(null)).isEqualTo("/");
        assertThat(NodeTester.normalizePath("")).isEqualTo("/");
        assertThat(NodeTester.normalizePath("/a/b")).isEqualTo("/a/b");
        assertThat(NodeTester.normalizePath("a/b")).isEqualTo("/a/b");
        assertThat(NodeTester.normalizePath("%2Fa%2Fb")).isEqualTo("/a/b");
    }

    @Test
    void classifiesCommonFailuresIntoReadableReasons() {
        assertThat(NodeTester.classify(new java.net.ConnectException("Connection refused")))
                .isEqualTo("REFUSED");
        assertThat(NodeTester.classify(new java.net.UnknownHostException("unknown host x")))
                .isEqualTo("DNS_ERR");
        assertThat(NodeTester.classify(new java.net.SocketTimeoutException("Read timed out")))
                .isEqualTo("TIMEOUT");
        assertThat(NodeTester.classify(new java.net.SocketException("Connection reset")))
                .isEqualTo("RESET");
    }
}
