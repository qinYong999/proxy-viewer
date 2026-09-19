package com.proxyviewer.service;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订阅抓取必须真的经过 SOCKS5 代理。
 *
 * <p><b>回归背景：</b>JDK 的 {@code HttpClient} 不支持 SOCKS，把 SOCKS 代理交给它会
 * 被静默忽略、请求退化成直连。订阅域名被墙时，页面表现就是"点击刷新节点失败"，
 * 且错误信息只有一句 connect timeout。</p>
 *
 * <p>这里用一个本地假 SOCKS5 代理验证两件事：请求确实穿过代理，
 * 且目标域名是交给代理侧解析的（本地 DNS 被污染也能连到真实地址）。</p>
 */
class SubscriptionServiceSocks5Test {

    private FakeSocks5Proxy proxy;

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
    }

    @Test
    void refreshFetchesSubscriptionThroughSocks5Proxy() throws Exception {
        String subscription = "vless://11111111-2222-3333-4444-555555555555@example.com:8443"
                + "?type=tcp&security=none#US-01";
        proxy = new FakeSocks5Proxy(Base64.getEncoder()
                .encodeToString(subscription.getBytes(StandardCharsets.UTF_8)));
        proxy.start();

        AppProperties props = propsWithSocksProxy(proxy.port());
        SubscriptionService service = new SubscriptionService(props, emptySyncService());
        // 域名故意不可解析：只有把域名交给代理侧解析才可能成功，退回直连必然失败
        SubscriptionService.RefreshResult result =
                service.refreshNodes("http://sub-only-via-proxy.invalid/sub?token=abc");

        assertThat(result.skipped()).isFalse();
        assertThat(result.nodeCount()).isEqualTo(1);
        assertThat(proxy.requestedHost()).isEqualTo("sub-only-via-proxy.invalid");
        assertThat(proxy.requestedPort()).isEqualTo(80);
        assertThat(proxy.requestLine()).isEqualTo("GET /sub?token=abc HTTP/1.1");
    }

    @Test
    void reportsFailureReasonOfEveryChannel() throws Exception {
        int deadPort = freePort();
        AppProperties props = propsWithSocksProxy(deadPort);
        // 关掉 DoH 回退：本测试只关心"每个通道的失败原因都被说清楚"
        props.getSubscription().setRawSocketFallbackEnabled(false);
        props.getSubscription().setConnectTimeoutSeconds(2);
        props.getSubscription().setRequestTimeoutSeconds(2);

        SubscriptionService service = new SubscriptionService(props, emptySyncService());

        assertThatThrownBy(() -> service.refreshNodes("http://sub-only-via-proxy.invalid/sub"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("订阅抓取失败")
                .hasMessageContaining("socks5://127.0.0.1:" + deadPort)
                .hasMessageContaining("直连(系统DNS)");
    }

    @Test
    void translatesFailuresIntoReadableReasons() {
        assertThat(SubscriptionService.describeFailure(
                new SocketTimeoutException("Connect timed out"))).isEqualTo("连接超时");
        assertThat(SubscriptionService.describeFailure(
                new java.net.ConnectException("Connection refused"))).isEqualTo("连接被拒绝");
        assertThat(SubscriptionService.describeFailure(
                new java.net.UnknownHostException("nope.invalid"))).isEqualTo("DNS 解析失败");
        assertThat(SubscriptionService.describeFailure(new IOException("HTTP 404"))).isEqualTo("HTTP 404");
        assertThat(SubscriptionService.describeFailure(new IOException()))
                .isEqualTo("IOException");
    }

    private static AppProperties propsWithSocksProxy(int port) {
        AppProperties props = new AppProperties();
        props.getSubscription().setProxyCandidates(List.of("socks5://127.0.0.1:" + port));
        return props;
    }

    /** 空库上的真实对账服务：入库结果由 NodeSyncService 自己算，不依赖 mock 框架 */
    private static NodeSyncService emptySyncService() {
        return new NodeSyncService(inMemoryRepository());
    }

    /**
     * 用 JDK 动态代理顶替 Spring Data 仓库。
     *
     * <p>刻意不用 Mockito：JDK 21+ 默认禁止动态挂载 agent，inline mock maker 需要额外的
     * JVM 配置才能初始化，一个抓取回归测试不该依赖那套环境假设。</p>
     */
    private static ProxyNodeRepository inMemoryRepository() {
        return (ProxyNodeRepository) Proxy.newProxyInstance(
                ProxyNodeRepository.class.getClassLoader(),
                new Class<?>[]{ProxyNodeRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAll" -> new ArrayList<ProxyNode>();
                    case "saveAll" -> new ArrayList<>(toList(args[0]));
                    case "deleteAll" -> null;
                    case "toString" -> "InMemoryProxyNodeRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "测试仓库未实现的方法: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static List<ProxyNode> toList(Object iterable) {
        List<ProxyNode> nodes = new ArrayList<>();
        ((Iterable<ProxyNode>) iterable).forEach(nodes::add);
        return nodes;
    }

    /** 占一个端口再立刻释放：得到几乎必然没人监听的端口号 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /**
     * 最小 SOCKS5 代理：完成无认证握手后直接扮演 HTTP 服务端，返回预置的订阅内容。
     *
     * <p>它不是真的转发，因此"抓到数据"就等价于"请求确实走到了代理"。</p>
     */
    private static final class FakeSocks5Proxy implements AutoCloseable {

        private final ServerSocket server;
        private final String body;
        private volatile String requestedHost;
        private volatile int requestedPort;
        private volatile String requestLine;
        private Thread worker;

        FakeSocks5Proxy(String body) throws IOException {
            this.body = body;
            this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        }

        int port() {
            return server.getLocalPort();
        }

        String requestedHost() {
            return requestedHost;
        }

        int requestedPort() {
            return requestedPort;
        }

        String requestLine() {
            return requestLine;
        }

        void start() {
            worker = new Thread(this::serve, "fake-socks5-proxy");
            worker.setDaemon(true);
            worker.start();
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(8000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                // 1) 方法协商：只接受"无认证"
                byte[] greeting = readFully(in, 3);
                if (greeting[0] != 0x05) {
                    return;
                }
                out.write(new byte[]{0x05, 0x00});
                out.flush();

                // 2) CONNECT 请求：记录被请求的目标
                byte[] head = readFully(in, 4);
                int atyp = head[3] & 0xFF;
                if (atyp == 0x03) {
                    int length = readFully(in, 1)[0] & 0xFF;
                    requestedHost = new String(readFully(in, length), StandardCharsets.UTF_8);
                } else if (atyp == 0x01) {
                    requestedHost = InetAddress.getByAddress(readFully(in, 4)).getHostAddress();
                } else {
                    requestedHost = "<atyp " + atyp + ">";
                }
                byte[] portBytes = readFully(in, 2);
                requestedPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

                // 3) 应答成功
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();

                // 4) 直接把后续字节当 HTTP 请求读掉，再返回订阅内容
                requestLine = readHttpRequestLine(in);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "Content-Length: " + payload.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                // 测试结束时的连接中断属正常现象
            }
        }

        /** 读到 \r\n\r\n 为止，返回请求行 */
        private static String readHttpRequestLine(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int matched = 0;
            int b;
            while ((b = in.read()) != -1) {
                buffer.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') {
                    matched++;
                } else if ((matched == 1 || matched == 3) && b == '\n') {
                    matched++;
                    if (matched == 4) {
                        break;
                    }
                } else {
                    matched = (b == '\r') ? 1 : 0;
                }
            }
            String raw = buffer.toString(StandardCharsets.ISO_8859_1);
            int end = raw.indexOf("\r\n");
            return end > 0 ? raw.substring(0, end) : raw;
        }

        private static byte[] readFully(InputStream in, int count) throws IOException {
            byte[] buf = new byte[count];
            int offset = 0;
            while (offset < count) {
                int read = in.read(buf, offset, count - offset);
                if (read == -1) {
                    throw new IOException("连接在握手期间关闭");
                }
                offset += read;
            }
            return buf;
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // 忽略
            }
            if (worker != null) {
                try {
                    worker.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
