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

    /** 访问控制：HTTP Basic 认证 */
    public static class Security {
        /** 是否启用认证。默认开启，关闭后任何人可访问（仅建议本机调试时关闭） */
        private boolean enabled = true;
        private String username = "admin";
        /** 留空则启动时随机生成并打印到日志 */
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
        private int threadPoolSize = 20;
        private int connectTimeoutMs = 8000;
        private int readTimeoutMs = 6000;
        private int maxRetries = 1;
        /** 是否为 TLS 节点做真实测速（明文 TCP 节点无法测速，一律记为未测） */
        private boolean speedTestEnabled = true;
        private int speedTestDurationMs = 3000;
        /**
         * 连续失败多少次才自动删除节点。0 = 永不自动删除（默认，失败仅打标记）。
         * 之前的实现是"一次失败就物理删除"，会导致断网时整表被清空。
         */
        private int autoDeleteAfterFailures = 0;
        /** 定时测试开关与周期 */
        private boolean scheduleEnabled = true;
        private long scheduleFixedRateMs = 6 * 60 * 60 * 1000L;
        private long scheduleInitialDelayMs = 60 * 60 * 1000L;

        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isSpeedTestEnabled() { return speedTestEnabled; }
        public void setSpeedTestEnabled(boolean speedTestEnabled) { this.speedTestEnabled = speedTestEnabled; }
        public int getSpeedTestDurationMs() { return speedTestDurationMs; }
        public void setSpeedTestDurationMs(int speedTestDurationMs) { this.speedTestDurationMs = speedTestDurationMs; }
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
