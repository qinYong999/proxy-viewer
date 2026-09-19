package com.proxyviewer.service;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.service.test.Socks5HttpClient;
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
    /** SOCKS5 通道手工跟随重定向的最大跳数（JDK HttpClient 会自动跟随，自研实现需自己来） */
    private static final int MAX_REDIRECTS = 5;

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
        List<String> failures = new ArrayList<>();

        for (Transport transport : buildTransports()) {
            try {
                log.info("尝试通过 {} 获取订阅...", transport.description());
                String body = transport.fetcher().fetch(uri);
                log.info("通过 {} 成功获取订阅（{} 字符）", transport.description(), body.length());
                return body;
            } catch (Exception e) {
                String reason = describeFailure(e);
                failures.add(transport.description() + " → " + reason);
                log.warn("{} 失败: {}", transport.description(), reason);
            }
        }

        if (props.getSubscription().isRawSocketFallbackEnabled()) {
            log.info("所有代理/直连方式失败，尝试 DoH 解析 + 裸 SSLSocket 直连...");
            try {
                return fetchViaRawSocket(uri);
            } catch (Exception e) {
                String reason = describeFailure(e);
                failures.add("DoH+" + uri.getHost() + " → " + reason);
                log.warn("DoH 直连失败: {}", reason);
            }
        }

        // 把每个通道的失败原因都带上：只报最后一条会让"到底卡在哪"无法判断
        throw new RuntimeException("订阅抓取失败（" + truncate(String.join("；", failures), 400) + "）");
    }

    /**
     * 经 SOCKS5 代理抓取订阅。
     *
     * <p>必须走自研的 {@link Socks5HttpClient}：JDK 的 {@code HttpClient} 不支持 SOCKS，
     * 配置了也会被静默忽略、退化成直连。主机名一律交给代理侧解析（{@code remoteDns=true}），
     * 这样本地 DNS 被污染时依然能连到真实订阅地址。</p>
     */
    private String fetchViaSocks5(String proxyHost, int proxyPort, URI uri) throws Exception {
        AppProperties.Subscription cfg = props.getSubscription();
        int connectMs = cfg.getConnectTimeoutSeconds() * 1000;
        int readMs = cfg.getRequestTimeoutSeconds() * 1000;

        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Socks5HttpClient.Response response = Socks5HttpClient.get(proxyHost, proxyPort, current,
                    connectMs, readMs, MAX_RESPONSE_BYTES, true);

            if (isRedirect(response.status())) {
                String location = headerValue(response.headers(), "location");
                if (location == null || location.isBlank()) {
                    throw new IOException("HTTP " + response.status() + " 重定向但缺少 Location");
                }
                URI next = current.resolve(location.trim());
                // 重定向后的地址同样要过 SSRF 校验，防止用 302 绕到内网
                SubscriptionUrlValidator.validateUri(next, cfg.isAllowPrivateHosts());
                log.info("订阅重定向: {} → {}", current.getHost(), next.getHost());
                current = next;
                continue;
            }
            if (!response.isSuccess()) {
                throw new IOException("HTTP " + response.status());
            }
            String body = response.bodyText();
            if (body == null || body.isBlank()) {
                throw new IOException("HTTP " + response.status() + " 但响应体为空");
            }
            return body.trim();
        }
        throw new IOException("重定向次数超过 " + MAX_REDIRECTS + " 次");
    }

    /** 经 JDK HttpClient 抓取（HTTP 代理与直连共用；JDK 原生支持 HTTP 代理与自动重定向） */
    private String fetchViaHttpClient(HttpClient client, URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(props.getSubscription().getRequestTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // 跟随重定向后要重新校验最终地址，防止用 302 绕过内网校验
        SubscriptionUrlValidator.validateUri(response.uri(),
                props.getSubscription().isAllowPrivateHosts());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new IOException("HTTP " + response.statusCode() + " 但响应体为空");
        }
        return body.trim();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String headerValue(String headers, String name) {
        if (headers == null) {
            return null;
        }
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** 把异常翻译成页面/日志里能直接看懂的原因 */
    static String describeFailure(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException || e instanceof SocketTimeoutException) {
            return "连接超时";
        }
        if (e instanceof java.net.ConnectException) {
            return "连接被拒绝";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "DNS 解析失败";
        }
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private record Transport(String description, Fetcher fetcher) {
    }

    /** 一种抓取通道：成功返回订阅原文，失败抛异常（消息用于诊断展示） */
    @FunctionalInterface
    private interface Fetcher {
        String fetch(URI uri) throws Exception;
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
                String host = proxyUri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("缺少主机名");
                }
                boolean socks = scheme.startsWith("socks");
                int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : (socks ? 1080 : 8080);
                if (socks) {
                    // 这里绝不能用 JDK 的 HttpClient：它不支持 SOCKS，会静默忽略代理配置改成直连，
                    // 订阅域名被墙时就只剩 connect timeout（这正是"点击刷新必然失败"的老问题）
                    final String proxyHost = host;
                    final int proxyPort = port;
                    transports.add(new Transport(candidate.trim(),
                            target -> fetchViaSocks5(proxyHost, proxyPort, target)));
                } else {
                    HttpClient client = buildProxyClient(
                            new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
                    transports.add(new Transport(candidate.trim(),
                            target -> fetchViaHttpClient(client, target)));
                }
            } catch (Exception e) {
                log.warn("代理配置无法解析，已跳过: {} ({})", candidate, e.getMessage());
            }
        }
        transports.add(new Transport("直连(系统DNS)", target -> fetchViaHttpClient(httpClient, target)));
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
