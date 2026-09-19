package com.proxyviewer.service;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.NodeTestRecord;
import com.proxyviewer.model.NodeTestRecordRepository;
import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import com.proxyviewer.service.test.NodeTester;
import com.proxyviewer.service.test.XrayCoreService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点测试编排：TCPing 预筛 → 真实延迟（经内核代理） → UDP 可用性 → 下载测速。
 *
 * <p>与旧实现的根本差别：旧代码直接对节点服务器端口发 HTTP/WS 握手，只能证明"端口开着"，
 * 会把大量已被阻断的节点误判为可达。现在每个节点都会被翻译成一份真实的 Xray 配置、
 * 启动独立内核，再通过内核的本地 SOCKS 入站真实访问外网——测的是代理到底能不能用。</p>
 *
 * <p>保留的旧有正确行为：失败不物理删除（只打 {@code FAILED:} 标记并累加连续失败次数）、
 * 批量落库、定时与手动测试互斥。</p>
 */
@Service
public class NodeTestService {

    private static final Logger log = LoggerFactory.getLogger(NodeTestService.class);
    private static final Logger oplog = LoggerFactory.getLogger("OPLOG");

    public static final String RESULT_OK = "OK";
    /** 未测得的哨兵值，页面以 — / N/A 呈现 */
    public static final long NOT_MEASURED = -1L;

    /** 本地 SOCKS 入站端口基址；内核由本应用独占，用高位端口避开 v2rayN 的 10808/10809 */
    private static final int BASE_SOCKS_PORT = 24000;

    private final AppProperties props;
    private final ProxyNodeRepository nodeRepository;
    private final NodeTestRecordRepository recordRepository;
    private final NodeTester tester;
    private final XrayCoreService core;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger threadSeq = new AtomicInteger();
    /** 测试阶段描述，供页面显示进度 */
    private final AtomicInteger doneCount = new AtomicInteger();
    private volatile int totalCount = 0;
    private volatile String phase = "";
    private volatile long startTimeMs = 0;
    private volatile long lastEndTimeMs = 0;
    private volatile String lastMessage = "";
    /** 最近处理的节点事件（供页面实时滚动显示），容量固定 */
    private final Deque<String> recentEvents = new ArrayDeque<>();
    private static final int MAX_EVENTS = 12;

    private final ExecutorService executor;

    public NodeTestService(AppProperties props,
                           ProxyNodeRepository nodeRepository,
                           NodeTestRecordRepository recordRepository,
                           NodeTester tester,
                           XrayCoreService core) {
        this.props = props;
        this.nodeRepository = nodeRepository;
        this.recordRepository = recordRepository;
        this.tester = tester;
        this.core = core;
        // 线程池按"延迟并发 + 测速并发"取，避免测速阶段排队等待
        AppProperties.Test cfg = props.getTest();
        int poolSize = Math.max(2, cfg.getLatencyConcurrency() + cfg.getSpeedConcurrency());
        this.executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "node-test-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 单节点完整测试结果（内存态，批次结束后统一落库） */
    private record TestOutcome(ProxyNode node,
                               boolean success,
                               long latencyMs,
                               long speedKbps,
                               boolean udpOk,
                               long udpMs,
                               String reason) {
    }

    /**
     * 测试进度快照。
     *
     * @param running   是否正在执行
     * @param phase     当前阶段中文描述
     * @param done      已完成节点数
     * @param total     总节点数
     * @param elapsedMs 已耗时（毫秒）；未开始时为 0
     * @param message   完成/失败时的一句话结论
     * @param events    最近处理的节点（新的在前）
     */
    public record ProgressSnapshot(boolean running, String phase, int done, int total,
                                   long elapsedMs, String message, List<String> events) {
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 测试进度：已完成 / 总数 / 当前阶段 */
    public int getDoneCount() {
        return doneCount.get();
    }

    public int getTotalCount() {
        return totalCount;
    }

    public String getPhase() {
        return phase;
    }

    /** 最近一次测试的结论（完成后保留，供页面在刷新后仍能显示） */
    public String getLastMessage() {
        return lastMessage;
    }

    /** 最近一次测试是否已产出结论 */
    public boolean hasFinishedOnce() {
        return lastEndTimeMs > 0;
    }

    /** 供前端轮询：一次性取走全部进度信息，避免多次读取时状态不一致 */
    public ProgressSnapshot snapshot() {
        boolean isRunning = running.get();
        long elapsed = 0;
        if (isRunning && startTimeMs > 0) {
            elapsed = System.currentTimeMillis() - startTimeMs;
        } else if (!isRunning && startTimeMs > 0 && lastEndTimeMs >= startTimeMs) {
            elapsed = lastEndTimeMs - startTimeMs;
        }
        List<String> events;
        synchronized (recentEvents) {
            events = new ArrayList<>(recentEvents);
        }
        return new ProgressSnapshot(isRunning, phase, doneCount.get(), totalCount,
                elapsed, lastMessage, events);
    }

    /** 记录一条节点处理事件，供页面实时滚动显示（过长会挤掉信息，这里限长） */
    private void recordEvent(String event) {
        if (event == null) {
            return;
        }
        String text = event.replaceAll("\\s+", " ").trim();
        final int maxEvent = 96;
        if (text.length() > maxEvent) {
            text = text.substring(0, maxEvent) + "…";
        }
        synchronized (recentEvents) {
            recentEvents.addFirst(text);
            while (recentEvents.size() > MAX_EVENTS) {
                recentEvents.removeLast();
            }
        }
    }

    private void clearEvents() {
        synchronized (recentEvents) {
            recentEvents.clear();
        }
    }

    /**
     * 立即返回并在后台线程执行测试。
     *
     * <p>整批测试耗时可达数分钟（每节点都要真实启动一次内核），若让 HTTP 请求同步等待，
     * 页面在整段时间内毫无反馈——用户无从判断是卡死还是在跑。这里改为提交后台执行，
     * 前端轮询 {@link #snapshot()} 展示进度。</p>
     *
     * @throws IllegalStateException 已有测试在执行
     */
    public void startTestRun(String triggerType) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有测试任务正在执行，请等待其完成后再试");
        }
        startTimeMs = System.currentTimeMillis();
        lastEndTimeMs = 0;
        lastMessage = "";
        doneCount.set(0);
        totalCount = 0;
        phase = "准备中";
        clearEvents();
        log.info("收到测试请求 [{}]，已转入后台执行", triggerType);

        executor.submit(() -> {
            try {
                NodeTestRecord record = doRunTest(triggerType);
                lastMessage = finishMessage(record);
            } catch (Exception e) {
                lastMessage = "测试失败: " + XrayCoreService.shortMessage(e);
                log.error("后台测试执行失败", e);
            } finally {
                lastEndTimeMs = System.currentTimeMillis();
                phase = "";
                running.set(false);
            }
        });
    }

    /** 统一的完成结论文案，异步与同步路径共用 */
    static String finishMessage(NodeTestRecord record) {
        if (record.getTotalNodes() == 0) {
            return "没有可测试的节点，请先刷新订阅";
        }
        return String.format("测试完成：总计 %d，真实可达 %d，失败 %d",
                record.getTotalNodes(), record.getSuccessCount(), record.getFailedCount());
    }

    /** 同步执行测试（供定时任务与测试代码调用） */
    public NodeTestRecord runTest(String triggerType) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有测试任务正在执行，请等待其完成后再试");
        }
        startTimeMs = System.currentTimeMillis();
        lastEndTimeMs = 0;
        lastMessage = "";
        doneCount.set(0);
        totalCount = 0;
        clearEvents();
        try {
            NodeTestRecord record = doRunTest(triggerType);
            lastMessage = finishMessage(record);
            return record;
        } catch (Exception e) {
            lastMessage = "测试失败: " + XrayCoreService.shortMessage(e);
            throw e;
        } finally {
            lastEndTimeMs = System.currentTimeMillis();
            phase = "";
            running.set(false);
        }
    }

    private NodeTestRecord doRunTest(String triggerType) {
        long t0 = System.currentTimeMillis();
        List<ProxyNode> allNodes = nodeRepository.findAll();
        AppProperties.Test cfg = props.getTest();
        int autoDeleteAfter = cfg.getAutoDeleteAfterFailures();

        log.info("========== 开始节点真实可用性测试 [{}] ==========", triggerType);
        if (!core.isCoreAvailable()) {
            log.error("未找到代理内核，无法进行真实代理测试。请配置 app.test.core-path 或 app.test.core-dir");
        } else {
            log.info("内核: {} | 资源目录: {}", core.getCorePath(), core.getAssetDir());
        }
        log.info("节点 {} 个 | 延迟并发 {} | 测速并发 {} | 测速={} | UDP={} | TCPing预筛={}",
                allNodes.size(), cfg.getLatencyConcurrency(), cfg.getSpeedConcurrency(),
                cfg.isSpeedTestEnabled() ? "开" : "关",
                cfg.isUdpTestEnabled() ? "开" : "关",
                cfg.isTcpingPreFilter() ? "开" : "关");

        totalCount = allNodes.size();
        doneCount.set(0);
        if (allNodes.isEmpty()) {
            // 空库：直接给出结论（由调用方经 finishMessage 统一呈现），
            // 避免"进度条永远停在 0%"的困惑
            log.info("库中没有节点，本次测试直接结束");
            return new NodeTestRecord(LocalDateTime.now(), 0, triggerType);
        }

        // ---------- 阶段 1：TCPing 预筛（不启动内核，成本极低） ----------
        List<TestOutcome> outcomes;
        if (cfg.isTcpingPreFilter()) {
            phase = "TCPing 预筛";
            outcomes = runTcpingStage(allNodes, cfg);
        } else {
            outcomes = null;
        }

        // ---------- 阶段 2：真实延迟 + UDP（每节点独立内核） ----------
        phase = "真实延迟测试";
        List<TestOutcome> finalOutcomes = runLatencyStage(allNodes, outcomes, cfg);

        // ---------- 阶段 3：下载测速（仅对延迟通过的节点，独立限并发） ----------
        if (cfg.isSpeedTestEnabled()) {
            phase = "下载测速";
            finalOutcomes = runSpeedStage(finalOutcomes, cfg);
        }

        // ---------- 落库 ----------
        phase = "写入结果";
        LocalDateTime now = LocalDateTime.now();
        List<ProxyNode> toSave = new ArrayList<>(allNodes.size());
        List<ProxyNode> toDelete = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int udpOk = 0;
        int speedMeasured = 0;
        long latencySum = 0;

        for (TestOutcome outcome : finalOutcomes) {
            ProxyNode node = outcome.node();
            node.setLastTestTime(now);

            if (outcome.success()) {
                node.setLastTestResult(RESULT_OK);
                node.setLatencyMs(outcome.latencyMs());
                node.setSpeedKbps(outcome.speedKbps());
                node.setConsecutiveFailures(0);
                success++;
                latencySum += outcome.latencyMs();
                if (outcome.speedKbps() != NOT_MEASURED) {
                    speedMeasured++;
                }
                if (outcome.udpOk()) {
                    udpOk++;
                }
                toSave.add(node);
                continue;
            }

            failed++;
            int consecutive = node.getConsecutiveFailures() + 1;
            node.setConsecutiveFailures(consecutive);
            node.setLastTestResult(NodeSyncService.FAILED_PREFIX + shortenReason(outcome.reason()));
            node.setLatencyMs(NOT_MEASURED);
            node.setSpeedKbps(NOT_MEASURED);
            log.warn("  #{} {} ❌ {} (连续失败 {})", node.getId(), node.getName(),
                    outcome.reason(), consecutive);
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
        record.setUdpSuccessCount(udpOk);
        record.setSpeedTestCount(speedMeasured);
        record.setAvgLatencyMs(success > 0 ? latencySum / success : NOT_MEASURED);
        recordRepository.save(record);

        log.info("========== 测试完成 [{}] ==========", triggerType);
        log.info("总计 {} | 真实可达 {} | 失败 {} | 自动删除 {} | UDP 可用 {} | 已测速 {} | 平均延迟 {}ms | 耗时 {}ms",
                allNodes.size(), success, failed, toDelete.size(), udpOk, speedMeasured,
                record.getAvgLatencyMs() == NOT_MEASURED ? "N/A" : record.getAvgLatencyMs(), elapsed);
        if (failed > 0 && autoDeleteAfter == 0) {
            log.info("失败节点已保留并打上 FAILED 标记，可在页面点击「清理失败节点」手动删除");
        }
        oplog.info("[NODE_TEST] {} | 总计={} 可达={} 失败={} UDP可用={} 已测速={} 耗时={}ms",
                triggerType, allNodes.size(), success, failed, udpOk, speedMeasured, elapsed);
        return record;
    }

    // ======================== 阶段 1：TCPing ========================

    /**
     * 纯 TCP 连接测时，不启动内核。用于快速剔除端口不通的节点。
     *
     * <p>与 v2rayN 的差异：v2rayN 只取 DNS 解析出的第一个 IP（IPv6 优先时会大量假死），
     * 这里遍历全部解析结果取最快成功者。</p>
     */
    private List<TestOutcome> runTcpingStage(List<ProxyNode> nodes, AppProperties.Test cfg) {
        List<CompletableFuture<TestOutcome>> futures = new ArrayList<>(nodes.size());
        ExecutorService tcpingPool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(cfg.getTcpingThreads(), Math.max(1, nodes.size()))),
                runnable -> {
                    Thread t = new Thread(runnable, "node-tcping-" + threadSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        try {
            for (ProxyNode node : nodes) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    long ms = tcping(node, cfg.getTcpingTimeoutMs());
                    doneCount.incrementAndGet();
                    recordEvent((ms >= 0 ? "✓ " : "✗ ") + displayName(node)
                            + (ms >= 0 ? " TCP " + ms + "ms" : " TCP 不通"));
                    return ms >= 0
                            ? new TestOutcome(node, true, ms, NOT_MEASURED, false, NOT_MEASURED, null)
                            : new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, false,
                            NOT_MEASURED, "TCP_UNREACHABLE");
                }, tcpingPool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<TestOutcome> results = new ArrayList<>(futures.size());
            for (CompletableFuture<TestOutcome> f : futures) {
                results.add(f.join());
            }
            long reachable = results.stream().filter(TestOutcome::success).count();
            log.info("TCPing 预筛完成: {}/{} 个节点 TCP 可达", reachable, results.size());
            return results;
        } finally {
            tcpingPool.shutdownNow();
        }
    }

    /** @return 连接耗时毫秒；不可达返回 -1 */
    static long tcping(ProxyNode node, int timeoutMs) {
        String server = node.getServer();
        if (server == null || server.isBlank() || node.getPort() <= 0) {
            return -1;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(server);
        } catch (Exception e) {
            return -1;
        }
        if (addresses.length == 0) {
            return -1;
        }
        long best = Long.MAX_VALUE;
        for (InetAddress addr : addresses) {
            long t0 = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(addr, node.getPort()), timeoutMs);
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
            } catch (Exception ignored) {
                // 该地址不通，试下一个
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    // ======================== 阶段 2：真实延迟 + UDP ========================

    private List<TestOutcome> runLatencyStage(List<ProxyNode> nodes, List<TestOutcome> tcpingResults,
                                              AppProperties.Test cfg) {
        // 用 TCPing 结果做预筛：只对 TCP 可达的节点启动内核
        List<ProxyNode> candidates = new ArrayList<>();
        List<TestOutcome> skipped = new ArrayList<>();
        if (tcpingResults == null) {
            candidates.addAll(nodes);
        } else {
            for (TestOutcome r : tcpingResults) {
                if (r.success()) {
                    candidates.add(r.node());
                } else {
                    skipped.add(r);
                }
            }
        }

        int concurrency = Math.max(1, Math.min(cfg.getLatencyConcurrency(), Math.max(1, candidates.size())));
        // 只测 TCP 可达的节点：进度总量随之收敛，否则进度条永远到不了 100%
        totalCount = candidates.size();
        doneCount.set(0);
        log.info("开始真实延迟测试: {} 个候选节点（TCPing 预筛已排除 {} 个）",
                candidates.size(), skipped.size());
        AtomicInteger portSeq = new AtomicInteger();
        List<CompletableFuture<TestOutcome>> futures = new ArrayList<>(candidates.size());
        for (ProxyNode node : candidates) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                int port = nextPort(portSeq);
                try {
                    return testNodeReal(node, port, cfg);
                } finally {
                    doneCount.incrementAndGet();
                }
            }, executor).exceptionally(e -> {
                log.error("测试节点 #{} 异常: {}", node.getId(), e.getMessage());
                return new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, false,
                        NOT_MEASURED, "ERROR:" + XrayCoreService.shortMessage(e));
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<TestOutcome> results = new ArrayList<>(skipped);
        for (CompletableFuture<TestOutcome> f : futures) {
            results.add(f.join());
        }
        // 保持与输入顺序一致，便于日志阅读
        results.sort((a, b) -> {
            Long ida = a.node().getId();
            Long idb = b.node().getId();
            return Long.compare(ida == null ? 0 : ida, idb == null ? 0 : idb);
        });
        long ok = results.stream().filter(TestOutcome::success).count();
        log.info("真实延迟测试完成: {}/{} 个节点真实可用（并发内核 {}）", ok, results.size(), concurrency);
        return results;
    }

    /**
     * 单节点真实测试：启动独立内核 → 经代理探测真实延迟 → （可选）UDP 可用性。
     * 任何情况下都保证内核被关闭。
     */
    private TestOutcome testNodeReal(ProxyNode node, int socksPort, AppProperties.Test cfg) {
        if (!core.isCoreAvailable()) {
            recordEvent("✗ " + displayName(node) + " 未找到代理内核");
            return new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, false, NOT_MEASURED,
                    "NO_CORE");
        }
        XrayCoreService.CoreProcess process = null;
        try {
            process = core.start(node, socksPort);
            if (process == null) {
                String why = core.getLastFailure();
                recordEvent("✗ " + displayName(node) + " " + shortenReason(why));
                return new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, false, NOT_MEASURED,
                        why);
            }

            NodeTester.ProbeResult latency = tester.measureLatency(node, socksPort);
            if (!latency.ok()) {
                log.info("  #{} {} ❌ 真实延迟失败: {}", node.getId(), node.getName(), latency.reason());
                recordEvent("✗ " + displayName(node) + " " + shortenReason(latency.reason()));
                return new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, false, NOT_MEASURED,
                        latency.reason());
            }

            boolean udpOk = false;
            long udpMs = NOT_MEASURED;
            if (cfg.isUdpTestEnabled()) {
                NodeTester.ProbeResult udp = tester.measureUdp(socksPort);
                udpOk = udp.ok();
                udpMs = udp.value();
            }

            log.info("  #{} {} ✅ 真实延迟 {}ms{}", node.getId(), node.getName(), latency.value(),
                    cfg.isUdpTestEnabled() ? (" | UDP " + (udpOk ? udpMs + "ms" : "不可用")) : "");
            recordEvent("✓ " + displayName(node) + " " + latency.value() + "ms"
                    + (cfg.isUdpTestEnabled() ? (udpOk ? " / UDP " + udpMs + "ms" : " / UDP 不可用") : ""));
            return new TestOutcome(node, true, latency.value(), NOT_MEASURED, udpOk, udpMs, null);
        } catch (Exception e) {
            log.warn("  #{} {} 测试异常: {}", node.getId(), node.getName(), e.getMessage());
            recordEvent("✗ " + displayName(node) + " " + shortenReason(NodeTester.classify(e)));
            return new TestOutcome(node, false, NOT_MEASURED, NOT_MEASURED, false, NOT_MEASURED,
                    NodeTester.classify(e));
        } finally {
            if (process != null) {
                process.close();
            }
        }
    }

    // ======================== 阶段 3：测速 ========================

    private List<TestOutcome> runSpeedStage(List<TestOutcome> outcomes, AppProperties.Test cfg) {
        List<TestOutcome> reachable = outcomes.stream().filter(TestOutcome::success).toList();
        if (reachable.isEmpty()) {
            log.info("无可用节点，跳过测速");
            return outcomes;
        }
        int concurrency = Math.max(1, Math.min(cfg.getSpeedConcurrency(), reachable.size()));
        log.info("开始测速 {} 个可达节点（并发内核 {}，限时 {}ms）",
                reachable.size(), concurrency, cfg.getSpeedTestDurationMs());
        // 测速只针对可达节点：重置进度基准，避免进度条停在延迟阶段的总数上不动
        totalCount = reachable.size();
        doneCount.set(0);
        recordEvent("开始测速 " + reachable.size() + " 个可达节点");

        ExecutorService speedPool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread t = new Thread(runnable, "node-speed-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        List<CompletableFuture<TestOutcome>> futures = new ArrayList<>(reachable.size());
        AtomicInteger portSeq = new AtomicInteger();
        try {
            for (TestOutcome outcome : reachable) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    ProxyNode node = outcome.node();
                    int port = nextPort(portSeq);
                    XrayCoreService.CoreProcess process = null;
                    try {
                        process = core.start(node, port);
                        if (process == null) {
                            recordEvent("✗ " + displayName(node) + " 测速时内核启动失败");
                            return outcome;
                        }
                        NodeTester.ProbeResult speed = tester.measureSpeed(node, port);
                        if (speed.ok()) {
                            log.info("  #{} {} ⚡ {} Kbps ({} MB/s)", node.getId(), node.getName(),
                                    speed.value(), String.format("%.2f", speed.value() / 8000.0));
                            recordEvent("⚡ " + displayName(node) + " "
                                    + String.format("%.2f", speed.value() / 8000.0) + " MB/s");
                            return new TestOutcome(node, true, outcome.latencyMs(), speed.value(),
                                    outcome.udpOk(), outcome.udpMs(), null);
                        }
                        log.info("  #{} {} 测速失败: {}", node.getId(), node.getName(), speed.reason());
                        recordEvent("✗ " + displayName(node) + " 测速失败 " + speed.reason());
                        return outcome;
                    } catch (Exception e) {
                        log.debug("  #{} 测速异常: {}", node.getId(), e.getMessage());
                        recordEvent("✗ " + displayName(node) + " 测速异常");
                        return outcome;
                    } finally {
                        doneCount.incrementAndGet();
                        if (process != null) {
                            process.close();
                        }
                    }
                }, speedPool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<TestOutcome> merged = new ArrayList<>(outcomes.size());
            for (CompletableFuture<TestOutcome> f : futures) {
                merged.add(f.join());
            }
            // 未被测速的失败节点原样保留
            for (TestOutcome o : outcomes) {
                if (!o.success()) {
                    merged.add(o);
                }
            }
            return merged;
        } finally {
            speedPool.shutdownNow();
        }
    }

    // ======================== 工具 ========================

    /**
     * {@code proxy_nodes.last_test_result} 列长 200，而失败原因可能来自内核/驱动的长文本。
     * 超长会导致整批 {@code saveAll} 因 "Data too long" 整体回滚——一条超长原因就能让
     * 本批次所有节点的测试结果全部丢失。这里按列宽留出余量截断。
     */
    static String shortenReason(String reason) {
        final int maxReason = 180;   // 200 - "FAILED:" 前缀，再留安全余量
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String cleaned = reason.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxReason ? cleaned : cleaned.substring(0, maxReason);
    }

    /** 进度事件里显示的节点名，过长会挤掉其它信息，这里截断 */
    static String displayName(ProxyNode node) {
        String name = node == null ? null : node.getName();
        if (name == null || name.isBlank()) {
            return node == null ? "?" : ("#" + node.getId());
        }
        String trimmed = name.trim();
        return trimmed.length() <= 24 ? trimmed : trimmed.substring(0, 24) + "…";
    }

    /** 分配本地 SOCKS 端口，避开 v2rayN 常用的 108xx 段 */
    private int nextPort(AtomicInteger seq) {
        return BASE_SOCKS_PORT + (seq.incrementAndGet() % 2000);
    }

    /**
     * 分批落库。
     *
     * <p>逐批提交，且单批失败只回退该批并继续：测试本身已经花了几分钟，
     * 不应因为个别行的数据问题丢掉整批结果。</p>
     */
    private void saveInBatches(List<ProxyNode> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        int batchSize = 200;
        int saved = 0;
        for (int i = 0; i < nodes.size(); i += batchSize) {
            List<ProxyNode> batch = nodes.subList(i, Math.min(i + batchSize, nodes.size()));
            try {
                nodeRepository.saveAll(batch);
                saved += batch.size();
            } catch (Exception e) {
                log.error("批量保存测试结果失败（第 {} 批，{} 个节点），该批结果已丢弃: {}",
                        i / batchSize + 1, batch.size(), XrayCoreService.shortMessage(e));
            }
        }
        if (saved < nodes.size()) {
            log.warn("测试结果部分丢失: 期望写入 {} 个，实际成功 {} 个", nodes.size(), saved);
        }
    }
}
