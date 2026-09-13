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
}
