package com.proxyviewer.service.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：凭据错误的节点必须被判为不可用。
 *
 * <p>这个用例锁定两个曾经真实存在的缺陷：</p>
 * <ol>
 *   <li>旧实现只探端口，把"端口开着但代理不可用"的节点判为可达；</li>
 *   <li>改用内核代理后，若通信层误用 JDK 的 {@code HttpClient}，它会<b>静默忽略
 *       SOCKS 代理</b>，请求退化成直连——于是连错误凭据的节点也会"测通"。
 *       本用例正是为了钉死这一点：只有真正经代理转发，凭据错误才会失败。</li>
 * </ol>
 *
 * <p>做法：本机起一个只接受指定 UUID 的 Xray 服务端，分别用正确与错误的 UUID
 * 各起一个客户端内核去连它。本机没有内核时整个类自动跳过。</p>
 */
@EnabledIf("coreAvailable")
class CredentialMismatchDiagnosticTest {

    private static final String WORK_DIR = System.getProperty("pv.xray.dir",
            "S:\\installationFree\\v2rayN-windows-64\\bin");

    static boolean coreAvailable() {
        return Files.isRegularFile(Path.of(WORK_DIR, "xray", "xray.exe"))
                || Files.isRegularFile(Path.of(WORK_DIR, "xray.exe"));
    }

    private static String xrayExe() {
        Path nested = Path.of(WORK_DIR, "xray", "xray.exe");
        return Files.isRegularFile(nested) ? nested.toString()
                : Path.of(WORK_DIR, "xray.exe").toString();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void wrongCredentialIsReportedAsUnavailableWhileCorrectCredentialWorks() throws Exception {
        int serverPort = freePort();
        String goodUuid = UUID.randomUUID().toString();

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pv-diag");
        Files.createDirectories(dir);
        Path cfg = dir.resolve("credential-server.json");
        Path logFile = dir.resolve("credential-server.log");
        Files.writeString(cfg, serverConfig(serverPort, goodUuid), StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(xrayExe(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        pb.environment().put("XRAY_LOCATION_ASSET", WORK_DIR);
        Process server = pb.start();

        try {
            waitPort(serverPort);

            AppProperties props = new AppProperties();
            props.getTest().setCoreDir(WORK_DIR);
            props.getTest().setLatencyTimeoutMs(8000);
            XrayCoreService core = new XrayCoreService(props);
            NodeTester tester = new NodeTester(props);

            // ---- 正确凭据：必须可达 ----
            int goodPort = freePort();
            XrayCoreService.CoreProcess goodProc =
                    core.start(vlessNode(serverPort, goodUuid), goodPort);
            assertThat(goodProc)
                    .withFailMessage("正确凭据的内核应能启动: %s", core.getLastFailure())
                    .isNotNull();
            try {
                NodeTester.ProbeResult good = tester.measureLatency(null, goodPort);
                assertThat(good.ok())
                        .withFailMessage("正确凭据的节点应当可达，实际 reason=%s", good.reason())
                        .isTrue();
                assertThat(good.value()).isGreaterThan(0);
            } finally {
                goodProc.close();
            }
            // 内核关闭后端口必须释放，否则后续测试会连到已死的内核上
            assertThat(goodProc.alive()).isFalse();

            // ---- 错误凭据：必须不可达 ----
            int badPort = freePort();
            assertThat(badPort).isNotEqualTo(goodPort);
            XrayCoreService.CoreProcess badProc =
                    core.start(vlessNode(serverPort, UUID.randomUUID().toString()), badPort);
            assertThat(badProc)
                    .withFailMessage("错误凭据的内核配置本身应能启动: %s", core.getLastFailure())
                    .isNotNull();
            try {
                NodeTester.ProbeResult bad = tester.measureLatency(null, badPort);
                assertThat(bad.ok())
                        .withFailMessage("凭据错误的节点必须被判为不可用——若此处为可达，"
                                + "说明请求没有真正经过代理（JDK HttpClient 会静默忽略 SOCKS 代理）")
                        .isFalse();
                assertThat(bad.value()).isEqualTo(NodeTester.NOT_MEASURED);
            } finally {
                badProc.close();
            }

            // 内核日志应留下"拒绝非法用户"的记录，佐证请求确实经过了代理
            String log = Files.readString(logFile, StandardCharsets.UTF_8);
            assertThat(log).contains("rejected");
        } finally {
            server.destroy();
            if (!server.waitFor(3, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
            Files.deleteIfExists(cfg);
        }
    }

    private static String serverConfig(int port, String uuid) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.putObject("log").put("loglevel", "info");
        ObjectNode inbound = root.putArray("inbounds").addObject();
        inbound.put("tag", "in").put("listen", "127.0.0.1").put("port", port)
                .put("protocol", "vless");
        inbound.putObject("settings").put("decryption", "none")
                .putArray("clients").addObject().put("id", uuid);
        root.putArray("outbounds").addObject()
                .put("tag", "direct").put("protocol", "freedom").putObject("settings");
        return mapper.writeValueAsString(root);
    }

    private static ProxyNode vlessNode(int serverPort, String uuid) {
        ProxyNode node = new ProxyNode();
        node.setProtocol("vless");
        node.setServer("127.0.0.1");
        node.setPort(serverPort);
        node.setUuid(uuid);
        node.setNetwork("tcp");
        node.setSecurity("");
        node.setTls("");
        node.setName("credential-probe");
        return node;
    }

    private static void waitPort(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return;
            } catch (Exception e) {
                Thread.sleep(40);
            }
        }
        throw new IllegalStateException("测试用 Xray 服务端未能在 8 秒内就绪");
    }

    @Test
    void generatedConfigIsAValidXrayConfig() throws Exception {
        AppProperties props = new AppProperties();
        props.getTest().setCoreDir(WORK_DIR);
        XrayCoreService core = new XrayCoreService(props);

        XrayCoreService.GeneratedConfig generated =
                core.generate(vlessNode(443, UUID.randomUUID().toString()), 25901);
        try {
            var json = new ObjectMapper().readTree(
                    Files.readString(generated.file(), StandardCharsets.UTF_8));
            assertThat(json.at("/inbounds/0/protocol").asText()).isEqualTo("socks");
            assertThat(json.at("/inbounds/0/port").asInt()).isEqualTo(25901);
            // UDP 测试依赖入站的 UDP ASSOCIATE 支持
            assertThat(json.at("/inbounds/0/settings/udp").asBoolean()).isTrue();
            assertThat(json.at("/outbounds/0/protocol").asText()).isEqualTo("vless");
            assertThat(json.at("/outbounds/0/tag").asText())
                    .isEqualTo(json.at("/routing/rules/0/outboundTag").asText());
            // 域名交给节点远端解析，绕开本机可能被污染的 DNS
            assertThat(json.at("/routing/domainStrategy").asText()).isEqualTo("AsIs");
        } finally {
            Files.deleteIfExists(generated.file());
        }
    }
}
