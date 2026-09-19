package com.proxyviewer.service.test;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 单节点的真实能力探测：真实延迟、下载测速、UDP 可用性。
 *
 * <p>所有探测都<b>经由节点的本地 SOCKS 入站</b>发起，因此测到的是"这个节点真的能不能用"，
 * 而不是"它服务器端口开没开"。通信层用 {@link Socks5HttpClient} 而不是 JDK 的
 * {@code HttpClient}——后者会静默忽略 SOCKS 代理，导致测试退化成直连测速。</p>
 *
 * <p>每项探测自己负责内核的起停（由调用方 {@code NodeTestService} 编排）。</p>
 */
@Service
public class NodeTester {

    private static final Logger log = LoggerFactory.getLogger(NodeTester.class);

    /** 未测得的哨兵值，与页面显示约定一致 */
    public static final long NOT_MEASURED = -1L;

    private final AppProperties props;

    public NodeTester(AppProperties props) {
        this.props = props;
    }

    /** 单项探测结果 */
    public record ProbeResult(boolean ok, long value, String reason) {
        static ProbeResult ok(long value) {
            return new ProbeResult(true, value, null);
        }

        static ProbeResult fail(String reason) {
            return new ProbeResult(false, NOT_MEASURED, reason);
        }
    }

    // ======================== 真实延迟 ========================

    /**
     * 真实延迟：经节点代理访问 {@code app.test.latency-test-url}，
     * 连测 {@code latency-attempts} 次取最小值（v2rayN 为 2 次取最小）。
     */
    public ProbeResult measureLatency(ProxyNode node, int socksPort) {
        AppProperties.Test cfg = props.getTest();
        URI target = URI.create(cfg.getLatencyTestUrl());

        long best = NOT_MEASURED;
        String lastReason = null;
        int attempts = Math.max(1, cfg.getLatencyAttempts());
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                sleepQuietly(cfg.getLatencyAttemptIntervalMs());
            }
            long t0 = System.nanoTime();
            try {
                Socks5HttpClient.Response response = Socks5HttpClient.get(
                        socksPort, target, cfg.getLatencyTimeoutMs(), cfg.getLatencyTimeoutMs(), 64 * 1024);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                // 要求 204/200：节点通了但被墙返回 403/502 不能算"可用"
                if (response.status() == 204 || response.status() == 200) {
                    if (best == NOT_MEASURED || ms < best) {
                        best = ms;
                    }
                } else {
                    lastReason = "HTTP_" + response.status();
                }
            } catch (Exception e) {
                lastReason = classify(e);
            }
        }
        if (best == NOT_MEASURED) {
            return ProbeResult.fail(lastReason == null ? "NO_RESPONSE" : lastReason);
        }
        return ProbeResult.ok(best);
    }

    // ======================== 下载测速 ========================

    /**
     * 下载测速：经节点代理限时下载 {@code app.test.speed-test-url}。
     *
     * @return 速率，单位 Kbps（Kbps = 字节数 × 8 ÷ 毫秒数）
     */
    public ProbeResult measureSpeed(ProxyNode node, int socksPort) {
        AppProperties.Test cfg = props.getTest();
        URI target = URI.create(cfg.getSpeedTestUrl());
        int readTimeout = Math.max(cfg.getSpeedTestTimeoutMs(), cfg.getSpeedTestDurationMs() + 5000);

        try {
            long t0 = System.nanoTime();
            long deadline = t0 + cfg.getSpeedTestDurationMs() * 1_000_000L;
            long total = Socks5HttpClient.streamBody(socksPort, target,
                    cfg.getSpeedTestTimeoutMs(), readTimeout, deadline, false);
            long elapsedMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
            if (total <= 0) {
                return ProbeResult.fail("NO_DATA");
            }
            long kbps = total * 8 / elapsedMs;
            log.debug("节点 #{} 测速: {} 字节 / {}ms = {} Kbps", node == null ? 0 : node.getId(),
                    total, elapsedMs, kbps);
            return ProbeResult.ok(kbps);
        } catch (Exception e) {
            return ProbeResult.fail(classify(e));
        }
    }

    // ======================== UDP 可用性 ========================

    /**
     * UDP 可用性：经节点的 SOCKS5 UDP ASSOCIATE 隧道发 NTP 请求。
     *
     * <p>能识别"TCP 可用但 UDP 被阻断"的节点——纯 TCP 测试完全测不出来。
     * 失败只记录原因，不污染延迟字段。</p>
     */
    public ProbeResult measureUdp(int socksPort) {
        AppProperties.Test cfg = props.getTest();
        String[] target = parseUdpTarget(cfg.getUdpTestTarget());
        String host = target[0];
        int port = Integer.parseInt(target[1]);
        boolean ntp = "ntp".equals(target[2]);
        int timeout = cfg.getUdpTestTimeoutMs();

        long best = Long.MAX_VALUE;
        String lastReason = null;
        java.net.Socket[] controlHolder = new java.net.Socket[1];
        DatagramChannel channel = null;
        try {
            InetSocketAddress relay = Socks5HttpClient.establishUdpAssociation(
                    socksPort, timeout, controlHolder);
            channel = DatagramChannel.open();
            channel.socket().setSoTimeout(timeout);
            String relayHost = relay.getHostString();
            if (relayHost == null || relayHost.isBlank() || "0.0.0.0".equals(relayHost)) {
                relayHost = "127.0.0.1";
            }
            channel.connect(new InetSocketAddress(relayHost, relay.getPort()));

            byte[] payload = ntp ? buildNtpRequest() : buildDnsQuery();
            ByteBuffer frame = buildUdpFrame(host, port, payload);
            // 与 v2rayN 一致：最多 2 次，取最小 RTT；校验响应而不是"收到即成功"
            for (int attempt = 0; attempt < 2 && best == Long.MAX_VALUE; attempt++) {
                long t0 = System.nanoTime();
                channel.write(frame.duplicate());
                ByteBuffer resp = ByteBuffer.allocate(1500);
                int n = channel.read(resp);
                if (n <= 0) {
                    lastReason = "UDP_NO_RESPONSE";
                    continue;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                byte[] data = stripUdpHeader(resp.array(), n);
                if (data == null) {
                    lastReason = "UDP_BAD_FRAME";
                    continue;
                }
                if (ntp && !isValidNtpResponse(data)) {
                    lastReason = "NTP_VERIFY_FAILED";
                    continue;
                }
                best = ms;
            }
        } catch (Exception e) {
            lastReason = classify(e);
        } finally {
            closeQuietly(channel);
            closeQuietly(controlHolder[0]);
        }
        if (best == Long.MAX_VALUE) {
            return ProbeResult.fail(lastReason == null ? "UDP_UNKNOWN" : lastReason);
        }
        return ProbeResult.ok(best);
    }

    /** 组装 SOCKS5 UDP 数据报：RSV(2) + FRAG(1) + 地址 + 载荷 */
    static ByteBuffer buildUdpFrame(String host, int port, byte[] payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(0x00);
        buffer.write(0x00);
        buffer.write(0x00);   // FRAG 必须为 0（不支持分片）
        Socks5HttpClient.writeAddress(buffer, host);
        buffer.write((port >> 8) & 0xFF);
        buffer.write(port & 0xFF);
        buffer.write(payload, 0, payload.length);
        return ByteBuffer.wrap(buffer.toByteArray());
    }

    /** 去掉 SOCKS5 UDP 响应头，返回载荷；头部非法返回 null */
    static byte[] stripUdpHeader(byte[] data, int length) {
        if (length < 4) {
            return null;
        }
        int offset = 3;                       // RSV(2) + FRAG(1)
        int atyp = data[offset] & 0xFF;
        offset++;
        switch (atyp) {
            case 0x01 -> offset += 4;
            case 0x03 -> {
                if (offset >= length) {
                    return null;
                }
                offset += 1 + (data[offset] & 0xFF);
            }
            case 0x04 -> offset += 16;
            default -> {
                return null;
            }
        }
        offset += 2;                          // PORT
        if (offset >= length) {
            return null;
        }
        byte[] payload = new byte[length - offset];
        System.arraycopy(data, offset, payload, 0, payload.length);
        return payload;
    }

    /** NTP 客户端请求：LI=0, VN=3, Mode=3，共 48 字节，其余为 0 */
    static byte[] buildNtpRequest() {
        byte[] packet = new byte[48];
        packet[0] = 0x1B;
        return packet;
    }

    /**
     * 校验 NTP 响应：长度足够且 Mode 字段为 4（服务端模式）。
     * 仅判断"收到回包"会把任意垃圾数据当成成功。
     */
    static boolean isValidNtpResponse(byte[] data) {
        return data != null && data.length >= 48 && (data[0] & 0x07) == 4;
    }

    /** DNS 查询兜底：A 记录查询 example.com */
    static byte[] buildDnsQuery() {
        return new byte[]{
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
                0x03, 'c', 'o', 'm', 0x00,
                0x00, 0x01, 0x00, 0x01};
    }

    /** 解析 {@code 类型:主机[:端口]}；未知类型与空值回落到 ntp */
    static String[] parseUdpTarget(String target) {
        String t = (target == null || target.isBlank()) ? "ntp:pool.ntp.org" : target.trim();
        String kind = "ntp";
        int colon = t.indexOf(':');
        if (colon > 0) {
            kind = t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            t = t.substring(colon + 1).trim();
        }
        String host = t;
        int port = "dns".equals(kind) ? 53 : 123;
        int lastColon = t.lastIndexOf(':');
        if (lastColon > 0 && lastColon < t.length() - 1) {
            try {
                port = Integer.parseInt(t.substring(lastColon + 1).trim());
                host = t.substring(0, lastColon).trim();
            } catch (NumberFormatException ignored) {
                // 冒号后不是端口，保持原样
            }
        }
        if (!"dns".equals(kind)) {
            kind = "ntp";
        }
        return new String[]{host, String.valueOf(port), kind};
    }

    /** 把节点 path 归一化成可直接请求的路径 */
    static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        try {
            String p = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            return p.startsWith("/") ? p : "/" + p;
        } catch (Exception e) {
            return "/";
        }
    }

    /** 把异常翻译成页面可读的短原因 */
    public static String classify(Exception e) {
        if (e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.http.HttpTimeoutException) {
            return "TIMEOUT";
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return "TIMEOUT";
        }
        if (msg.contains("refused")) {
            return "REFUSED";
        }
        if (msg.contains("unknown host") || msg.contains("nodename")) {
            return "DNS_ERR";
        }
        if (msg.contains("reset") || msg.contains("eof") || msg.contains("broken pipe")
                || msg.contains("closed") || msg.contains("关闭")) {
            return "RESET";
        }
        if (msg.contains("certificate") || msg.contains("cert")) {
            return "TLS_CERT";
        }
        if (msg.contains("handshake") || msg.contains("ssl")) {
            return "TLS_HANDSHAKE";
        }
        return e.getClass().getSimpleName();
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}
