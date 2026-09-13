package com.proxyviewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 操作日志 —— 记录刷新订阅、复制节点等关键操作
 */
@Entity
@Table(name = "operation_logs")
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作类型：REFRESH / COPY / ... */
    @Column(name = "action", length = 50, nullable = false)
    private String action;

    /** 操作描述 */
    @Column(name = "detail", length = 500)
    private String detail;

    /** 操作结果：SUCCESS / FAILED */
    @Column(name = "result", length = 20)
    private String result;

    /** 相关节点数量 */
    @Column(name = "node_count")
    private int nodeCount;

    /** 订阅链接（如果是刷新操作） */
    @Column(name = "subscription_url", length = 500)
    private String subscriptionUrl;

    /** 操作时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 客户端 IP */
    @Column(name = "client_ip", length = 50)
    private String clientIp;

    public OperationLog() {}

    public OperationLog(String action, String detail, String result, int nodeCount) {
        this.action = action;
        this.detail = detail;
        this.result = result;
        this.nodeCount = nodeCount;
        this.createdAt = LocalDateTime.now();
    }

    // === getters / setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public int getNodeCount() { return nodeCount; }
    public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }

    public String getSubscriptionUrl() { return subscriptionUrl; }
    public void setSubscriptionUrl(String subscriptionUrl) { this.subscriptionUrl = subscriptionUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
}
