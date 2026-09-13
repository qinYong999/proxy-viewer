package com.proxyviewer.service;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.NodeTestRecord;
import com.proxyviewer.model.NodeTestRecordRepository;
import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点连通性测试。
 *
 * <p>与旧实现的关键差异：</p>
 * <ul>
 *   <li><b>不再"失败即删除"</b>：失败只写 {@code FAILED:原因} 标记并累加连续失败次数，
 *       避免一次网络抖动或断网就把整张表清空；只有显式配置
 *       {@code app.test.auto-delete-after-failures=N} 且连续失败达到 N 次才自动删除。</li>
 *   <li><b>failedCount 真实</b>：统计成功/失败/删除三个数，页面不再恒显示 0。</li>
 *   <li><b>不再伪造速度</b>：明文 TCP 节点无法测速，一律记为未测（-1），
 *       只有真正做了 TLS 下载测速的节点才有速度值。</li>
 *   <li><b>批量落库</b>：测试只修改内存中的实体，结束后分批 {@code saveAll}，
 *       消除每个节点一次 save 的写放大。</li>
 *   <li><b>并发保护</b>：定时任务与手动触发不会重叠执行。</li>
 * </ul>
 */
@Service
public class NodeTestService {

    private static final Logger log = LoggerFactory.getLogger(NodeTestService.class);
    private static final Logger oplog = LoggerFactory.getLogger("OPLOG");

    public static final String RESULT_OK = "OK";
    public static final long NOT_MEASURED = -1L;

    private final AppProperties props;
    private final ProxyNodeRepository nodeRepository;
    private final NodeTestRecordRepository recordRepository;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger threadSeq = new AtomicInteger();

    public NodeTestService(AppProperties props,
                           ProxyNodeRepository nodeRepository,
                           NodeTestRecordRepository recordRepository) {
        this.props = props;
        this.nodeRepository = nodeRepository;
        this.recordRepository = recordRepository;
        this.executor = Executors.newFixedThreadPool(props.getTest().getThreadPoolSize(), runnable -> {
            Thread thread = new Thread(runnable, "node-test-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 单节点测试结果（不直接落库，由调用方统一批量持久化） */
    private record TestOutcome(ProxyNode node, boolean success, long latencyMs, long speedKbps, String reason) {

        static TestOutcome ok(ProxyNode node, long latencyMs, long speedKbps) {
            return new TestOutcome(node, true, latencyMs, speedKbps, null);
        }

        static TestOutcome fail(ProxyNode node, String reason) {
            return new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, reason);
        }
    }

    public NodeTestRecord runTest(String triggerType) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有测试任务正在执行，请等待其完成后再试");
        }
        try {
            return doRunTest(triggerType);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private NodeTestRecord doRunTest(String triggerType) {
        long t0 = System.currentTimeMillis();
        List<ProxyNode> allNodes = nodeRepository.findAll();
        int autoDeleteAfter = props.getTest().getAutoDeleteAfterFailures();

        log.info("========== 开始节点连通性测试 [{}] ==========", triggerType);
        log.info("共计 {} 个节点，并发测试（线程池={}，连续失败 {} 次后自动删除）",
                allNodes.size(), props.getTest().getThreadPoolSize(),
                autoDeleteAfter > 0 ? String.valueOf(autoDeleteAfter) : "永不");

        List<CompletableFuture<TestOutcome>> futures = new ArrayList<>(allNodes.size());
        for (ProxyNode node : allNodes) {
            futures.add(CompletableFuture
                    .supplyAsync(() -> testNode(node), executor)
                    .exceptionally(e -> {
                        log.error("测试节点 #{} {} 异常: {}", node.getId(), node.getName(), e.getMessage());
                        return TestOutcome.fail(node, "ERROR:" + shortMessage(e));
                    }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<ProxyNode> toSave = new ArrayList<>(allNodes.size());
        List<ProxyNode> toDelete = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        int success = 0;
        int failed = 0;

        for (CompletableFuture<TestOutcome> future : futures) {
            TestOutcome outcome = future.join();
            ProxyNode node = outcome.node();
            node.setLastTestTime(now);

            if (outcome.success()) {
                node.setLastTestResult(RESULT_OK);
                node.setLatencyMs(outcome.latencyMs());
                node.setSpeedKbps(outcome.speedKbps());
                node.setConsecutiveFailures(0);
                success++;
                toSave.add(node);
                continue;
            }

            failed++;
            int consecutive = node.getConsecutiveFailures() + 1;
            node.setConsecutiveFailures(consecutive);
            node.setLastTestResult(NodeSyncService.FAILED_PREFIX + outcome.reason());
            node.setLatencyMs(NOT_MEASURED);
            node.setSpeedKbps(NOT_MEASURED);
            log.warn("  #{} {} ❌ {} (连续失败 {})", node.getId(), node.getName(), outcome.reason(), consecutive);

            if (autoDeleteAfter > 0 && consecutive >= autoDeleteAfter) {
                toDelete.add(node);
            } else {
                toSave.add(node);
            }
        }

        saveInBatches(toSave);
        if (!toDelete.isEmpty()) {
            nodeRepository.deleteAll(toDelete);
        }

        long elapsed = System.currentTimeMillis() - t0;
        NodeTestRecord record = new NodeTestRecord(now, allNodes.size(), triggerType);
        record.setSuccessCount(success);
        record.setFailedCount(failed);
        record.setDeletedCount(toDelete.size());
        record.setDurationMs(elapsed);
        recordRepository.save(record);

        log.info("========== 测试完成 [{}] ==========", triggerType);
        log.info("总计: {}, 可达: {}, 失败: {}, 自动删除: {}, 耗时: {}ms",
                allNodes.size(), success, failed, toDelete.size(), elapsed);
        if (failed > 0 && autoDeleteAfter == 0) {
            log.info("失败节点已保留并打上 FAILED 标记，可在页面点击「清理失败节点」手动删除");
        }
        oplog.info("[NODE_TEST] {} | 总计={} 可达={} 失败={} 已删除={} 耗时={}ms",
                triggerType, allNodes.size(), success, failed, toDelete.size(), elapsed);
        return record;
    }

    private void saveInBatches(List<ProxyNode> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        int batchSize = 200;
        for (int i = 0; i < nodes.size(); i += batchSize) {
            nodeRepository.saveAll(nodes.subList(i, Math.min(i + batchSize, nodes.size())));
        }
    }

    // ======================== 单节点测试 ========================

    private TestOutcome testNode(ProxyNode node) {
        String serverAddr = node.getServer();
        int port = node.getPort();

        if (serverAddr == null || serverAddr.isBlank()) {
            return TestOutcome.fail(node, "SKIP:empty_server");
        }

        boolean isWs = "ws".equals(node.getNetwork());
        boolean useTls = "tls".equals(node.getTls()) || "tls".equals(node.getSecurity());
        String requestPath = getRequestPath(node);
        AppProperties.Test cfg = props.getTest();

        String sni = null;
        if (useTls) {
            sni = node.getSni();
            if (sni == null || sni.isBlank()) {
                sni = node.getHost();
            }
            if (sni == null || sni.isBlank()) {
                return TestOutcome.fail(node, "SKIP:empty_sni");
            }
        }
        String host = node.getHost();
        if (host == null || host.isBlank()) {
            host = serverAddr;
        }

        String finalError = null;
        for (int attempt = 0; attempt <= cfg.getMaxRetries(); attempt++) {
            if (attempt > 0) {
                log.info("  #{} ↻ 重试第{}次", node.getId(), attempt);
            }

            long dnsMs;
            long connMs;
            long appMs;
            Socket socket = null;

            try {
                long ps = System.nanoTime();
                InetAddress.getAllByName(serverAddr);
                dnsMs = (System.nanoTime() - ps) / 1_000_000;

                if (!useTls && !isWs) {
                    // ---- 明文 TCP：连接 + 协议字节探针 ----
                    socket = new Socket();
                    socket.setSoTimeout(5000);
                    ps = System.nanoTime();
                    socket.connect(new InetSocketAddress(serverAddr, port), cfg.getConnectTimeoutMs());
                    connMs = (System.nanoTime() - ps) / 1_000_000;

                    String probeError = probePlainTcp(node, socket);
                    if (probeError != null) {
                        finalError = probeError;
                        continue;
                    }

                    // 明文 TCP 无法做 HTTP 测速，速度记为未测，绝不凭 RTT 估算
                    log.info("  #{} {} ✅ {}ms ⚡未测 [dns={} tcp={}]",
                            node.getId(), node.getName(), connMs, dnsMs, connMs);
                    return TestOutcome.ok(node, connMs, NOT_MEASURED);
                }

                // ---- TLS / WS ----
                if (useTls) {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, null, null);
                    SSLSocket sslSocket = (SSLSocket) ctx.getSocketFactory().createSocket();
                    sslSocket.setSoTimeout(cfg.getReadTimeoutMs());
                    SSLParameters params = sslSocket.getSSLParameters();
                    params.setServerNames(List.of(new SNIHostName(sni)));
                    sslSocket.setSSLParameters(params);
                    socket = sslSocket;
                } else {
                    socket = new Socket();
                    socket.setSoTimeout(cfg.getReadTimeoutMs());
                }

                ps = System.nanoTime();
                socket.connect(new InetSocketAddress(serverAddr, port), cfg.getConnectTimeoutMs());
                connMs = (System.nanoTime() - ps) / 1_000_000;

                long appStart = System.nanoTime();
                if (useTls) {
                    ((SSLSocket) socket).startHandshake();
                }

                String httpReq = isWs
                        ? buildWsUpgradeRequest(host, requestPath)
                        : buildHttpRequest(host, requestPath);
                OutputStream out = socket.getOutputStream();
                out.write(httpReq.getBytes(StandardCharsets.UTF_8));
                out.flush();

                byte[] buf = new byte[512];
                int totalRead = readUpTo(socket.getInputStream(), buf, cfg.getReadTimeoutMs());
                appMs = (System.nanoTime() - appStart) / 1_000_000;

                if (totalRead == 0) {
                    finalError = "NO_RESPONSE";
                    continue;
                }

                String response = new String(buf, 0, Math.min(totalRead, 200), StandardCharsets.UTF_8);
                boolean ok = isWs
                        ? (response.contains("101 Switching Protocols") || response.contains("101 WebSocket"))
                        : response.startsWith("HTTP/");

                if (ok) {
                    long totalLatency = connMs + appMs;
                    long speed = NOT_MEASURED;
                    if (useTls && cfg.isSpeedTestEnabled()) {
                        speed = measureSpeedTls(node, sni, host, requestPath, isWs);
                    }
                    log.info("  #{} {} ✅ {}ms [dns={} tcp={} {}={}]", node.getId(), node.getName(),
                            totalLatency, dnsMs, connMs, useTls ? "tls" : "ws", appMs);
                    return TestOutcome.ok(node, totalLatency, speed);
                }

                String preview = response.trim();
                if (preview.length() > 30) {
                    preview = preview.substring(0, 30);
                }
                finalError = "BAD_RESP:" + preview.replace("\r\n", " ");

            } catch (Exception e) {
                finalError = classifyError(e);
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                        // 忽略关闭异常
                    }
                }
            }
        }

        return TestOutcome.fail(node, finalError != null ? finalError : "UNKNOWN");
    }

    /**
     * 明文 TCP 探针：发一个协议版本字节观察服务端反应。
     * 真代理会等待后续数据，普通服务（HTTPS/SSH 等）会立刻关闭连接。
     *
     * @return null 表示像真代理；否则返回失败原因
     */
    private String probePlainTcp(ProxyNode node, Socket socket) {
        try {
            socket.setSoTimeout(500);
            int b = socket.getInputStream().read();
            if (b == -1) {
                return "TCP_CLOSED_IMMEDIATELY";
            }
        } catch (SocketTimeoutException e) {
            // 无数据 → 连接存活，继续探针
        } catch (Exception e) {
            return classifyError(e);
        }

        byte probe = (byte) ("vless".equals(node.getProtocol()) ? 0x00 : 0x01);
        long probeStart = System.nanoTime();
        try {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(probe);
            socket.getOutputStream().flush();
            int r = socket.getInputStream().read();
            long rttMs = (System.nanoTime() - probeStart) / 1_000_000;
            if (r == -1 && rttMs < 300) {
                return "NOT_PROXY:" + rttMs + "ms";
            }
            return null;
        } catch (SocketTimeoutException e) {
            // 2 秒内不关闭 → 服务端在等协议数据 → 像真代理
            return null;
        } catch (SocketException e) {
            long rttMs = (System.nanoTime() - probeStart) / 1_000_000;
            if (rttMs < 300) {
                return "NOT_PROXY:" + rttMs + "ms";
            }
            return classifyError(e);
        } catch (Exception e) {
            return classifyError(e);
        }
    }

    private long measureSpeedTls(ProxyNode node, String sni, String host, String path, boolean isWs) {
        AppProperties.Test cfg = props.getTest();
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket();
            try {
                socket.setSoTimeout(cfg.getSpeedTestDurationMs() + 2000);
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(sni)));
                socket.setSSLParameters(params);
                socket.connect(new InetSocketAddress(node.getServer(), node.getPort()), cfg.getConnectTimeoutMs());
                socket.startHandshake();

                String httpReq = isWs
                        ? buildWsUpgradeRequest(host, path)
                        : buildHttpRequest(host, path);
                OutputStream out = socket.getOutputStream();
                out.write(httpReq.getBytes(StandardCharsets.UTF_8));
                out.flush();

                InputStream in = socket.getInputStream();
                byte[] buf = new byte[4096];
                long totalBytes = 0;
                long deadline = System.currentTimeMillis() + cfg.getSpeedTestDurationMs();
                while (System.currentTimeMillis() < deadline) {
                    if (in.available() > 0) {
                        int read = in.read(buf, 0, Math.min(in.available(), buf.length));
                        if (read == -1) {
                            break;
                        }
                        totalBytes += read;
                    } else {
                        Thread.sleep(20);
                    }
                }
                long speedKbps = totalBytes * 8 / cfg.getSpeedTestDurationMs();
                log.info("  #{} {} ⚡ {} KB/s ({} bytes / {}ms)", node.getId(), node.getName(),
                        speedKbps / 8, totalBytes, cfg.getSpeedTestDurationMs());
                return speedKbps;
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NOT_MEASURED;
        } catch (Exception e) {
            log.debug("  #{} 测速失败: {}", node.getId(), e.getMessage());
            return NOT_MEASURED;
        }
    }

    /** 阻塞读最多 {@code maxLen} 字节或超时 */
    private static int readUpTo(InputStream in, byte[] buf, int timeoutMs) throws Exception {
        int total = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (total < buf.length && System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int read = in.read(buf, total, Math.min(available, buf.length - total));
                if (read == -1) {
                    break;
                }
                total += read;
            } else {
                Thread.sleep(30);
            }
        }
        return total;
    }

    static String classifyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        String lower = msg.toLowerCase();
        if (lower.contains("timeout") || lower.contains("timed out")) return "TIMEOUT";
        if (lower.contains("connection refused") || lower.contains("refused")) return "REFUSED";
        if (lower.contains("unknown host") || lower.contains("dns")) return "DNS_ERR";
        if (lower.contains("certificate") || lower.contains("cert")) return "TLS_CERT";
        if (lower.contains("handshake") || lower.contains("ssl")) return "TLS_HANDSHAKE";
        if (lower.contains("reset") || lower.contains("eof") || lower.contains("broken pipe")) return "RESET";
        if (lower.contains("no route") || lower.contains("unreach")) return "UNREACHABLE";
        return msg.length() > 40 ? msg.substring(0, 40) : msg;
    }

    private static String shortMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 40 ? msg.substring(0, 40) : msg;
    }

    private String getRequestPath(ProxyNode node) {
        if (node.getPath() == null || node.getPath().isBlank()) {
            return "/";
        }
        try {
            String p = URLDecoder.decode(node.getPath(), StandardCharsets.UTF_8);
            if (p.startsWith("/")) {
                return p;
            }
            int slashIdx = p.indexOf('/');
            return slashIdx >= 0 ? p.substring(slashIdx) : "/";
        } catch (Exception e) {
            return "/";
        }
    }

    private static String buildHttpRequest(String host, String path) {
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: Mozilla/5.0\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
    }

    private static String buildWsUpgradeRequest(String host, String path) {
        String key = Base64.getEncoder().encodeToString("test-websocket-key".getBytes(StandardCharsets.UTF_8));
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: Mozilla/5.0\r\n\r\n";
    }
}
