package com.proxyviewer.service.test;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SOCKS5 协议层测试。
 *
 * <p>这一层是自研的，因为 JDK 的 {@code HttpClient} 不支持 SOCKS 代理。
 * 握手报文或 HTTP 解析错一位，就会把可用节点判成不可用（或反之）。</p>
 */
class Socks5HttpClientTest {

    // ======================== 握手报文 ========================

    @Test
    void buildsConnectRequestWithIpv4Address() {
        byte[] request = Socks5HttpClient.buildConnectRequest("1.2.3.4", 443);
        assertThat(request).hasSize(10);
        assertThat(request[0]).isEqualTo((byte) 0x05);   // VER
        assertThat(request[1]).isEqualTo((byte) 0x01);   // CMD = CONNECT
        assertThat(request[2]).isEqualTo((byte) 0x00);   // RSV
        assertThat(request[3]).isEqualTo((byte) 0x01);   // ATYP = IPv4
        assertThat(new byte[]{request[4], request[5], request[6], request[7]})
                .containsExactly((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        // 443 = 0x01BB
        assertThat(request[8]).isEqualTo((byte) 0x01);
        assertThat(request[9]).isEqualTo((byte) 0xBB);
    }

    @Test
    void buildsConnectRequestWithDomainWhenUnresolvable() {
        String host = "no-such-host.invalid";
        byte[] request = Socks5HttpClient.buildConnectRequest(host, 80);
        assertThat(request[3]).isEqualTo((byte) 0x03);   // ATYP = 域名
        assertThat(request[4] & 0xFF).isEqualTo(host.length());
        assertThat(new String(request, 5, host.length(), StandardCharsets.UTF_8)).isEqualTo(host);
        assertThat(request[5 + host.length()]).isEqualTo((byte) 0x00);
        assertThat(request[6 + host.length()]).isEqualTo((byte) 80);
    }

    @Test
    void writeAddressPrefersIpForResolvableHost() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Socks5HttpClient.writeAddress(buffer, "127.0.0.1");
        byte[] raw = buffer.toByteArray();
        assertThat(raw[0]).isEqualTo((byte) 0x01);       // IPv4，省掉代理侧 DNS
        assertThat(raw).hasSize(5);
    }

    /**
     * 远程 DNS 模式：即使本地能解析也必须发域名。
     *
     * <p>本地 DNS 被污染时解析出的是假 IP，只有让代理侧解析才连得到真实目标。</p>
     */
    @Test
    void remoteDnsModeSendsDomainEvenWhenLocallyResolvable() {
        String host = "127.0.0.1";
        byte[] request = Socks5HttpClient.buildConnectRequest(host, 443, true);
        assertThat(request[3]).isEqualTo((byte) 0x03);   // ATYP = 域名
        assertThat(request[4] & 0xFF).isEqualTo(host.length());
        assertThat(new String(request, 5, host.length(), StandardCharsets.UTF_8)).isEqualTo(host);
    }

    @Test
    void domainLongerThan255BytesIsRejected() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String tooLong = "a".repeat(256);
        assertThatThrownBy(() -> Socks5HttpClient.writeAddress(buffer, tooLong, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("域名过长");
    }

    // ======================== 状态行与应答码 ========================

    @Test
    void parsesStatusFromResponseHeader() {
        assertThat(Socks5HttpClient.parseStatus("HTTP/1.1 204 No Content")).isEqualTo(204);
        assertThat(Socks5HttpClient.parseStatus("HTTP/1.1 200 OK")).isEqualTo(200);
        assertThat(Socks5HttpClient.parseStatus("HTTP/1.0 403 Forbidden")).isEqualTo(403);
        assertThat(Socks5HttpClient.parseStatus("garbage")).isEqualTo(-1);
    }

    @Test
    void mapsSocksReplyCodesToReadableMessages() {
        assertThat(Socks5HttpClient.replyMessage(0x02)).isEqualTo("connection not allowed");
        assertThat(Socks5HttpClient.replyMessage(0x05)).isEqualTo("connection refused");
        assertThat(Socks5HttpClient.replyMessage(0x08)).isEqualTo("address type not supported");
        assertThat(Socks5HttpClient.replyMessage(0x42)).contains("code 66");
    }

    // ======================== 代理必须被真正使用 ========================

    /**
     * 关键回归：代理指向死端口时请求必须失败。
     *
     * <p>如果这里"成功"，说明请求绕过了代理直达目标——正是 JDK HttpClient
     * 配置 SOCKS 代理时的真实行为，会让所有节点测试失去意义。</p>
     */
    @Test
    void requestThroughDeadProxyPortMustFail() {
        URI target = URI.create("http://cp.cloudflare.com/generate_204");
        assertThatThrownBy(() -> Socks5HttpClient.get(1, target, 2000, 2000, 4096))
                .isInstanceOf(IOException.class);
    }

    @Test
    void nonSocksPortIsRejectedRatherThanSilentlyIgnored() {
        // 指向一个非 SOCKS 服务（此处用不可能监听的端口），必须抛错而不是回退直连
        assertThatThrownBy(() -> Socks5HttpClient.connectViaSocks(2, "example.com", 80, 1500))
                .isInstanceOf(IOException.class);
    }

    // ======================== HTTP 解析 ========================

    @Test
    void readsStatusAndBodyFromPlainResponse() throws IOException {
        String raw = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
        Socks5HttpClient.Response response = readViaReflection(raw, 4096);
        assertThat(response.status()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void decodesChunkedBody() throws IOException {
        String raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        Socks5HttpClient.Response response = readViaReflection(raw, 4096);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyText()).isEqualTo("hello world");
    }

    @Test
    void readsBodyByContentLength() throws IOException {
        String raw = "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nhello world";
        Socks5HttpClient.Response response = readViaReflection(raw, 4096);
        assertThat(response.bodyText()).isEqualTo("hello world");
    }

    @Test
    void emptyResponseIsAnError() {
        assertThatThrownBy(() -> readViaReflection("", 4096))
                .isInstanceOf(IOException.class);
    }

    /** 通过公开入口解析一段构造好的响应：借用 get() 的解析逻辑需要 socket，这里用反射调私有方法 */
    private static Socks5HttpClient.Response readViaReflection(String raw, int maxBytes)
            throws IOException {
        try {
            var method = Socks5HttpClient.class.getDeclaredMethod(
                    "readResponse", InputStream.class, int.class);
            method.setAccessible(true);
            InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1));
            return (Socks5HttpClient.Response) method.invoke(null, in, maxBytes);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IOException("无法调用 readResponse", e);
        }
    }
}
