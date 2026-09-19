package com.proxyviewer;

import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import com.proxyviewer.service.NodeTestService;
import com.proxyviewer.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 测试触发与进度状态机。
 *
 * <p>背景：早期实现让 {@code POST /test} 同步等待整批测试跑完（可达数分钟），
 * 页面在这段时间内毫无反馈，用户无法判断是卡死还是在运行。现在改为立即返回、
 * 后台执行，前端轮询进度。本测试锁定这一行为。</p>
 *
 * <p>为避免真的联网/起内核，节点一律指向 TEST-NET-1 保留地址（192.0.2.0/24，不可路由）：
 * TCPing 预筛会判其不可达，因此不会启动内核，测试仍能验证完整状态机。</p>
 */
@IntegrationTest
class NodeTestProgressTest {

    @Autowired
    private NodeTestService nodeTestService;
    @Autowired
    private ProxyNodeRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    /** 简单轮询等待，避免为一个断言引入 Awaitility 依赖 */
    private static void waitUntil(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待超时（" + timeoutMs + "ms）: " + what);
    }

    private static ProxyNode unreachableNode(String name) {
        ProxyNode node = new ProxyNode();
        node.setName(name);
        node.setProtocol("vless");
        node.setServer("192.0.2.1");     // TEST-NET-1，保证不可路由
        node.setPort(9);
        node.setUuid("11111111-2222-3333-4444-555555555555");
        node.setNetwork("tcp");
        return node;
    }

    @Test
    void emptyLibraryFinishesWithClearMessage() {
        repository.deleteAll();

        nodeTestService.startTestRun("TEST");
        waitUntil("空库测试结束", () -> !nodeTestService.isRunning(), 15_000);

        NodeTestService.ProgressSnapshot snapshot = nodeTestService.snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.message()).contains("没有可测试的节点");
        assertThat(snapshot.events()).isEmpty();
        // 结束后阶段应被复位，避免页面一直显示"测试中"
        assertThat(snapshot.phase()).isEmpty();
    }

    @Test
    void startTestRunReturnsImmediatelyInsteadOfBlocking() {
        repository.deleteAll();
        repository.save(unreachableNode("progress-probe"));

        long t0 = System.currentTimeMillis();
        nodeTestService.startTestRun("TEST");
        long returnMs = System.currentTimeMillis() - t0;

        // 关键断言：调用必须立刻返回，而不是等整批跑完
        assertThat(returnMs)
                .withFailMessage("startTestRun 应立即返回，实际耗时 %dms", returnMs)
                .isLessThan(2000);

        waitUntil("测试结束", () -> !nodeTestService.isRunning(), 40_000);

        NodeTestService.ProgressSnapshot after = nodeTestService.snapshot();
        assertThat(after.running()).isFalse();
        assertThat(after.message()).isNotBlank();
        assertThat(after.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void progressIsObservableWhileRunning() {
        repository.deleteAll();
        // 多个不可达节点：TCPing 各自要等超时，足以观察到"运行中"的中间态
        for (int i = 0; i < 4; i++) {
            repository.save(unreachableNode("progress-probe-" + i));
        }

        nodeTestService.startTestRun("TEST");
        waitUntil("进入运行态", nodeTestService::isRunning, 5_000);

        NodeTestService.ProgressSnapshot during = nodeTestService.snapshot();
        assertThat(during.running()).isTrue();
        // 阶段必须可读，否则前端拿不到有意义的反馈
        assertThat(during.phase()).isNotBlank();

        waitUntil("测试结束", () -> !nodeTestService.isRunning(), 60_000);
    }

    @Test
    void secondStartWhileRunningIsRejectedInsteadOfRunningTwice() {
        repository.deleteAll();
        for (int i = 0; i < 4; i++) {
            repository.save(unreachableNode("dup-probe-" + i));
        }

        nodeTestService.startTestRun("TEST");
        waitUntil("进入运行态", nodeTestService::isRunning, 5_000);

        assertThatThrownBy(() -> nodeTestService.startTestRun("TEST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有测试任务正在执行");

        waitUntil("测试结束", () -> !nodeTestService.isRunning(), 60_000);
    }

    @Test
    void snapshotIsStableBeforeAnyRun() {
        NodeTestService.ProgressSnapshot snapshot = nodeTestService.snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.done()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.events()).isNotNull();
    }
}

