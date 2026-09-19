package com.proxyviewer.service.test;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内核进程托管集成测试：真实启动一次 Xray，验证「起得来、判得准、关得掉」。
 *
 * <p>本机没有内核时整个类自动跳过（{@link EnabledIf}），因此不会让 CI 或他人机器变红。
 * 本机内核位置通过 {@code -Dpv.xray.dir=...} 覆盖。</p>
 */
@EnabledIf("coreAvailable")
class CoreServiceIntegrationTest {

    private static final String WORK_DIR = System.getProperty("pv.xray.dir",
            "S:\\installationFree\\v2rayN-windows-64\\bin");

    /** 测试用的本地 SOCKS 入站端口 */
    private static final int SOCKS_PORT = 25999;

    static boolean coreAvailable() {
        return Path.of(WORK_DIR, "xray", "xray.exe").toFile().isFile()
                || Path.of(WORK_DIR, "xray.exe").toFile().isFile();
    }

    private XrayCoreService newService() {
        AppProperties props = new AppProperties();
        props.getTest().setCoreDir(WORK_DIR);
        props.getTest().setCoreStartupTimeoutMs(10000);
        return new XrayCoreService(props);
    }

    /**
     * 节点指向一个不会真正建立连接的本地端口：
     * 配置合法、内核能起、SOCKS 入站会监听，但出站不可达——足以验证生命周期。
     */
    private static ProxyNode dummyNode() {
        ProxyNode node = new ProxyNode();
        node.setProtocol("vless");
        node.setServer("127.0.0.1");
        node.setPort(9);   // discard 端口，必然连不上
        node.setUuid(UUID.randomUUID().toString());
        node.setNetwork("tcp");
        node.setSecurity("none");
        node.setName("core-lifecycle-probe");
        return node;
    }

    @Test
    void locatesCoreAndReadsVersion() {
        XrayCoreService service = newService();
        assertThat(service.isCoreAvailable()).isTrue();
        assertThat(service.getCorePath()).isNotBlank();
        // 资源目录必须能定位到，否则 geoip/geosite 加载会失败
        assertThat(service.getAssetDir()).isNotBlank();
        assertThat(service.version()).containsIgnoringCase("Xray");
    }

    @Test
    void startsCoreWaitsForRealSocksHandshakeThenCleansUp() throws IOException {
        XrayCoreService service = newService();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "proxy-viewer-core");
        long before = countTempConfigs(tempDir);

        XrayCoreService.CoreProcess process = service.start(dummyNode(), SOCKS_PORT);
        try {
            assertThat(process)
                    .withFailMessage("内核应能启动并就绪，失败原因: %s", service.getLastFailure())
                    .isNotNull();
            assertThat(process.alive()).isTrue();
            assertThat(process.socksPort()).isEqualTo(SOCKS_PORT);

            // 就绪判定用的是真实 SOCKS5 握手，这里独立复验一次
            assertThat(socksHandshakeOk(SOCKS_PORT)).isTrue();
            // 临时配置文件应已生成
            assertThat(countTempConfigs(tempDir)).isEqualTo(before + 1);
        } finally {
            process.close();
        }

        // 关闭后：进程退出、临时配置被清理，不留垃圾
        assertThat(process.alive()).isFalse();
        assertThat(countTempConfigs(tempDir)).isEqualTo(before);
    }

    @Test
    void reportsFailureReasonWhenConfigIsUnsupported() {
        XrayCoreService service = newService();
        ProxyNode trojan = dummyNode();
        trojan.setProtocol("trojan");

        assertThat(service.start(trojan, SOCKS_PORT)).isNull();
        assertThat(service.getLastFailure()).contains("UNSUPPORTED_PROTOCOL");
    }

    @Test
    void reportsFailureReasonWhenRealityPublicKeyMissing() {
        XrayCoreService service = newService();
        ProxyNode node = dummyNode();
        node.setSecurity("reality");
        node.setSni("www.microsoft.com");

        assertThat(service.start(node, SOCKS_PORT)).isNull();
        assertThat(service.getLastFailure()).contains("REALITY_MISSING_PUBLIC_KEY");
    }

    private static long countTempConfigs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("pv-test-")).count();
        }
    }

    private static boolean socksHandshakeOk(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            socket.setSoTimeout(500);
            socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
            socket.getOutputStream().flush();
            byte[] resp = socket.getInputStream().readNBytes(2);
            return resp.length == 2 && resp[0] == 0x05;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void coreDirResolutionAcceptsV2rayNLayout() {
        // v2rayN 便携版把内核放在 bin/xray/xray.exe，core-dir 指向 bin 即可
        XrayCoreService service = newService();
        assertThat(service.getCorePath()).contains("xray").endsWith("xray.exe");
    }
}
