package com.proxyviewer.model;

import jakarta.persistence.*;

/**
 * 代理节点信息 JPA 实体，统一表示 vless 和 vmess 两种协议
 */
@Entity
@Table(name = "proxy_nodes", indexes = {
    @Index(name = "idx_protocol", columnList = "protocol"),
    @Index(name = "idx_country", columnList = "countryName"),
    @Index(name = "idx_network", columnList = "network")
})
public class ProxyNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_name", length = 500)
    private String name;          // 节点名称（含国家/地区emoji）

    @Column(length = 20)
    private String protocol;      // 协议类型: vless / vmess

    @Column(length = 200)
    private String server;        // 服务器地址

    @Column(nullable = false)
    private int port = 443;       // 端口

    @Column(name = "uuid_", length = 100)
    private String uuid;          // UUID / ID

    @Column(length = 20)
    private String network;       // 传输协议: tcp / ws

    @Column(columnDefinition = "TEXT")
    private String path;          // WebSocket路径

    @Column(length = 300)
    private String host;          // Host（伪装域名）

    @Column(length = 20)
    private String tls;           // TLS: tls / none

    @Column(length = 300)
    private String sni;           // SNI

    @Column(length = 20)
    private String security;      // 安全: auto / tls

    private int aid;              // AlterID（vmess特有）

    @Column(length = 50)
    private String fp;            // Fingerprint (指纹)

    @Column(length = 50)
    private String alpn;          // ALPN

    /**
     * VLESS flow（如 {@code xtls-rprx-vision}）。
     * 缺失会导致 Reality/Vision 节点生成的内核配置必然握手失败，进而被误判为节点失效。
     */
    @Column(length = 50)
    private String flow;

    /** VLESS 加密方式（通常为 {@code none}） */
    @Column(length = 50)
    private String encryption;

    /** REALITY 公钥（分享链接参数 {@code pbk}），属敏感参数 */
    @Column(name = "public_key", length = 200)
    private String publicKey;

    /** REALITY shortId（分享链接参数 {@code sid}） */
    @Column(name = "short_id", length = 100)
    private String shortId;

    /** REALITY spiderX（分享链接参数 {@code spx}） */
    @Column(name = "spider_x", length = 200)
    private String spiderX;

    /** gRPC 传输的 serviceName / xhttp 的 mode */
    @Column(name = "service_name", length = 200)
    private String serviceName;

    /** TCP 伪装头类型：none / http（分享链接参数 {@code headerType}） */
    @Column(name = "header_type", length = 20)
    private String headerType;

    @Column(name = "skip_cert_verify")
    private boolean skipCertVerify;

    @Column(length = 50)
    private String countryCode;

    @Column(length = 50)
    private String countryName;

    /** 原始订阅链接（vless://... 或 vmess://...），用于复制到客户端 */
    @Column(name = "original_link", columnDefinition = "TEXT")
    private String originalLink;

    /** 最近一次测试延迟（毫秒），-1 表示不可达 */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** 最近一次测试时间 */
    @Column(name = "last_test_time")
    private java.time.LocalDateTime lastTestTime;

    /** 最近一次测试结果：OK / FAILED:xxx / null(未测) */
    @Column(name = "last_test_result", length = 200)
    private String lastTestResult;

    /**
     * 连续失败次数。测试成功清零；达到 app.test.auto-delete-after-failures 才考虑自动删除。
     * 这样单次网络抖动不会立刻把节点删掉。
     */
    @Column(name = "consecutive_failures", nullable = false, columnDefinition = "int default 0")
    private int consecutiveFailures;

    /** 下载速度（Kbps），-1 表示未测速 */
    @Column(name = "speed_kbps")
    private Long speedKbps;

    public ProxyNode() {}

    // --- getters / setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getServer() { return server; }
    public void setServer(String server) { this.server = server; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getTls() { return tls; }
    public void setTls(String tls) { this.tls = tls; }

    public String getSni() { return sni; }
    public void setSni(String sni) { this.sni = sni; }

    public String getSecurity() { return security; }
    public void setSecurity(String security) { this.security = security; }

    public int getAid() { return aid; }
    public void setAid(int aid) { this.aid = aid; }

    public String getFp() { return fp; }
    public void setFp(String fp) { this.fp = fp; }

    public String getAlpn() { return alpn; }
    public void setAlpn(String alpn) { this.alpn = alpn; }

    public String getFlow() { return flow; }
    public void setFlow(String flow) { this.flow = flow; }

    public String getEncryption() { return encryption; }
    public void setEncryption(String encryption) { this.encryption = encryption; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getShortId() { return shortId; }
    public void setShortId(String shortId) { this.shortId = shortId; }

    public String getSpiderX() { return spiderX; }
    public void setSpiderX(String spiderX) { this.spiderX = spiderX; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getHeaderType() { return headerType; }
    public void setHeaderType(String headerType) { this.headerType = headerType; }

    public boolean isSkipCertVerify() { return skipCertVerify; }
    public void setSkipCertVerify(boolean skipCertVerify) { this.skipCertVerify = skipCertVerify; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public String getCountryName() { return countryName; }
    public void setCountryName(String countryName) { this.countryName = countryName; }

    public String getOriginalLink() { return originalLink; }
    public void setOriginalLink(String originalLink) { this.originalLink = originalLink; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public java.time.LocalDateTime getLastTestTime() { return lastTestTime; }
    public void setLastTestTime(java.time.LocalDateTime lastTestTime) { this.lastTestTime = lastTestTime; }

    public String getLastTestResult() { return lastTestResult; }
    public void setLastTestResult(String lastTestResult) { this.lastTestResult = lastTestResult; }

    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

    public Long getSpeedKbps() { return speedKbps; }
    public void setSpeedKbps(Long speedKbps) { this.speedKbps = speedKbps; }
}
