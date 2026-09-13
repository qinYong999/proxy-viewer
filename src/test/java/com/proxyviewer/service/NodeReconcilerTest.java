package com.proxyviewer.service;

import com.proxyviewer.model.ProxyNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量对账：取代 deleteAll + saveAll 之后，必须保证
 * "新增/更新/删除"划分正确，且已有节点的 id 与测试结果不被刷新清掉。
 */
class NodeReconcilerTest {

    private static ProxyNode node(String server, String path, String name) {
        ProxyNode node = new ProxyNode();
        node.setProtocol("vless");
        node.setServer(server);
        node.setPort(443);
        node.setUuid("uuid-" + server);
        node.setNetwork("ws");
        node.setPath(path);
        node.setName(name);
        node.setOriginalLink("vless://uuid-" + server + "@" + server + ":443");
        return node;
    }

    @Test
    void insertsBrandNewNodes() {
        NodeReconciler.Plan plan = NodeReconciler.plan(List.of(), List.of(node("a.com", "/a", "A")));

        assertThat(plan.inserts()).hasSize(1);
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.deletes()).isEmpty();
    }

    @Test
    void updatesExistingNodeAndPreservesIdAndTestResult() {
        ProxyNode existing = node("a.com", "/a", "旧名字");
        existing.setId(7L);
        existing.setLatencyMs(120L);
        existing.setSpeedKbps(5000L);
        existing.setLastTestResult("OK");

        ProxyNode incoming = node("a.com", "/a", "新名字");

        NodeReconciler.Plan plan = NodeReconciler.plan(List.of(existing), List.of(incoming));

        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.deletes()).isEmpty();
        assertThat(plan.updates()).hasSize(1);
        ProxyNode updated = plan.updates().get(0);
        assertThat(updated).isSameAs(existing);
        assertThat(updated.getId()).isEqualTo(7L);
        assertThat(updated.getName()).isEqualTo("新名字");
        assertThat(updated.getLatencyMs()).isEqualTo(120L);
        assertThat(updated.getSpeedKbps()).isEqualTo(5000L);
        assertThat(updated.getLastTestResult()).isEqualTo("OK");
    }

    @Test
    void deletesNodesMissingFromSubscription() {
        ProxyNode gone = node("gone.com", "/x", "GONE");
        ProxyNode kept = node("kept.com", "/y", "KEPT");

        NodeReconciler.Plan plan = NodeReconciler.plan(List.of(gone, kept), List.of(node("kept.com", "/y", "KEPT")));

        assertThat(plan.deletes()).containsExactly(gone);
        assertThat(plan.updates()).containsExactly(kept);
        assertThat(plan.inserts()).isEmpty();
    }

    @Test
    void deduplicatesRepeatedEntriesInSubscription() {
        NodeReconciler.Plan plan = NodeReconciler.plan(List.of(),
                List.of(node("dup.com", "/a", "一"), node("dup.com", "/a", "二")));

        assertThat(plan.inserts()).hasSize(1);
        assertThat(plan.duplicateIncoming()).isEqualTo(1);
    }

    @Test
    void removesDuplicateRowsAlreadyInDatabase() {
        ProxyNode first = node("dup.com", "/a", "一");
        first.setId(1L);
        ProxyNode second = node("dup.com", "/a", "二");
        second.setId(2L);

        NodeReconciler.Plan plan = NodeReconciler.plan(List.of(first, second),
                List.of(node("dup.com", "/a", "三")));

        assertThat(plan.duplicateExisting()).isEqualTo(1);
        assertThat(plan.deletes()).containsExactly(second);
        assertThat(plan.updates()).containsExactly(first);
    }

    @Test
    void fingerprintIgnoresNameAndToleratesNulls() {
        ProxyNode a = node("a.com", "/a", "名字一");
        ProxyNode b = node("a.com", "/a", "名字二");
        assertThat(NodeReconciler.fingerprint(a)).isEqualTo(NodeReconciler.fingerprint(b));

        ProxyNode weird = new ProxyNode();
        assertThat(NodeReconciler.fingerprint(weird)).isNotNull();
        assertThat(NodeReconciler.fingerprint(null)).isEmpty();
    }
}
