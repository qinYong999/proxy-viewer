package com.proxyviewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 节点测试批次记录 */
@Entity
@Table(name = "node_test_records")
public class NodeTestRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_time", nullable = false)
    private LocalDateTime testTime;

    @Column(name = "total_nodes")
    private int totalNodes;

    @Column(name = "success_count")
    private int successCount;

    @Column(name = "failed_count")
    private int failedCount;

    @Column(name = "deleted_count")
    private int deletedCount;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(name = "trigger_type", length = 20)
    private String triggerType;  // SCHEDULED / MANUAL

    /** 本批次 UDP 可用（SOCKS5 UDP ASSOCIATE 探测通过）的节点数 */
    @Column(name = "udp_success_count", nullable = false, columnDefinition = "int default 0")
    private int udpSuccessCount;

    /** 本批次实际完成下载测速的节点数 */
    @Column(name = "speed_test_count", nullable = false, columnDefinition = "int default 0")
    private int speedTestCount;

    /**
     * 本批次可达节点的平均真实延迟（毫秒）；无可达节点时为 -1。
     *
     * <p>用包装类型而非 {@code long}：本列是后加的，历史批次行该列为 {@code NULL}，
     * 若声明为原始类型，Hibernate 读取旧行时会抛
     * {@code Can not set long field ... to null value}。</p>
     */
    @Column(name = "avg_latency_ms", columnDefinition = "bigint default -1")
    private Long avgLatencyMs = -1L;

    public NodeTestRecord() {}

    public NodeTestRecord(LocalDateTime testTime, int totalNodes, String triggerType) {
        this.testTime = testTime;
        this.totalNodes = totalNodes;
        this.triggerType = triggerType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getTestTime() { return testTime; }
    public void setTestTime(LocalDateTime testTime) { this.testTime = testTime; }

    public int getTotalNodes() { return totalNodes; }
    public void setTotalNodes(int totalNodes) { this.totalNodes = totalNodes; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public int getDeletedCount() { return deletedCount; }
    public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public int getUdpSuccessCount() { return udpSuccessCount; }
    public void setUdpSuccessCount(int udpSuccessCount) { this.udpSuccessCount = udpSuccessCount; }

    public int getSpeedTestCount() { return speedTestCount; }
    public void setSpeedTestCount(int speedTestCount) { this.speedTestCount = speedTestCount; }

    /** 历史批次的该列可能为 NULL，统一按"未测得"(-1) 暴露给页面 */
    public long getAvgLatencyMs() { return avgLatencyMs == null ? -1L : avgLatencyMs; }

    public void setAvgLatencyMs(long avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
}
