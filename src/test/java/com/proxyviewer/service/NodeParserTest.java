package com.proxyviewer.service;

import com.proxyviewer.model.ProxyNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class NodeParserTest {

    private final NodeParser parser = new NodeParser();

    @Test
    void parsesVlessUriWithAllParams() {
        String uri = "vless://11111111-2222-3333-4444-555555555555@example.com:8443"
                + "?encryption=none&security=tls&type=ws&host=cdn.example.com"
                + "&path=%2Fws%3Fed%3D2048&sni=cdn.example.com&fp=chrome&alpn=h2"
                + "#%F0%9F%87%BA%F0%9F%87%B8%20US-Relay-01";

        ProxyNode node = parser.parse(uri);

        assertThat(node).isNotNull();
        assertThat(node.getProtocol()).isEqualTo("vless");
        assertThat(node.getServer()).isEqualTo("example.com");
        assertThat(node.getPort()).isEqualTo(8443);
        assertThat(node.getUuid()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(node.getNetwork()).isEqualTo("ws");
        assertThat(node.getTls()).isEqualTo("tls");
        assertThat(node.getSecurity()).isEqualTo("tls");
        assertThat(node.getPath()).isEqualTo("/ws?ed=2048");
        assertThat(node.getHost()).isEqualTo("cdn.example.com");
        assertThat(node.getSni()).isEqualTo("cdn.example.com");
        assertThat(node.getFp()).isEqualTo("chrome");
        assertThat(node.getAlpn()).isEqualTo("h2");
        assertThat(node.getName()).isEqualTo("🇺🇸 US-Relay-01");
        assertThat(node.getCountryCode()).isEqualTo("US");
        assertThat(node.getCountryName()).isEqualTo("美国");
    }

    @Test
    void vlessWithoutFragmentFallsBackToHostAndPort() {
        ProxyNode node = parser.parse("vless://uuid-1@1.2.3.4:443?type=tcp&security=none");

        assertThat(node).isNotNull();
        assertThat(node.getName()).isEqualTo("1.2.3.4:443");
        assertThat(node.getPort()).isEqualTo(443);
        assertThat(node.getNetwork()).isEqualTo("tcp");
        assertThat(node.getCountryName()).isNull();
    }

    @Test
    void vlessSupportsIpv6LiteralAndDefaultPort() {
        ProxyNode node = parser.parse("vless://uuid-2@[2001:db8::1]:2053?type=tcp#IPv6");

        assertThat(node).isNotNull();
        assertThat(node.getServer()).isEqualTo("2001:db8::1");
        assertThat(node.getPort()).isEqualTo(2053);

        ProxyNode noPort = parser.parse("vless://uuid-3@example.org?type=tcp");
        assertThat(noPort).isNotNull();
        assertThat(noPort.getPort()).isEqualTo(443);
    }

    @Test
    void parsesVmessBase64Json() {
        String json = "{\"v\":\"2\",\"ps\":\"🇸🇬 SG-01\",\"add\":\"sg.example.com\",\"port\":\"8443\","
                + "\"id\":\"aaaa-bbbb\",\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\","
                + "\"host\":\"sg.example.com\",\"path\":\"/v2\",\"tls\":\"tls\",\"sni\":\"sg.example.com\"}";
        String uri = "vmess://" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        ProxyNode node = parser.parse(uri);

        assertThat(node).isNotNull();
        assertThat(node.getProtocol()).isEqualTo("vmess");
        assertThat(node.getServer()).isEqualTo("sg.example.com");
        assertThat(node.getPort()).isEqualTo(8443);
        assertThat(node.getUuid()).isEqualTo("aaaa-bbbb");
        assertThat(node.getNetwork()).isEqualTo("ws");
        assertThat(node.getPath()).isEqualTo("/v2");
        assertThat(node.getTls()).isEqualTo("tls");
        assertThat(node.getCountryCode()).isEqualTo("SG");
        assertThat(node.getCountryName()).isEqualTo("新加坡");
    }

    @Test
    void ignoresUnsupportedOrBrokenLines() {
        assertThat(parser.parse("trojan://x@example.com:443")).isNull();
        assertThat(parser.parse("https://example.com/sub")).isNull();
        assertThat(parser.parse("vmess://not-base64-$$$")).isNull();
        assertThat(parser.parse("vless://no-at-sign")).isNull();
        assertThat(parser.parse("")).isNull();
        assertThat(parser.parse(null)).isNull();
    }

    @Test
    void flagEmojiWinsOverTextCode() {
        ProxyNode node = new ProxyNode();
        parser.parseCountryFromName(node, "🇯🇵 JP-Relay-01");

        assertThat(node.getCountryCode()).isEqualTo("JP");
        assertThat(node.getCountryName()).isEqualTo("日本");
    }

    /** 历史缺陷：🏁（无区域指示符）曾被误判成 "AY" */
    @Test
    void checkerFlagIsNotMisdetectedAsCountry() {
        ProxyNode node = new ProxyNode();
        parser.parseCountryFromName(node, "🏁 中转-AY");

        assertThat(node.getCountryCode()).isNull();
        assertThat(node.getCountryName()).isNull();
    }

    @Test
    void textCodeRequiresSeparatorAndKnownCode() {
        ProxyNode unknown = new ProxyNode();
        parser.parseCountryFromName(unknown, "ZZ-01");
        assertThat(unknown.getCountryCode()).isNull();

        ProxyNode noSeparator = new ProxyNode();
        parser.parseCountryFromName(noSeparator, "HK01");
        assertThat(noSeparator.getCountryCode()).isNull();

        ProxyNode valid = new ProxyNode();
        parser.parseCountryFromName(valid, "HK-01 香港");
        assertThat(valid.getCountryCode()).isEqualTo("HK");
        assertThat(valid.getCountryName()).isEqualTo("香港");
    }

    @Test
    void flagCountryCodeIsStoredAsTwoLetterCodeNotEmoji() {
        ProxyNode node = new ProxyNode();
        parser.parseCountryFromName(node, "🇩🇪 德国法兰克福");

        assertThat(node.getCountryCode()).isEqualTo("DE");
        assertThat(node.getCountryCode()).hasSize(2);
        assertThat(node.getCountryName()).isEqualTo("德国");
    }

    @Test
    void parsesQueryParamsWithAndWithoutValue() {
        var params = NodeParser.parseQueryParams("a=1&b&c=%2Fx");

        assertThat(params).containsEntry("a", "1").containsEntry("b", "").containsEntry("c", "/x");
    }
}
