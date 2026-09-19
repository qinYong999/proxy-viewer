package com.proxyviewer.service.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * 直接实现 SOCKS5 的 HTTP 客户端。
 *
 * <p><b>为什么不用 {@code java.net.http.HttpClient}：</b>JDK 的 HttpClient
 * <b>不支持 SOCKS 代理</b>——它只实现 HTTP 代理的 CONNECT 隧道。给它配置
 * {@code Proxy.Type.SOCKS}（无论走 ProxySelector 还是 socksProxyHost 系统属性）
 * 都会被<b>静默忽略</b>，请求直接走直连。这会让"经代理测延迟"变成"测本机直连"，
 * 从而把任何节点都误判为可用。此处实测确认过该行为。</p>
 *
 * <p>因此这里手工完成 SOCKS5 握手与 HTTP/1.1 收发。好处是完全可控：
 * 能精确计时、能区分失败原因、能复用同一套握手做 UDP ASSOCIATE。</p>
 *
 * <p><b>主机名解析：</b>默认优先把域名在本地解析成 IP 再交给代理（省一次代理侧 DNS）。
 * 但本机 DNS 常被污染，订阅抓取这类场景必须让代理远端解析，
 * 此时用 {@code remoteDns=true}，报文里发域名（ATYP=0x03）。</p>
 *
 * <p><b>HTTPS：</b>{@link #get} 对 https 目标会在 SOCKS5 隧道上再套一层 TLS，
 * SNI 使用原始域名，因此同样能穿过被污染的本地 DNS。</p>
 */
public final class Socks5HttpClient {

    private static final Logger log = LoggerFactory.getLogger(Socks5HttpClient.class);

    private static final int MAX_HEADER_BYTES = 32 * 1024;
    /** 单次请求的响应体上限（仅用于非流式读取，防异常大响应打爆内存） */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    /** 默认信任库的 TLS 上下文，进程内复用（初始化一次即可） */
    private static volatile SSLContext tlsContext;

    private Socks5HttpClient() {
    }

    /** HTTP 响应 */
    public record Response(int status, String headers, byte[] body, long bodyBytes) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** 经 SOCKS5 代理获取 URL，读到响应体结束（受 maxBytes 与读超时限制） */
    public static Response get(int socksPort, URI uri, int connectTimeoutMs, int readTimeoutMs,
                               int maxBytes) throws IOException {
        return get(socksPort, uri, connectTimeoutMs, readTimeoutMs, maxBytes, false);
    }

    /**
     * 经 SOCKS5 代理获取 URL。
     *
     * @param remoteDns true 表示主机名一律由代理侧解析（本地 DNS 被污染时必须为 true）
     */
    public static Response get(int socksPort, URI uri, int connectTimeoutMs, int readTimeoutMs,
                               int maxBytes, boolean remoteDns) throws IOException {
        return get("127.0.0.1", socksPort, uri, connectTimeoutMs, readTimeoutMs, maxBytes, remoteDns);
    }

    /** 经指定的 SOCKS5 代理获取 URL；https 目标会在隧道上再套 TLS */
    public static Response get(String proxyHost, int proxyPort, URI uri, int connectTimeoutMs,
                               int readTimeoutMs, int maxBytes, boolean remoteDns)
            throws IOException {
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }

        try (Socket socket = open(proxyHost, proxyPort, uri, port, connectTimeoutMs, remoteDns)) {
            socket.setSoTimeout(readTimeoutMs);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader(uri) + "\r\n"
                    + "User-Agent: Mozilla/5.0\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: gzip, deflate\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readResponse(socket.getInputStream(), maxBytes);
        }
    }

    /** 建立到目标的连接：先做 SOCKS5 握手，https 目标再在其上套一层 TLS */
    private static Socket open(String proxyHost, int proxyPort, URI uri, int port,
                               int connectTimeoutMs, boolean remoteDns) throws IOException {
        Socket socket = connectViaSocks(proxyHost, proxyPort, uri.getHost(), port,
                connectTimeoutMs, remoteDns);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return socket;
        }
        try {
            return wrapTls(socket, uri.getHost(), port, connectTimeoutMs);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * 给已建立的隧道套上 TLS。
     *
     * <p>SNI 必须用原始域名：代理侧解析出的 IP 只有配合正确 SNI 才能拿到对的证书。
     * 目标本身就是 IP 字面量时不发 SNI（{@code SNIHostName} 不接受 IP）。</p>
     */
    private static Socket wrapTls(Socket plain, String host, int port, int timeoutMs)
            throws IOException {
        try {
            SSLSocket ssl = (SSLSocket) tlsContext().getSocketFactory()
                    .createSocket(plain, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            if (!isIpLiteral(host)) {
                params.setServerNames(List.of(new SNIHostName(host)));
            }
            ssl.setSSLParameters(params);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            return ssl;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS 握手失败: " + e.getMessage(), e);
        }
    }

    private static SSLContext tlsContext() throws Exception {
        SSLContext ctx = tlsContext;
        if (ctx == null) {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            tlsContext = ctx;
        }
        return ctx;
    }

    /** 粗判 IP 字面量（SNI 只允许真正的主机名） */
    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true;   // IPv6
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && !Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    /**
     * 流式下载：边读边统计字节数，直到读满期望字节数、连接结束或超出时限。
     *
     * <p>用于测速——必须避免把整个响应体缓冲进内存。</p>
     *
     * @return 实际读到的字节数
     */
    public static long streamBody(int socksPort, URI uri, int connectTimeoutMs, int readTimeoutMs,
                                  long deadlineNanos, boolean countHeaders) throws IOException {
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        try (Socket socket = connectViaSocks(socksPort, uri.getHost(), port, connectTimeoutMs)) {
            socket.setSoTimeout(readTimeoutMs);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader(uri) + "\r\n"
                    + "User-Agent: Mozilla/5.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            // 先把响应头读掉（不计入测速字节）
            String headerBlock = readHeaderBlock(in);
            if (headerBlock == null || headerBlock.isEmpty()) {
                return 0;
            }
            long total = countHeaders ? headerBlock.length() : 0;
            byte[] buf = new byte[64 * 1024];
            while (System.nanoTime() < deadlineNanos) {
                int read;
                try {
                    read = in.read(buf);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (read == -1) {
                    break;
                }
                total += read;
            }
            return total;
        }
    }

    /** 获取 URL 并返回状态码与响应体（自动处理 chunked 与 gzip/deflate） */
    public static Response getFull(int socksPort, URI uri, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        return get(socksPort, uri, connectTimeoutMs, readTimeoutMs, MAX_BODY_BYTES);
    }

    // ======================== SOCKS5 握手 ========================

    /** 建立经 SOCKS5 代理到目标地址的连接 */
    public static Socket connectViaSocks(int socksPort, String host, int port, int connectTimeoutMs)
            throws IOException {
        return connectViaSocks(socksPort, host, port, connectTimeoutMs, false);
    }

    /**
     * 建立经 SOCKS5 代理到目标地址的连接。
     *
     * @param remoteDns true 时主机名一律以域名形式交给代理侧解析；
     *                  本机 DNS 被污染时本地解析出的 IP 是错的，只有远端解析才连得到真实目标
     */
    public static Socket connectViaSocks(int socksPort, String host, int port, int connectTimeoutMs,
                                         boolean remoteDns) throws IOException {
        return connectViaSocks("127.0.0.1", socksPort, host, port, connectTimeoutMs, remoteDns);
    }

    /** 建立经指定 SOCKS5 代理到目标地址的连接（代理只支持无认证） */
    public static Socket connectViaSocks(String proxyHost, int proxyPort, String host, int port,
                                         int connectTimeoutMs, boolean remoteDns)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
            socket.setSoTimeout(connectTimeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1) 方法协商：只声明"无认证"
            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] greeting = readFully(in, 2);
            if (greeting[0] != 0x05) {
                throw new IOException("SOCKS5 版本不匹配: " + greeting[0]);
            }
            if (greeting[1] != 0x00) {
                throw new IOException("SOCKS5 代理要求认证（方法=" + greeting[1] + "），本实现仅支持无认证");
            }

            // 2) CONNECT 请求
            out.write(buildConnectRequest(host, port, remoteDns));
            out.flush();

            // 3) 应答
            byte[] head = readFully(in, 4);
            if (head[1] != 0x00) {
                throw new IOException("SOCKS5 连接被拒绝: " + replyMessage(head[1]));
            }
            skipBoundAddress(in, head[3]);
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
            throw e;
        }
    }

    /** 建立 SOCKS5 UDP 关联，返回中继地址；控制连接通过 holder 返回以便保持打开 */
    public static InetSocketAddress establishUdpAssociation(int socksPort, int timeoutMs,
                                                            Socket[] controlHolder)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", socksPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] greeting = readFully(in, 2);
            if (greeting[0] != 0x05 || greeting[1] != 0x00) {
                throw new IOException("SOCKS5 认证协商失败");
            }

            // UDP ASSOCIATE，DST.ADDR/DST.PORT 填 0 表示由服务端决定中继地址
            out.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            out.flush();
            byte[] head = readFully(in, 4);
            if (head[1] != 0x00) {
                throw new IOException("SOCKS5 UDP ASSOCIATE 被拒绝: " + replyMessage(head[1]));
            }
            InetSocketAddress relay = readBoundAddress(in, head[3]);
            if (controlHolder != null && controlHolder.length > 0) {
                // 控制连接必须保持打开，否则服务端会关闭 UDP 关联
                controlHolder[0] = socket;
            }
            return relay;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
            throw e;
        }
    }

    static byte[] buildConnectRequest(String host, int port) {
        return buildConnectRequest(host, port, false);
    }

    static byte[] buildConnectRequest(String host, int port, boolean remoteDns) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(0x05);
        buffer.write(0x01);   // CONNECT
        buffer.write(0x00);   // RSV
        writeAddress(buffer, host, remoteDns);
        buffer.write((port >> 8) & 0xFF);
        buffer.write(port & 0xFF);
        return buffer.toByteArray();
    }

    /** 写入 SOCKS5 地址字段：能解析成 IP 就发 IP，否则发域名由代理侧解析（UDP 帧也复用此格式） */
    public static void writeAddress(ByteArrayOutputStream buffer, String host) {
        writeAddress(buffer, host, false);
    }

    /** 写入 SOCKS5 地址字段；remoteDns=true 时始终发域名，交给代理侧解析 */
    public static void writeAddress(ByteArrayOutputStream buffer, String host, boolean remoteDns) {
        if (remoteDns) {
            writeDomainAddress(buffer, host);
            return;
        }
        try {
            byte[] raw = InetAddress.getByName(host).getAddress();
            if (raw.length == 4) {
                buffer.write(0x01);
            } else {
                buffer.write(0x04);
            }
            buffer.write(raw, 0, raw.length);
        } catch (Exception e) {
            writeDomainAddress(buffer, host);
        }
    }

    private static void writeDomainAddress(ByteArrayOutputStream buffer, String host) {
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255) {
            throw new IllegalArgumentException("SOCKS5 域名过长（" + name.length + " 字节）: " + host);
        }
        buffer.write(0x03);
        buffer.write(name.length);
        buffer.write(name, 0, name.length);
    }

    private static void skipBoundAddress(InputStream in, int atyp) throws IOException {
        readBoundAddress(in, atyp);
    }

    private static InetSocketAddress readBoundAddress(InputStream in, int atyp) throws IOException {
        switch (atyp) {
            case 0x01 -> {
                byte[] addr = readFully(in, 4);
                byte[] portBytes = readFully(in, 2);
                return new InetSocketAddress(InetAddress.getByAddress(addr),
                        ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF));
            }
            case 0x03 -> {
                int len = readFully(in, 1)[0] & 0xFF;
                String host = new String(readFully(in, len), StandardCharsets.UTF_8);
                byte[] portBytes = readFully(in, 2);
                return InetSocketAddress.createUnresolved(host,
                        ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF));
            }
            case 0x04 -> {
                byte[] addr = readFully(in, 16);
                byte[] portBytes = readFully(in, 2);
                return new InetSocketAddress(InetAddress.getByAddress(addr),
                        ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF));
            }
            default -> throw new IOException("SOCKS5 应答地址类型非法: " + atyp);
        }
    }

    static String replyMessage(int code) {
        return switch (code) {
            case 0x01 -> "general failure";
            case 0x02 -> "connection not allowed";
            case 0x03 -> "network unreachable";
            case 0x04 -> "host unreachable";
            case 0x05 -> "connection refused";
            case 0x06 -> "TTL expired";
            case 0x07 -> "command not supported";
            case 0x08 -> "address type not supported";
            default -> "code " + code;
        };
    }

    // ======================== HTTP 解析 ========================

    private static Response readResponse(InputStream in, int maxBytes) throws IOException {
        String headerBlock = readHeaderBlock(in);
        if (headerBlock == null || headerBlock.isEmpty()) {
            throw new IOException("对端在返回响应前关闭了连接");
        }
        int status = parseStatus(headerBlock);
        if (status < 0) {
            // 状态行不可解析：通常说明对端不是 HTTP（例如被中间设备插入了内容）
            String preview = headerBlock.length() > 60 ? headerBlock.substring(0, 60) : headerBlock;
            throw new IOException("响应状态行无法解析: " + preview.replaceAll("\\s+", " "));
        }
        String lower = headerBlock.toLowerCase();

        byte[] raw = readBody(in, lower, maxBytes);
        byte[] body = decodeBody(raw, lower);
        return new Response(status, headerBlock, body, body.length);
    }

    /** 读到 \r\n\r\n 为止的响应头；连头部都没有时返回 null */
    private static String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            // 识别 \r\n\r\n
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
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw new IOException("响应头超过上限");
            }
        }
        String text = buffer.toString(StandardCharsets.ISO_8859_1);
        if (text.isEmpty()) {
            return null;
        }
        int split = text.indexOf("\r\n\r\n");
        return split >= 0 ? text.substring(0, split) : text;
    }

    static int parseStatus(String headerBlock) {
        int idx = headerBlock.indexOf(' ');
        if (idx < 0) {
            return -1;
        }
        int end = idx + 1;
        while (end < headerBlock.length() && Character.isDigit(headerBlock.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(headerBlock.substring(idx + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] readBody(InputStream in, String lowerHeaders, int maxBytes)
            throws IOException {
        boolean chunked = lowerHeaders.contains("transfer-encoding: chunked");
        boolean closeDelimited = lowerHeaders.contains("connection: close") || chunked;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (chunked) {
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) {
                    break;
                }
                int semi = sizeLine.indexOf(';');
                String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
                int size;
                try {
                    size = Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    break;
                }
                if (size <= 0) {
                    break;
                }
                readInto(in, out, size, maxBytes);
                readLine(in);   // 块尾 CRLF
            }
        } else {
            // 无 Content-Length 时读到 EOF；有则按长度读
            int contentLength = parseContentLength(lowerHeaders);
            if (contentLength >= 0) {
                readInto(in, out, Math.min(contentLength, maxBytes), maxBytes);
            } else if (closeDelimited) {
                byte[] buf = new byte[8192];
                int read;
                try {
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        if (out.size() >= maxBytes) {
                            break;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // 读超时：使用已读内容
                }
            }
        }
        return out.toByteArray();
    }

    private static void readInto(InputStream in, ByteArrayOutputStream out, int count, int maxBytes)
            throws IOException {
        byte[] buf = new byte[8192];
        int remaining = count;
        while (remaining > 0) {
            int read = in.read(buf, 0, Math.min(buf.length, remaining));
            if (read == -1) {
                return;
            }
            out.write(buf, 0, read);
            remaining -= read;
            if (out.size() >= maxBytes) {
                return;
            }
        }
    }

    private static int parseContentLength(String lowerHeaders) {
        for (String line : lowerHeaders.split("\r\n")) {
            if (line.startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        return buffer.size() == 0 && b == -1 ? null
                : buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] decodeBody(byte[] raw, String lowerHeaders) {
        if (raw.length == 0) {
            return raw;
        }
        try {
            if (lowerHeaders.contains("content-encoding: gzip")) {
                return readAllQuietly(new GZIPInputStream(new java.io.ByteArrayInputStream(raw)));
            }
            if (lowerHeaders.contains("content-encoding: deflate")) {
                return readAllQuietly(new InflaterInputStream(new java.io.ByteArrayInputStream(raw)));
            }
        } catch (Exception e) {
            log.debug("解压响应体失败，返回原始字节: {}", e.getMessage());
        }
        return raw;
    }

    private static byte[] readAllQuietly(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = stream.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = in.read(buf, offset, count - offset);
            if (read == -1) {
                throw new IOException("SOCKS5 握手期间连接被对端关闭");
            }
            offset += read;
        }
        return buf;
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        if (port <= 0 || port == defaultPort(uri.getScheme())) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + port;
    }
}
