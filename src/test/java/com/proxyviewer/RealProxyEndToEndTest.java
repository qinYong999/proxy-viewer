package com.proxyviewer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.proxyviewer.model.NodeTestRecord;
import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import com.proxyviewer.service.NodeSyncService;
import com.proxyviewer.service.NodeTestService;
import com.proxyviewer.support.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：真实内核 + 真实代理链路。
 *
 * <p>本机自建一个 Xray 服务端作为"节点"，再让被测系统把它当作订阅节点去测试。
 * 这样可以在不依赖外网、不依赖真实机场的前提下，确定性地验证：</p>
 * <ul>
 *   <li>节点能被翻译成合法的内核配置并成功启动；</li>
 *   <li>真实延迟是"经代理访问外网"测出来的，而不是探端口；</li>
 *   <li>不可达节点被标记为失败而非误判为可达；</li>
 *   <li>失败节点只打标记、不物理删除。</li>
 * </ul>
 *
 * <p>本机没有内核时整个类自动跳过。</p>
 */
@IntegrationTest
@EnabledIf("coreAvailable")
class RealProxyEndToEndTest {

    private static final String WORK_DIR = System.getProperty("pv.xray.dir",
            "S:\\installationFree\\v2rayN-windows-64\\bin");

    /** 自建服务端监听的端口区间 */
    private static final int SERVER_BASE_PORT = 27601;

    private static Process serverProcess;
    private static int serverSocksPort;
    private static String vlessUuid;
    private static Path serverConfig;

    static {
        // 让 Spring 上下文里的 XrayCoreService 用到本机内核
        System.setProperty("pv.xray.dir", WORK_DIR);
    }

    static boolean coreAvailable() {
        return Files.isRegularFile(Path.of(WORK_DIR, "xray", "xray.exe"))
                || Files.isRegularFile(Path.of(WORK_DIR, "xray.exe"));
    }

    private static String xrayExe() {
        Path nested = Path.of(WORK_DIR, "xray", "xray.exe");
        return Files.isRegularFile(nested) ? nested.toString()
                : Path.of(WORK_DIR, "xray.exe").toString();
    }

    @Autowired
    private NodeTestService nodeTestService;
    @Autowired
    private ProxyNodeRepository repository;
    @Autowired
    private com.proxyviewer.service.test.XrayCoreService coreService;

    @Test
    void springContextCanSeeTheLocalCore() {
        // 前提校验：上下文里的内核必须指向本机内核，否则后续断言失败的原因会被误判
        assertThat(coreService.isCoreAvailable())
                .withFailMessage("集成测试上下文未定位到内核（pv.xray.dir=%s，解析结果=%s）",
                        System.getProperty("pv.xray.dir"), coreService.getCorePath())
                .isTrue();
    }

    /** 启动一个本地 Xray 服务端：vless(无 TLS) → freedom，供测试当作"可用节点" */
    private static void startFakeServer() throws Exception {
        if (serverProcess != null) {
            return;
        }
        serverSocksPort = freePort();
        vlessUuid = UUID.randomUUID().toString();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.putObject("log").put("loglevel", "warning");
        ArrayNode inbounds = root.putArray("inbounds");
        ObjectNode inbound = inbounds.addObject();
        inbound.put("tag", "vless-in");
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", serverSocksPort);
        inbound.put("protocol", "vless");
        ObjectNode settings = inbound.putObject("settings");
        settings.put("decryption", "none");
        settings.putArray("clients").addObject().put("id", vlessUuid);
        root.putArray("outbounds").addObject()
                .put("tag", "direct").put("protocol", "freedom").putObject("settings");

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pv-e2e");
        Files.createDirectories(dir);
        serverConfig = dir.resolve("fake-server.json");
        Files.writeString(serverConfig, mapper.writeValueAsString(root), StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(xrayExe(), "run", "-c", serverConfig.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Path asset = Path.of(WORK_DIR).getParent();
        if (asset != null && Files.exists(asset.resolve("geoip.dat"))) {
            pb.environment().put("XRAY_LOCATION_ASSET", asset.toString());
        } else {
            pb.environment().put("XRAY_LOCATION_ASSET", WORK_DIR);
        }
        serverProcess = pb.start();
        serverProcess.getOutputStream().close();

        // 等待服务端真正就绪
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", serverSocksPort), 300);
                return;
            } catch (Exception e) {
                Thread.sleep(40);
            }
        }
        throw new IllegalStateException("自建 Xray 服务端未能在 8 秒内就绪");
    }

    @AfterAll
    static void stopFakeServer() throws Exception {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroy();
            if (!serverProcess.waitFor(3, TimeUnit.SECONDS)) {
                serverProcess.destroyForcibly();
            }
        }
        if (serverConfig != null) {
            Files.deleteIfExists(serverConfig);
        }
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ProxyNode node(String name, String server, int port, String uuid,
                                  boolean withTls) {
        ProxyNode n = new ProxyNode();
        n.setName(name);
        n.setProtocol("vless");
        n.setServer(server);
        n.setPort(port);
        n.setUuid(uuid);
        n.setNetwork("tcp");
        n.setSecurity(withTls ? "tls" : "");
        n.setTls(withTls ? "tls" : "");
        n.setSni(withTls ? server : "");
        return n;
    }

    @Test
    void realTestMeasuresLatencyThroughProxyAndMarksUnreachableNodes() throws Exception {
        startFakeServer();

        // 1) 真节点：指向自建服务端，应当被测出真实延迟
        ProxyNode good = node("e2e-可达节点", "127.0.0.1", serverSocksPort, vlessUuid, false);
        // 2) 死节点：端口没人监听，TCPing 就该判失败，不会被误判为可达
        ProxyNode dead = node("e2e-不可达节点", "127.0.0.1", freePort(), UUID.randomUUID().toString(), false);
        List<ProxyNode> saved = repository.saveAll(new ArrayList<>(List.of(good, dead)));

        NodeTestRecord record = nodeTestService.runTest("TEST");

        assertThat(record.getTotalNodes()).isEqualTo(2);
        assertThat(record.getSuccessCount())
                .withFailMessage("应恰好有 1 个节点真实可达（自建服务端），实际 %s；"
                                + "服务端端口=%s，内核=%s",
                        record.getSuccessCount(), serverSocksPort,
                        nodeTestService.getClass().getSimpleName())
                .isEqualTo(1);
        assertThat(record.getFailedCount()).isEqualTo(1);
        // 延迟是经代理实测的值，必须是正数
        assertThat(record.getAvgLatencyMs()).isGreaterThan(0);

        List<ProxyNode> after = repository.findAll();
        assertThat(after).hasSize(2);   // 失败不删除

        ProxyNode goodAfter = after.stream()
                .filter(n -> n.getName().contains("可达节点")
                        && !n.getName().contains("不可达"))
                .findFirst().orElseThrow();
        assertThat(goodAfter.getLastTestResult()).isEqualTo(NodeTestService.RESULT_OK);
        assertThat(goodAfter.getLatencyMs()).isNotNull().isGreaterThan(0);
        assertThat(goodAfter.getConsecutiveFailures()).isZero();

        ProxyNode deadAfter = after.stream()
                .filter(n -> n.getName().contains("不可达"))
                .findFirst().orElseThrow();
        assertThat(deadAfter.getLastTestResult())
                .startsWith(NodeSyncService.FAILED_PREFIX);
        assertThat(deadAfter.getLatencyMs()).isEqualTo(NodeTestService.NOT_MEASURED);
        assertThat(deadAfter.getConsecutiveFailures()).isEqualTo(1);

        assertThat(saved).hasSize(2);
    }

    @Test
    void unreachableNodeNeverReportsOk() throws Exception {
        // 关键回归：旧实现只探端口，这种节点会被误判为"可达"
        ProxyNode dead = node("e2e-端口不通", "127.0.0.1", freePort(),
                UUID.randomUUID().toString(), false);
        repository.save(dead);

        NodeTestRecord record = nodeTestService.runTest("TEST");

        assertThat(record.getSuccessCount()).isZero();
        assertThat(record.getFailedCount()).isEqualTo(1);
        ProxyNode after = repository.findAll().get(0);
        assertThat(after.getLastTestResult()).isNotEqualTo(NodeTestService.RESULT_OK);
        assertThat(after.getLastTestResult()).contains("FAILED:");
    }

    @Test
    void nodeWithWrongCredentialsIsNotReportedAsReachable() throws Exception {
        startFakeServer();
        // 服务端在监听（端口可达、TCPing 会通过），但 UUID 不匹配：
        // 只有"真实经代理访问外网"才能识别出这种节点不可用
        ProxyNode wrongUuid = node("e2e-凭据错误", "127.0.0.1", serverSocksPort,
                UUID.randomUUID().toString(), false);
        repository.save(wrongUuid);

        NodeTestRecord record = nodeTestService.runTest("TEST");

        ProxyNode after = repository.findAll().get(0);
        assertThat(record.getSuccessCount())
                .withFailMessage("UUID 不匹配的节点不应被判定为可用，实际结果=%s",
                        after.getLastTestResult())
                .isZero();
        assertThat(after.getLastTestResult()).startsWith(NodeSyncService.FAILED_PREFIX);
    }
}
