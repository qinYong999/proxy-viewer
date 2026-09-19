package com.proxyviewer.service.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.ProxyNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 代理内核（Xray）的配置生成与进程托管。
 *
 * <p>这是节点测试重构的地基。旧实现直接对节点服务器端口发 HTTP/WS 握手请求，
 * 只能证明"端口开着"，无法证明"这个代理能用"——大量已被阻断的节点会被判为可达。
 * 新实现改为：<b>为节点生成真实的内核配置，启动内核，通过内核的本地 SOCKS 入站
 * 真实访问外网</b>，与 v2rayN 的 Realping / Speedtest 同源。</p>
 *
 * <p>内核形态采用「每节点独立内核」（v2rayN 的测速路径也是这么做的）：
 * 避免节点间互相干扰，且单节点失败不会影响整批。内核就绪判定使用
 * <b>真实的 SOCKS5 握手</b>（{@code 05 01 00} → 期望首字节 {@code 0x05}），
 * 而不是只探 TCP 端口——端口可能恰被别的服务占用而产生"假就绪"。</p>
 */
@Service
public class XrayCoreService {

    private static final Logger log = LoggerFactory.getLogger(XrayCoreService.class);

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong configSeq = new AtomicLong();
    private final AtomicInteger processSeq = new AtomicInteger();

    /** 内核可执行文件路径（null 表示未找到） */
    private volatile String corePath;
    /** 内核资源目录（geoip.dat / geosite.dat 所在处） */
    private volatile String assetDir;
    /** "未找到内核"的告警只打印一次，避免每次测试刷屏 */
    private volatile boolean warnedNotFound;

    /** 当前存活的内核进程，用于应用关闭时兜底清理 */
    private final List<Process> liveProcesses = new ArrayList<>();

    public XrayCoreService(AppProperties props) {
        this.props = props;
    }

    // ======================== 内核定位 ========================

    /** 内核是否可用；不可用时页面会给出明确的配置提示，而不是把节点全判失败 */
    public boolean isCoreAvailable() {
        return resolveCorePath() != null;
    }

    public String getCorePath() {
        return resolveCorePath();
    }

    public String getAssetDir() {
        resolveCorePath();
        return assetDir;
    }

    /**
     * 定位内核可执行文件。
     *
     * <p>这里<b>不缓存"未找到"的结果</b>：配置可能在运行期才补上（例如先启动应用、
     * 之后才安装内核，或测试在不同阶段设置内核位置）。若把失败结果锁死，就会出现
     * "配置已经对了却依然报找不到内核"的假故障。只有探测成功时才记住结果。</p>
     */
    private synchronized String resolveCorePath() {
        String exeName = isWindows() ? "xray.exe" : "xray";
        List<String> candidates = new ArrayList<>();

        AppProperties.Test cfg = props.getTest();
        if (notBlank(cfg.getCorePath())) {
            candidates.add(cfg.getCorePath().trim());
        }
        // 系统属性便于测试与临时覆盖：-Dpv.xray.dir=...
        String sysDir = System.getProperty("pv.xray.dir", "");
        for (String dir : new String[]{cfg.getCoreDir(), sysDir}) {
            if (!notBlank(dir)) {
                continue;
            }
            String d = dir.trim();
            candidates.add(d + java.io.File.separator + exeName);
            // v2rayN 便携版把内核放在 bin/xray/ 下
            candidates.add(d + java.io.File.separator + "xray" + java.io.File.separator + exeName);
            candidates.add(d + java.io.File.separator + "bin" + java.io.File.separator
                    + "xray" + java.io.File.separator + exeName);
        }
        // v2rayN 常见安装位置。自动探测只为省去手工配置；找不到时页面会提示如何显式配置。
        String userHome = System.getProperty("user.home", "");
        List<String> bases = new ArrayList<>();
        bases.add(userHome + "\\Downloads");
        bases.add(userHome + "\\Desktop");
        bases.add("D:\\Users\\" + userName() + "\\Downloads");
        for (String drive : new String[]{"C:", "D:", "E:", "F:", "S:"}) {
            bases.add(drive + "\\installationFree");
            bases.add(drive + "\\Program Files");
            bases.add(drive);
        }
        for (String base : bases) {
            candidates.add(base + "\\v2rayN-windows-64\\bin\\xray\\" + exeName);
            candidates.add(base + "\\v2rayN-windows-64\\bin\\" + exeName);
        }
        // 便携版解压后目录名常带后缀（如 "v2rayN-windows-64 (1)"），按名字模糊找一层
        for (String base : bases) {
            Path basePath = Path.of(base);
            if (!Files.isDirectory(basePath)) {
                continue;
            }
            try (var children = Files.list(basePath)) {
                children.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                                .startsWith("v2rayn"))
                        .limit(5)
                        .forEach(p -> {
                            candidates.add(p + "\\bin\\xray\\" + exeName);
                            candidates.add(p + "\\bin\\" + exeName);
                        });
            } catch (Exception ignored) {
                // 目录不可读就跳过
            }
        }

        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                String found = p.toAbsolutePath().toString();
                if (!found.equals(corePath)) {
                    log.info("已定位代理内核: {}（资源目录 {}）", found, resolveAssetDir(found));
                }
                corePath = found;
                assetDir = resolveAssetDir(found);
                return corePath;
            }
        }

        // PATH 查找
        String onPath = findOnPath(exeName);
        if (onPath != null) {
            if (!onPath.equals(corePath)) {
                log.info("已从 PATH 定位代理内核: {}", onPath);
            }
            corePath = onPath;
            assetDir = resolveAssetDir(onPath);
            return corePath;
        }

        corePath = null;
        if (!warnedNotFound) {
            warnedNotFound = true;
            log.warn("未找到代理内核（{}）。节点测试无法进行真实代理验证，"
                    + "请在 app.test.core-path 或 app.test.core-dir 中指定内核位置", exeName);
        }
        return null;
    }

    private static String userName() {
        String n = System.getProperty("user.name", "");
        return n.isBlank() ? "Users" : n;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 资源目录：优先取显式配置，否则用内核所在目录（v2rayN 把 geoip.dat 放在 bin/ 下） */
    private String resolveAssetDir(String exePath) {
        if (notBlank(props.getTest().getCoreAssetDir())) {
            Path p = Path.of(props.getTest().getCoreAssetDir().trim());
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        Path exe = Path.of(exePath).toAbsolutePath();
        Path dir = exe.getParent();
        if (dir == null) {
            return null;
        }
        // 内核在 .../bin/xray/ 下时，资源通常在上一级 .../bin/
        if (Files.exists(dir.resolve("geoip.dat"))) {
            return dir.toString();
        }
        Path parent = dir.getParent();
        if (parent != null && Files.exists(parent.resolve("geoip.dat"))) {
            return parent.toString();
        }
        return dir.toString();
    }

    private static String findOnPath(String exeName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(dir, exeName);
                if (Files.isRegularFile(p)) {
                    return p.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {
                // 非法 PATH 项直接跳过
            }
        }
        return null;
    }

    // ======================== 配置生成 ========================

    /** 生成配置的结果：本地 SOCKS 入站端口 + 节点出站标签 */
    public record GeneratedConfig(int socksPort, String outboundTag, Path file) {
    }

    /**
     * 为一个节点生成可运行的内核配置（单入站 + 单出站 + 直连兜底）。
     *
     * @throws UnsupportedNodeException 节点参数不足以生成内核配置
     */
    public GeneratedConfig generate(ProxyNode node, int socksPort) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode logNode = root.putObject("log");
        logNode.put("loglevel", props.getTest().getCoreLogLevel());
        logNode.put("access", "");

        ArrayNode inbounds = root.putArray("inbounds");
        ObjectNode inbound = inbounds.addObject();
        inbound.put("tag", "socks-in");
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", socksPort);
        inbound.put("protocol", "socks");
        ObjectNode inSettings = inbound.putObject("settings");
        inSettings.put("auth", "noauth");
        inSettings.put("udp", true);   // UDP 测试需要 UDP ASSOCIATE 支持
        inSettings.putArray("accounts");

        ArrayNode outbounds = root.putArray("outbounds");
        ObjectNode outbound = buildOutbound(node);
        outbounds.add(outbound);
        outbounds.addObject().put("tag", "direct").put("protocol", "freedom").putObject("settings");

        ObjectNode routing = root.putObject("routing");
        // AsIs：出站直接使用入站送来的目标域名，由节点远端解析，绕开本地被污染的 DNS
        routing.put("domainStrategy", "AsIs");
        ArrayNode rules = routing.putArray("rules");
        ObjectNode rule = rules.addObject();
        rule.put("type", "field");
        rule.putArray("inboundTag").add("socks-in");
        rule.put("outboundTag", (String) outbound.get("tag").asText());

        Path dir = configDir();
        Files.createDirectories(dir);
        Path file = dir.resolve("pv-test-" + configSeq.incrementAndGet() + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return new GeneratedConfig(socksPort, outbound.get("tag").asText(), file);
    }

    private Path configDir() throws IOException {
        // 放在系统临时目录，避免污染仓库；应用退出时清理
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "proxy-viewer-core");
        Files.createDirectories(dir);
        return dir;
    }

    /** 节点参数不足，无法生成内核配置 */
    public static class UnsupportedNodeException extends RuntimeException {
        public UnsupportedNodeException(String message) {
            super(message);
        }
    }

    /** 把节点实体翻译成 Xray 出站配置 */
    ObjectNode buildOutbound(ProxyNode node) {
        String protocol = lower(node.getProtocol());
        if (!"vless".equals(protocol) && !"vmess".equals(protocol)) {
            throw new UnsupportedNodeException("UNSUPPORTED_PROTOCOL:" + node.getProtocol());
        }
        if (notBlank(node.getServer()) == false || node.getPort() <= 0) {
            throw new UnsupportedNodeException("BAD_ADDRESS");
        }
        if (notBlank(node.getUuid()) == false) {
            throw new UnsupportedNodeException("MISSING_UUID");
        }

        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("tag", "proxy");
        outbound.put("protocol", protocol);

        ArrayNode vnext = outbound.putObject("settings").putArray("vnext");
        ObjectNode server = vnext.addObject();
        server.put("address", node.getServer());
        server.put("port", node.getPort());
        ArrayNode users = server.putArray("users");
        ObjectNode user = users.addObject();
        user.put("id", node.getUuid());

        if ("vless".equals(protocol)) {
            user.put("encryption", notBlank(node.getEncryption()) ? node.getEncryption().trim() : "none");
            if (notBlank(node.getFlow())) {
                user.put("flow", node.getFlow().trim());
            }
        } else {
            user.put("alterId", Math.max(node.getAid(), 0));
            user.put("security", "auto");
        }

        ObjectNode stream = buildStreamSettings(node, protocol);
        outbound.set("streamSettings", stream);
        return outbound;
    }

    private ObjectNode buildStreamSettings(ProxyNode node, String protocol) {
        ObjectNode stream = mapper.createObjectNode();
        String network = notBlank(node.getNetwork()) ? node.getNetwork().trim().toLowerCase(Locale.ROOT) : "tcp";
        stream.put("network", network);

        String security = resolveSecurity(node);
        boolean reality = "reality".equals(security);
        if (!"none".equals(security)) {
            stream.put("security", security);
            stream.set("tlsSettings", buildTlsSettings(node, reality));
        }

        switch (network) {
            case "ws" -> {
                ObjectNode ws = stream.putObject("wsSettings");
                ws.put("path", notBlank(node.getPath()) ? node.getPath().trim() : "/");
                if (notBlank(node.getHost())) {
                    // Xray 26 起 host 从 headers 提升为独立字段（旧写法已弃用）
                    ws.put("host", node.getHost().trim());
                }
            }
            case "grpc" -> {
                ObjectNode grpc = stream.putObject("grpcSettings");
                String sn = notBlank(node.getServiceName()) ? node.getServiceName().trim()
                        : (notBlank(node.getPath()) ? node.getPath().trim() : "");
                grpc.put("serviceName", sn);
                grpc.put("multiMode", false);
            }
            case "httpupgrade" -> {
                ObjectNode hu = stream.putObject("httpupgradeSettings");
                hu.put("path", notBlank(node.getPath()) ? node.getPath().trim() : "/");
                if (notBlank(node.getHost())) {
                    hu.put("host", node.getHost().trim());
                }
            }
            case "xhttp" -> {
                ObjectNode xhttp = stream.putObject("xhttpSettings");
                xhttp.put("path", notBlank(node.getPath()) ? node.getPath().trim() : "/");
                xhttp.put("mode", notBlank(node.getServiceName()) ? node.getServiceName().trim() : "auto");
                if (notBlank(node.getHost())) {
                    xhttp.put("host", node.getHost().trim());
                }
            }
            case "tcp" -> {
                // TCP + HTTP 伪装头
                if ("http".equalsIgnoreCase(node.getHeaderType())) {
                    ObjectNode tcp = stream.putObject("tcpSettings");
                    ObjectNode header = tcp.putObject("header");
                    header.put("type", "http");
                    ObjectNode request = header.putObject("request");
                    request.putArray("path").add(notBlank(node.getPath()) ? node.getPath().trim() : "/");
                    ObjectNode headers = request.putObject("headers");
                    headers.putArray("Host").add(notBlank(node.getHost()) ? node.getHost().trim() : node.getServer());
                }
            }
            default -> {
                // kcp / quic 等暂不支持：内核自会报错，这里不阻断，让测试给出真实结论
            }
        }
        return stream;
    }

    /** 归一化安全类型：vless 用 security 参数，vmess 用 tls 字段 */
    private String resolveSecurity(ProxyNode node) {
        String security = lower(node.getSecurity());
        String tls = lower(node.getTls());
        if ("reality".equals(security) || "reality".equals(tls)) {
            return "reality";
        }
        if ("tls".equals(security) || "tls".equals(tls) || "xtls".equals(security)) {
            return "tls";
        }
        return "none";
    }

    private ObjectNode buildTlsSettings(ProxyNode node, boolean reality) {
        ObjectNode tls = mapper.createObjectNode();
        String serverName = notBlank(node.getSni()) ? node.getSni().trim()
                : (notBlank(node.getHost()) ? node.getHost().trim() : node.getServer());
        tls.put("serverName", serverName);
        tls.put("allowInsecure", node.isSkipCertVerify());
        if (notBlank(node.getFp())) {
            tls.put("fingerprint", node.getFp().trim());
        }
        if (notBlank(node.getAlpn())) {
            ArrayNode alpn = tls.putArray("alpn");
            for (String a : node.getAlpn().split(",")) {
                if (!a.isBlank()) {
                    alpn.add(a.trim());
                }
            }
        }
        if (reality) {
            if (notBlank(node.getPublicKey()) == false) {
                // 缺公钥的 Reality 节点无法建立连接，明确报错而不是让内核神秘失败
                throw new UnsupportedNodeException("REALITY_MISSING_PUBLIC_KEY");
            }
            ObjectNode rs = tls.putObject("realitySettings");
            rs.put("show", false);
            rs.put("publicKey", node.getPublicKey().trim());
            rs.put("shortId", notBlank(node.getShortId()) ? node.getShortId().trim() : "");
            rs.put("spiderX", notBlank(node.getSpiderX()) ? node.getSpiderX().trim() : "/");
            // realitySettings 必须带 serverName/fingerprint，Xray 才认
            rs.put("serverName", serverName);
            if (notBlank(node.getFp())) {
                rs.put("fingerprint", node.getFp().trim());
            }
        }
        return tls;
    }

    // ======================== 进程生命周期 ========================

    /** 一个正在运行的内核实例；务必在 finally 中 {@link #close()}，否则会泄漏进程 */
    public final class CoreProcess implements AutoCloseable {
        private final Process process;
        private final Path configFile;
        private final int socksPort;

        private CoreProcess(Process process, Path configFile, int socksPort) {
            this.process = process;
            this.configFile = configFile;
            this.socksPort = socksPort;
        }

        public int socksPort() {
            return socksPort;
        }

        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            try {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(1500, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } catch (Exception e) {
                log.debug("关闭内核进程异常: {}", e.getMessage());
            } finally {
                synchronized (liveProcesses) {
                    liveProcesses.remove(process);
                }
                try {
                    Files.deleteIfExists(configFile);
                } catch (IOException e) {
                    log.debug("删除临时内核配置失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 启动一个节点内核，等待 SOCKS 端口真正就绪。
     *
     * @return 就绪的内核实例；启动失败返回 null，失败原因写入 {@code failureReason}
     */
    public CoreProcess start(ProxyNode node, int socksPort) {
        String exe = resolveCorePath();
        if (exe == null) {
            lastFailure = "未找到代理内核，请配置 app.test.core-path";
            return null;
        }
        Path cfgFile = null;
        try {
            GeneratedConfig generated = generate(node, socksPort);
            cfgFile = generated.file();

            ProcessBuilder pb = new ProcessBuilder(exe, "run", "-c", cfgFile.toString());
            pb.directory(cfgFile.getParent().toFile());
            pb.redirectErrorStream(true);
            if (notBlank(getAssetDir())) {
                pb.environment().put("XRAY_LOCATION_ASSET", getAssetDir());
            }
            Process process = pb.start();
            synchronized (liveProcesses) {
                liveProcesses.add(process);
            }

            long deadline = System.currentTimeMillis() + props.getTest().getCoreStartupTimeoutMs();
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    // 进程已退出：配置有误或端口被占用，内核自身会说明原因
                    lastFailure = "内核启动失败: " + readProcessOutput(process);
                    Files.deleteIfExists(cfgFile);
                    synchronized (liveProcesses) {
                        liveProcesses.remove(process);
                    }
                    return null;
                }
                if (socksHandshakeOk(socksPort)) {
                    return new CoreProcess(process, cfgFile, socksPort);
                }
                Thread.sleep(25);
            }
            // 进程还活着但 SOCKS 入站没就绪：多为端口被占用，或内核启动异常缓慢
            lastFailure = process.isAlive()
                    ? "内核已启动但 SOCKS 端口 " + socksPort + " 未就绪（"
                      + props.getTest().getCoreStartupTimeoutMs() + "ms 超时，可能端口被占用）"
                    : "内核启动失败: " + readProcessOutput(process);
            process.destroyForcibly();
            Files.deleteIfExists(cfgFile);
            synchronized (liveProcesses) {
                liveProcesses.remove(process);
            }
            return null;
        } catch (UnsupportedNodeException e) {
            lastFailure = e.getMessage();
            cleanupQuietly(cfgFile);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailure = "启动被中断";
            cleanupQuietly(cfgFile);
            return null;
        } catch (Exception e) {
            lastFailure = "启动内核异常: " + shortMessage(e);
            cleanupQuietly(cfgFile);
            return null;
        }
    }

    private volatile String lastFailure = "";

    /** 最近一次 {@link #start} 的失败原因（供页面展示具体原因） */
    public String getLastFailure() {
        return lastFailure;
    }

    private static void cleanupQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /**
     * 真实就绪判定：完成 SOCKS5 无认证握手并校验首字节为 {@code 0x05}。
     * 只探 TCP 是不够的，端口可能被别的服务占用而给出假就绪。
     */
    private static boolean socksHandshakeOk(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            socket.setSoTimeout(300);
            socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
            socket.getOutputStream().flush();
            byte[] resp = socket.getInputStream().readNBytes(2);
            return resp.length == 2 && resp[0] == 0x05;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取内核输出用于诊断（内核异常退出后调用，stdout 已 EOF） */
    private static String readProcessOutput(Process process) {
        try (InputStream in = process.getInputStream()) {
            byte[] buf = in.readNBytes(600);
            String text = new String(buf, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "(无输出)" : text.replaceAll("\\s+", " ");
        } catch (Exception e) {
            return "(读取内核输出失败)";
        }
    }

    public static String shortMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    /** 应用关闭时兜底清理所有存活内核，避免留下孤儿进程 */
    @PreDestroy
    public void shutdown() {
        List<Process> copy;
        synchronized (liveProcesses) {
            copy = new ArrayList<>(liveProcesses);
            liveProcesses.clear();
        }
        for (Process p : copy) {
            try {
                if (p.isAlive()) {
                    p.destroy();
                }
            } catch (Exception ignored) {
                // 忽略
            }
        }
        copy.stream().filter(Process::isAlive).forEach(Process::destroyForcibly);
        // 清理临时配置
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "proxy-viewer-core");
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().startsWith("pv-test-"))
                            .sorted(Comparator.naturalOrder())
                            .forEach(XrayCoreService::cleanupQuietly);
                }
            }
        } catch (Exception e) {
            log.debug("清理临时内核配置目录失败: {}", e.getMessage());
        }
        if (!copy.isEmpty()) {
            log.info("已清理 {} 个存活的内核进程", copy.size());
        }
    }

    /** 供测试与诊断：读取内核版本 */
    public String version() {
        String exe = resolveCorePath();
        if (exe == null) {
            return "(未找到内核)";
        }
        try {
            Process p = new ProcessBuilder(exe, "version").redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return out.lines().findFirst().orElse("(无版本输出)").trim();
        } catch (Exception e) {
            return "(读取版本失败: " + shortMessage(e) + ")";
        }
    }

    /** 供测试：解析出的出站 JSON */
    JsonNode outboundJson(ProxyNode node) {
        return buildOutbound(node);
    }
}
