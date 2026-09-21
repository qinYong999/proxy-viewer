package com.proxyviewer.service.test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内核配置生成测试。
 *
 * <p>这些断言直接对应"节点能不能连通"的成因：漏掉 flow / pbk / sid / serviceName
 * 会让 Reality、gRPC 节点的配置必然握手失败，从而被误判成节点失效。</p>
 */
class XrayCoreServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 清掉可能由其它测试设置的内核位置。
     * 否则"找不到内核时应优雅降级"这类断言会被污染，得到假结果。
     */
    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void clearCoreLocation() {
        System.clearProperty("pv.xray.dir");
    }

    private XrayCoreService service() {
        AppProperties props = new AppProperties();
        return new XrayCoreService(props);
    }

    private JsonNode outbound(ProxyNode node) {
        return mapper.valueToTree(service().outboundJson(node));
    }

    private static ProxyNode vlessBase() {
        ProxyNode node = new ProxyNode();
        node.setProtocol("vless");
        node.setServer("example.com");
        node.setPort(443);
        node.setUuid("11111111-2222-3333-4444-555555555555");
        return node;
    }

    @Test
    void buildsVlessTlsTcpOutbound() {
        ProxyNode node = vlessBase();
        node.setNetwork("tcp");
        node.setSecurity("tls");
        node.setSni("sni.example.com");
        node.setFp("chrome");
        node.setAlpn("h2,http/1.1");

        JsonNode ob = outbound(node);
        assertThat(ob.get("protocol").asText()).isEqualTo("vless");
        assertThat(ob.at("/settings/vnext/0/address").asText()).isEqualTo("example.com");
        assertThat(ob.at("/settings/vnext/0/port").asInt()).isEqualTo(443);
        assertThat(ob.at("/settings/vnext/0/users/0/id").asText())
                .isEqualTo("11111111-2222-3333-4444-555555555555");
        // encryption 缺省必须是 none，否则 VLESS 无法建立
        assertThat(ob.at("/settings/vnext/0/users/0/encryption").asText()).isEqualTo("none");
        assertThat(ob.at("/streamSettings/network").asText()).isEqualTo("tcp");
        assertThat(ob.at("/streamSettings/security").asText()).isEqualTo("tls");
        assertThat(ob.at("/streamSettings/tlsSettings/serverName").asText()).isEqualTo("sni.example.com");
        assertThat(ob.at("/streamSettings/tlsSettings/fingerprint").asText()).isEqualTo("chrome");
        assertThat(ob.at("/streamSettings/tlsSettings/alpn")).hasSize(2);
    }

    @Test
    void vlessWsUsesHostAsTopLevelFieldNotDeprecatedHeaders() {
        ProxyNode node = vlessBase();
        node.setNetwork("ws");
        node.setPath("/ws-path");
        node.setHost("cdn.example.com");
        node.setSecurity("tls");
        node.setSni("cdn.example.com");

        JsonNode ws = outbound(node).at("/streamSettings/wsSettings");
        assertThat(ws.get("path").asText()).isEqualTo("/ws-path");
        // Xray 26 起 host 从 headers 提升为独立字段，旧写法已被弃用
        assertThat(ws.get("host").asText()).isEqualTo("cdn.example.com");
        assertThat(ws.has("headers")).isFalse();
    }

    @Test
    void vlessRealityCarriesPublicKeyShortIdAndSpiderX() {
        ProxyNode node = vlessBase();
        node.setNetwork("tcp");
        node.setSecurity("reality");
        node.setSni("www.microsoft.com");
        node.setFp("chrome");
        node.setPublicKey("PUBLIC-KEY-BASE64");
        node.setShortId("0123abcd");
        node.setSpiderX("/spx");
        node.setFlow("xtls-rprx-vision");

        JsonNode ob = outbound(node);
        assertThat(ob.at("/streamSettings/security").asText()).isEqualTo("reality");
        JsonNode rs = ob.at("/streamSettings/tlsSettings/realitySettings");
        assertThat(rs.get("publicKey").asText()).isEqualTo("PUBLIC-KEY-BASE64");
        assertThat(rs.get("shortId").asText()).isEqualTo("0123abcd");
        assertThat(rs.get("spiderX").asText()).isEqualTo("/spx");
        assertThat(rs.get("serverName").asText()).isEqualTo("www.microsoft.com");
        // flow 缺失会让 Vision 节点握手失败，必须透传
        assertThat(ob.at("/settings/vnext/0/users/0/flow").asText()).isEqualTo("xtls-rprx-vision");
    }

    @Test
    void realityWithoutPublicKeyFailsLoudlyInsteadOfSilentlyMisjudgingNode() {
        ProxyNode node = vlessBase();
        node.setSecurity("reality");
        node.setSni("www.microsoft.com");

        assertThatThrownBy(() -> service().outboundJson(node))
                .isInstanceOf(XrayCoreService.UnsupportedNodeException.class)
                .hasMessageContaining("REALITY_MISSING_PUBLIC_KEY");
    }

    @Test
    void grpcNodeCarriesServiceName() {
        ProxyNode node = vlessBase();
        node.setNetwork("grpc");
        node.setServiceName("my-grpc-service");
        node.setSecurity("tls");

        JsonNode grpc = outbound(node).at("/streamSettings/grpcSettings");
        assertThat(grpc.get("serviceName").asText()).isEqualTo("my-grpc-service");
    }

    @Test
    void tcpWithHttpHeaderTypeBuildsFakeHeader() {
        ProxyNode node = vlessBase();
        node.setNetwork("tcp");
        node.setHeaderType("http");
        node.setPath("/fake");
        node.setHost("fake.example.com");

        JsonNode tcp = outbound(node).at("/streamSettings/tcpSettings");
        assertThat(tcp.at("/header/type").asText()).isEqualTo("http");
        assertThat(tcp.at("/header/request/path/0").asText()).isEqualTo("/fake");
        assertThat(tcp.at("/header/request/headers/Host/0").asText()).isEqualTo("fake.example.com");
    }

    @Test
    void vmessOutboundUsesAlterIdAndAutoSecurity() {
        ProxyNode node = new ProxyNode();
        node.setProtocol("vmess");
        node.setServer("vmess.example.com");
        node.setPort(8443);
        node.setUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        node.setAid(64);
        node.setNetwork("ws");
        node.setPath("/vpath");
        node.setHost("vhost.example.com");
        node.setTls("tls");
        node.setSni("vhost.example.com");

        JsonNode ob = outbound(node);
        assertThat(ob.get("protocol").asText()).isEqualTo("vmess");
        assertThat(ob.at("/settings/vnext/0/users/0/alterId").asInt()).isEqualTo(64);
        assertThat(ob.at("/settings/vnext/0/users/0/security").asText()).isEqualTo("auto");
        assertThat(ob.at("/streamSettings/security").asText()).isEqualTo("tls");
        assertThat(ob.at("/streamSettings/wsSettings/path").asText()).isEqualTo("/vpath");
    }

    @Test
    void noTlsMeansNoTlsSettingsAtAll() {
        ProxyNode node = vlessBase();
        node.setNetwork("tcp");
        node.setSecurity("");
        node.setTls("");

        JsonNode stream = outbound(node).at("/streamSettings");
        assertThat(stream.has("security")).isFalse();
        assertThat(stream.has("tlsSettings")).isFalse();
    }

    @Test
    void xhttpNetworkCarriesModeAndPath() {
        ProxyNode node = vlessBase();
        node.setNetwork("xhttp");
        node.setPath("/xh");
        node.setServiceName("packet-up");
        node.setHost("xh.example.com");
        node.setSecurity("tls");

        JsonNode xh = outbound(node).at("/streamSettings/xhttpSettings");
        assertThat(xh.get("path").asText()).isEqualTo("/xh");
        assertThat(xh.get("mode").asText()).isEqualTo("packet-up");
        assertThat(xh.get("host").asText()).isEqualTo("xh.example.com");
    }

    @Test
    void rejectsUnsupportedProtocolAndMissingFields() {
        ProxyNode trojan = new ProxyNode();
        trojan.setProtocol("trojan");
        trojan.setServer("t.example.com");
        trojan.setPort(443);
        trojan.setUuid("x");
        assertThatThrownBy(() -> service().outboundJson(trojan))
                .isInstanceOf(XrayCoreService.UnsupportedNodeException.class)
                .hasMessageContaining("UNSUPPORTED_PROTOCOL");

        ProxyNode noUuid = vlessBase();
        noUuid.setUuid("");
        assertThatThrownBy(() -> service().outboundJson(noUuid))
                .isInstanceOf(XrayCoreService.UnsupportedNodeException.class)
                .hasMessageContaining("MISSING_UUID");

        ProxyNode noServer = vlessBase();
        noServer.setServer("");
        assertThatThrownBy(() -> service().outboundJson(noServer))
                .isInstanceOf(XrayCoreService.UnsupportedNodeException.class)
                .hasMessageContaining("BAD_ADDRESS");
    }

    @Test
    void coreAvailabilityIsReportedWithoutThrowing() {
        AppProperties props = new AppProperties();
        // 指向一个必然不存在的路径。注意：不能只靠"PATH 里没有"来判断，
        // 因为开发机可能装了 v2rayN；这里用绝对路径排除歧义。
        props.getTest().setCorePath("Z:\\definitely\\not\\here\\xray.exe");
        props.getTest().setCoreDir("Z:\\definitely\\not\\here");
        XrayCoreService svc = new XrayCoreService(props);
        // 找不到内核时必须优雅返回，页面据此提示如何配置，而不是抛异常
        assertThat(svc.version()).isNotNull();
    }
}
