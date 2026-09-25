package com.proxyviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用级配置（前缀 {@code app}）。
 *
 * <p>所有敏感信息（订阅链接、数据库口令、访问口令）均支持通过环境变量注入，
 * 见 {@code src/main/resources/application.properties}。</p>
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Security security = new Security();
    private final Subscription subscription = new Subscription();
    private final Test test = new Test();

    public Security getSecurity() { return security; }
    public Subscription getSubscription() { return subscription; }
    public Test getTest() { return test; }

    /** 访问控制：Spring Security 表单登录（见 {@link SecurityConfig}） */
    public static class Security {
        /** 是否启用认证。默认开启，关闭后任何人可访问（仅建议本机调试时关闭） */
        private boolean enabled = true;
        private String username = "admin";
        /** 留空则启动时随机生成并打印到日志；配置里存明文口令，运行期用 bcrypt 编码比对 */
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /** 订阅抓取 */
    public static class Subscription {
        /** 页面未指定订阅链接时使用的默认链接（唯一来源，模板不再硬编码） */
        private String defaultUrl = "";
        /** 是否允许订阅地址指向内网/回环地址（默认禁止，防 SSRF） */
        private boolean allowPrivateHosts = false;
        /** 抓取时依次尝试的代理，格式 scheme://host:port（scheme 支持 socks5/http） */
        private List<String> proxyCandidates = new ArrayList<>(List.of(
                "socks5://127.0.0.1:10808",
                "http://127.0.0.1:10809"));
        /** 所有 HttpClient 都失败时，是否退回 DoH + 裸 SSLSocket 直连 */
        private boolean rawSocketFallbackEnabled = true;
        /** DoH 解析使用的 DNS 服务器（IP:443，走 TLS + SNI） */
        private String dohServerIp = "8.8.8.8";
        private String dohServerHost = "dns.google";
        private int connectTimeoutSeconds = 20;
        private int requestTimeoutSeconds = 30;
        /** 空结果保护：抓取为空时保留旧数据（防订阅临时失效清库） */
        private boolean keepDataOnEmptyResult = true;

        public String getDefaultUrl() { return defaultUrl; }
        public void setDefaultUrl(String defaultUrl) { this.defaultUrl = defaultUrl; }
        public boolean isAllowPrivateHosts() { return allowPrivateHosts; }
        public void setAllowPrivateHosts(boolean allowPrivateHosts) { this.allowPrivateHosts = allowPrivateHosts; }
        public List<String> getProxyCandidates() { return proxyCandidates; }
        public void setProxyCandidates(List<String> proxyCandidates) { this.proxyCandidates = proxyCandidates; }
        public boolean isRawSocketFallbackEnabled() { return rawSocketFallbackEnabled; }
        public void setRawSocketFallbackEnabled(boolean rawSocketFallbackEnabled) { this.rawSocketFallbackEnabled = rawSocketFallbackEnabled; }
        public String getDohServerIp() { return dohServerIp; }
        public void setDohServerIp(String dohServerIp) { this.dohServerIp = dohServerIp; }
        public String getDohServerHost() { return dohServerHost; }
        public void setDohServerHost(String dohServerHost) { this.dohServerHost = dohServerHost; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
        public boolean isKeepDataOnEmptyResult() { return keepDataOnEmptyResult; }
        public void setKeepDataOnEmptyResult(boolean keepDataOnEmptyResult) { this.keepDataOnEmptyResult = keepDataOnEmptyResult; }
    }

    /** 节点连通性测试 */
    public static class Test {
        /**
         * 代理内核（Xray）可执行文件路径。留空表示自动探测：
         * 先查 {@code app.test.core-dir}，再查 PATH，最后查 v2rayN 便携版的默认位置。
         */
        private String corePath = "";
        /** 内核所在目录；自动探测时会在其下查找 {@code xray/xray.exe} 或 {@code xray.exe} */
        private String coreDir = "";
        /**
         * 内核资源目录（含 geoip.dat / geosite.dat）。
         * 留空则用内核可执行文件所在目录，并通过 {@code XRAY_LOCATION_ASSET} 传给内核。
         */
        private String coreAssetDir = "";
        /** 内核启动到就绪的最长等待（毫秒）。探针为真实的 SOCKS5 握手，不是盲等 */
        private int coreStartupTimeoutMs = 8000;
        /** 内核日志级别：warning / info / debug */
        private String coreLogLevel = "warning";
        /** 测速阶段并发内核数（每节点独立内核，过高会互相争抢带宽并放大内存占用） */
        private int speedConcurrency = 3;

        /** TCPing（纯 TCP 连接测时）快速预筛并发度 */
        private int tcpingThreads = 50;
        /** TCPing 超时；v2rayN 硬编码 5 秒 */
        private int tcpingTimeoutMs = 5000;
        /** 开启后：TCPing 不通的节点直接判失败，不再启动内核做真实测试 */
        private boolean tcpingPreFilter = true;
        /** 真实延迟测试并发内核数；每节点一个内核进程，过高会耗尽句柄/端口 */
        private int latencyConcurrency = 8;

        /** 真实延迟探测地址：期望返回 204 且无响应体 */
        private String latencyTestUrl = "http://cp.cloudflare.com/generate_204";
        /** 真实延迟超时（单次） */
        private int latencyTimeoutMs = 5000;
        /** 真实延迟连测次数，取最小值（v2rayN 为 2 次） */
        private int latencyAttempts = 2;
        /** 两次探测之间的间隔 */
        private int latencyAttemptIntervalMs = 100;

        private boolean speedTestEnabled = true;
        /** 测速下载地址 */
        private String speedTestUrl = "https://speed.cloudflare.com/__down?bytes=50000000";
        /** 测速上限时长，到点即中断（v2rayN 默认 10 秒） */
        private int speedTestDurationMs = 10000;
        /** 测速连接/读超时 */
        private int speedTestTimeoutMs = 15000;

        /** UDP 可用性测试：目标格式 {@code 类型:主机[:端口]}，类型支持 ntp / dns */
        private boolean udpTestEnabled = false;
        private String udpTestTarget = "ntp:pool.ntp.org";
        private int udpTestTimeoutMs = 5000;

        /**
         * 连续失败多少次才自动删除节点。0 = 永不自动删除（默认，失败仅打标记）。
         * 之前的实现是"一次失败就物理删除"，会导致断网时整表被清空。
         */
        private int autoDeleteAfterFailures = 0;
        /** 定时测试开关与周期 */
        private boolean scheduleEnabled = true;
        private long scheduleFixedRateMs = 6 * 60 * 60 * 1000L;
        private long scheduleInitialDelayMs = 60 * 60 * 1000L;

        public String getCorePath() { return corePath; }
        public void setCorePath(String corePath) { this.corePath = corePath; }
        public String getCoreDir() { return coreDir; }
        public void setCoreDir(String coreDir) { this.coreDir = coreDir; }
        public String getCoreAssetDir() { return coreAssetDir; }
        public void setCoreAssetDir(String coreAssetDir) { this.coreAssetDir = coreAssetDir; }
        public int getCoreStartupTimeoutMs() { return coreStartupTimeoutMs; }
        public void setCoreStartupTimeoutMs(int coreStartupTimeoutMs) { this.coreStartupTimeoutMs = coreStartupTimeoutMs; }
        public String getCoreLogLevel() { return coreLogLevel; }
        public void setCoreLogLevel(String coreLogLevel) { this.coreLogLevel = coreLogLevel; }
        public int getSpeedConcurrency() { return speedConcurrency; }
        public void setSpeedConcurrency(int speedConcurrency) { this.speedConcurrency = speedConcurrency; }
        public int getTcpingThreads() { return tcpingThreads; }
        public void setTcpingThreads(int tcpingThreads) { this.tcpingThreads = tcpingThreads; }
        public int getTcpingTimeoutMs() { return tcpingTimeoutMs; }
        public void setTcpingTimeoutMs(int tcpingTimeoutMs) { this.tcpingTimeoutMs = tcpingTimeoutMs; }
        public boolean isTcpingPreFilter() { return tcpingPreFilter; }
        public void setTcpingPreFilter(boolean tcpingPreFilter) { this.tcpingPreFilter = tcpingPreFilter; }
        public int getLatencyConcurrency() { return latencyConcurrency; }
        public void setLatencyConcurrency(int latencyConcurrency) { this.latencyConcurrency = latencyConcurrency; }
        public String getLatencyTestUrl() { return latencyTestUrl; }
        public void setLatencyTestUrl(String latencyTestUrl) { this.latencyTestUrl = latencyTestUrl; }
        public int getLatencyTimeoutMs() { return latencyTimeoutMs; }
        public void setLatencyTimeoutMs(int latencyTimeoutMs) { this.latencyTimeoutMs = latencyTimeoutMs; }
        public int getLatencyAttempts() { return latencyAttempts; }
        public void setLatencyAttempts(int latencyAttempts) { this.latencyAttempts = latencyAttempts; }
        public int getLatencyAttemptIntervalMs() { return latencyAttemptIntervalMs; }
        public void setLatencyAttemptIntervalMs(int latencyAttemptIntervalMs) { this.latencyAttemptIntervalMs = latencyAttemptIntervalMs; }
        public boolean isSpeedTestEnabled() { return speedTestEnabled; }
        public void setSpeedTestEnabled(boolean speedTestEnabled) { this.speedTestEnabled = speedTestEnabled; }
        public String getSpeedTestUrl() { return speedTestUrl; }
        public void setSpeedTestUrl(String speedTestUrl) { this.speedTestUrl = speedTestUrl; }
        public int getSpeedTestDurationMs() { return speedTestDurationMs; }
        public void setSpeedTestDurationMs(int speedTestDurationMs) { this.speedTestDurationMs = speedTestDurationMs; }
        public int getSpeedTestTimeoutMs() { return speedTestTimeoutMs; }
        public void setSpeedTestTimeoutMs(int speedTestTimeoutMs) { this.speedTestTimeoutMs = speedTestTimeoutMs; }
        public boolean isUdpTestEnabled() { return udpTestEnabled; }
        public void setUdpTestEnabled(boolean udpTestEnabled) { this.udpTestEnabled = udpTestEnabled; }
        public String getUdpTestTarget() { return udpTestTarget; }
        public void setUdpTestTarget(String udpTestTarget) { this.udpTestTarget = udpTestTarget; }
        public int getUdpTestTimeoutMs() { return udpTestTimeoutMs; }
        public void setUdpTestTimeoutMs(int udpTestTimeoutMs) { this.udpTestTimeoutMs = udpTestTimeoutMs; }
        public int getAutoDeleteAfterFailures() { return autoDeleteAfterFailures; }
        public void setAutoDeleteAfterFailures(int autoDeleteAfterFailures) { this.autoDeleteAfterFailures = autoDeleteAfterFailures; }
        public boolean isScheduleEnabled() { return scheduleEnabled; }
        public void setScheduleEnabled(boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }
        public long getScheduleFixedRateMs() { return scheduleFixedRateMs; }
        public void setScheduleFixedRateMs(long scheduleFixedRateMs) { this.scheduleFixedRateMs = scheduleFixedRateMs; }
        public long getScheduleInitialDelayMs() { return scheduleInitialDelayMs; }
        public void setScheduleInitialDelayMs(long scheduleInitialDelayMs) { this.scheduleInitialDelayMs = scheduleInitialDelayMs; }
    }
}
