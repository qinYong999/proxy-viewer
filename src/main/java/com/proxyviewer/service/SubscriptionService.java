package com.proxyviewer.service;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅服务：抓取订阅内容 → 解码 → 解析为 {@link ProxyNode} → 增量入库。
 *
 * <p>抓取仍然保留三级降级（本地代理 → 直连 → DoH + 裸 SSLSocket），因为本项目的使用
 * 场景就是"本地 DNS 被污染"。但做了三处修正：</p>
 * <ol>
 *   <li>所有传输通道统一返回 HTTP 原文，解码只做一次（原实现在裸 socket 分支里解码两次，
 *       该降级通道实际总是解析出 0 个节点）；</li>
 *   <li>明文订阅（非 base64）也能识别；</li>
 *   <li>读响应改为阻塞读到 EOF（服务端 Connection: close），不再"睡满 30 秒"。</li>
 * </ol>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** 单次订阅响应体上限，避免异常大响应打爆内存 */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final AppProperties props;
    private final NodeSyncService syncService;
    private final NodeParser parser = new NodeParser();
    private final HttpClient httpClient;

    public SubscriptionService(AppProperties props, NodeSyncService syncService) {
        this.props = props;
        this.syncService = syncService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getSubscription().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 刷新结果 */
    public record RefreshResult(int nodeCount, boolean skipped, NodeSyncService.SyncResult sync) {
    }

    /**
     * 抓取订阅并增量写入数据库。
     *
     * <p>抓取为空时按配置保留旧数据并标记 {@code skipped}，避免订阅临时失效把库清空。</p>
     */
    public RefreshResult refreshNodes(String subscriptionUrl) throws Exception {
        log.info("===== 开始刷新节点 =====");
        long t0 = System.currentTimeMillis();

        AppProperties.Subscription cfg = props.getSubscription();
        URI uri = SubscriptionUrlValidator.validate(subscriptionUrl, cfg.isAllowPrivateHosts());

        List<ProxyNode> nodes = fetchAndParse(uri);
        long t1 = System.currentTimeMillis();
        log.info("抓取+解析完成: {} 个节点, 耗时 {}ms", nodes.size(), t1 - t0);

        if (nodes.isEmpty()) {
            if (cfg.isKeepDataOnEmptyResult()) {
                log.warn("抓取结果为空，保留现有数据，不执行刷新");
                log.info("===== 刷新跳过 =====");
                return new RefreshResult(0, true, null);
            }
            log.warn("抓取结果为空，按配置清空节点");
        }

        NodeSyncService.SyncResult sync = syncService.applyIncremental(nodes);
        log.info("===== 刷新完成: 新增 {} / 更新 {} / 删除 {}，耗时 {}ms =====",
                sync.inserted(), sync.updated(), sync.deleted(), System.currentTimeMillis() - t0);
        return new RefreshResult(sync.total(), false, sync);
    }

    // ======================== 抓取 ========================

    private List<ProxyNode> fetchAndParse(URI uri) throws Exception {
        String body = fetchSubscription(uri);
        if (body == null || body.isBlank()) {
            log.warn("订阅数据为空");
            return List.of();
        }

        String decoded = normalizeBody(body);
        if (decoded == null || decoded.isBlank()) {
            log.warn("订阅内容既不是明文节点也不是可解码的 Base64");
            return List.of();
        }

        List<ProxyNode> nodes = new ArrayList<>();
        int vlessCount = 0;
        int vmessCount = 0;
        int failedCount = 0;
        for (String line : decoded.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("vless://") && !trimmed.startsWith("vmess://")) {
                continue;
            }
            ProxyNode node = parser.parse(trimmed);
            if (node == null) {
                failedCount++;
                continue;
            }
            node.setOriginalLink(trimmed);
            nodes.add(node);
            if ("vless".equals(node.getProtocol())) {
                vlessCount++;
            } else {
                vmessCount++;
            }
        }
        log.info("解析完成: {} 条 (VLESS={}, VMESS={}, 解析失败={})", nodes.size(), vlessCount, vmessCount, failedCount);
        return nodes;
    }

    /** 明文订阅直接使用；否则按 Base64 解码 */
    static String normalizeBody(String body) {
        String trimmed = body.trim();
        if (trimmed.contains("://")) {
            return trimmed;
        }
        return Base64Codec.decode(trimmed);
    }

    private String fetchSubscription(URI uri) throws Exception {
        Exception lastError = null;

        for (Transport transport : buildTransports()) {
            try {
                log.info("尝试通过 {} 获取订阅...", transport.description());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(props.getSubscription().getRequestTimeoutSeconds()))
                        .GET()
                        .build();
                HttpResponse<String> response = transport.client()
                        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                // 跟随重定向后要重新校验最终地址，防止用 302 绕过内网校验
                SubscriptionUrlValidator.validateUri(response.uri(),
                        props.getSubscription().isAllowPrivateHosts());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String body = response.body();
                    if (body != null && !body.isBlank()) {
                        log.info("通过 {} 成功获取订阅 (HTTP {})", transport.description(), response.statusCode());
                        return body.trim();
                    }
                    log.warn("{} 返回空内容", transport.description());
                } else {
                    log.warn("{} 返回 HTTP {}", transport.description(), response.statusCode());
                }
            } catch (Exception e) {
                lastError = e;
                log.warn("{} 失败: {}", transport.description(), e.getMessage());
            }
        }

        if (!props.getSubscription().isRawSocketFallbackEnabled()) {
            throw new RuntimeException("所有传输通道均失败: "
                    + (lastError == null ? "未知原因" : lastError.getMessage()));
        }

        log.info("所有 HttpClient 方式失败，尝试 DoH 解析 + 裸 SSLSocket 直连...");
        try {
            return fetchViaRawSocket(uri);
        } catch (Exception e) {
            throw new RuntimeException("直连也失败: " + e.getMessage()
                    + (lastError == null ? "" : " (原始错误: " + lastError.getMessage() + ")"));
        }
    }

    private record Transport(String description, HttpClient client) {
    }

    private List<Transport> buildTransports() {
        List<Transport> transports = new ArrayList<>();
        for (String candidate : props.getSubscription().getProxyCandidates()) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                URI proxyUri = URI.create(candidate.trim());
                String scheme = proxyUri.getScheme() == null ? "" : proxyUri.getScheme().toLowerCase();
                Proxy.Type type = scheme.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                int port = proxyUri.getPort() > 0 ? proxyUri.getPort()
                        : (type == Proxy.Type.SOCKS ? 1080 : 8080);
                transports.add(new Transport(candidate.trim(),
                        buildProxyClient(new Proxy(type, new InetSocketAddress(proxyUri.getHost(), port)))));
            } catch (Exception e) {
                log.warn("代理配置无法解析，已跳过: {} ({})", candidate, e.getMessage());
            }
        }
        transports.add(new Transport("直连(系统DNS)", httpClient));
        return transports;
    }

    /** 构建通过指定代理的 HttpClient，由代理负责远程 DNS 解析（绕过本地污染 DNS） */
    private HttpClient buildProxyClient(Proxy proxy) {
        ProxySelector selector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                log.warn("代理连接失败: {} → {}", uri, sa);
            }
        };
        return HttpClient.newBuilder()
                .proxy(selector)
                .connectTimeout(Duration.ofSeconds(props.getSubscription().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 绕过系统 DNS：DoH 解析域名 → 用解析到的 IP + 正确 SNI 建 TLS 直连，手写 HTTP 报文 */
    private String fetchViaRawSocket(URI uri) throws Exception {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        String ip = resolveDnsViaDoh(host);
        log.info("手动 DNS 解析 {} → {}", host, ip);

        if (!props.getSubscription().isAllowPrivateHosts()
                && SubscriptionUrlValidator.isBlockedAddress(InetAddress.getByName(ip))) {
            throw new IllegalStateException("DoH 将 " + host + " 解析到内网/保留地址 " + ip + "，已拒绝");
        }

        RawHttpResponse response = rawHttpGet(ip, port, host, path, host,
                props.getSubscription().getRequestTimeoutSeconds() * 1000);
        if (response.body().isEmpty()) {
            throw new IllegalStateException("响应体为空");
        }
        return response.body();
    }

    /** 通过 DoH（DNS-over-HTTPS）解析域名 IPv4 */
    private String resolveDnsViaDoh(String hostname) throws Exception {
        AppProperties.Subscription cfg = props.getSubscription();
        String path = "/resolve?name=" + hostname + "&type=A";
        RawHttpResponse response = rawHttpGet(cfg.getDohServerIp(), 443, cfg.getDohServerHost(), path,
                cfg.getDohServerHost(), 10000);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"data\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"").matcher(response.body());
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalStateException("DoH 未返回 A 记录");
    }

    private record RawHttpResponse(String statusLine, String headers, String body) {
    }

    /**
     * 手写 HTTPS GET：阻塞读到 EOF（服务端 {@code Connection: close}），
     * 由 SO_TIMEOUT 兜底，不再固定等待整个超时窗口。
     */
    private RawHttpResponse rawHttpGet(String ip, int port, String hostHeader, String path,
                                       String sni, int timeoutMs) throws IOException {
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
        } catch (Exception e) {
            throw new IOException("初始化 TLS 失败: " + e.getMessage(), e);
        }
        SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket();
        try {
            socket.setSoTimeout(timeoutMs);
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sni)));
            socket.setSSLParameters(params);
            socket.connect(new InetSocketAddress(ip, port),
                    props.getSubscription().getConnectTimeoutSeconds() * 1000);
            socket.startHandshake();

            String httpReq = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: Mozilla/5.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(httpReq.getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] raw = readAll(socket.getInputStream());
            String response = new String(raw, StandardCharsets.UTF_8);
            log.info("收到响应 {} 字节", raw.length);

            int headerEnd = response.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                throw new IOException("无效 HTTP 响应");
            }
            String headers = response.substring(0, headerEnd);
            String statusLine = headers.substring(0, headers.indexOf("\r\n") > 0
                    ? headers.indexOf("\r\n") : headers.length());
            String body = response.substring(headerEnd + 4).trim();
            if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
                body = decodeChunked(body);
            }
            log.info("HTTP 状态: {}", statusLine);
            return new RawHttpResponse(statusLine, headers, body);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        try {
            int read;
            while ((read = in.read(tmp)) != -1) {
                buffer.write(tmp, 0, read);
                if (buffer.size() > MAX_RESPONSE_BYTES) {
                    throw new IOException("响应体超过上限 " + MAX_RESPONSE_BYTES + " 字节");
                }
            }
        } catch (SocketTimeoutException e) {
            // 读超时：使用已读到的内容
        }
        return buffer.toByteArray();
    }

    static String decodeChunked(String body) {
        StringBuilder result = new StringBuilder();
        int pos = 0;
        while (pos < body.length()) {
            int crlf = body.indexOf("\r\n", pos);
            if (crlf < 0) {
                break;
            }
            int size;
            try {
                size = Integer.parseInt(body.substring(pos, crlf).trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size <= 0) {
                break;
            }
            int start = crlf + 2;
            if (start + size > body.length()) {
                break;
            }
            result.append(body, start, start + size);
            pos = start + size + 2;
        }
        return result.toString();
    }
}
