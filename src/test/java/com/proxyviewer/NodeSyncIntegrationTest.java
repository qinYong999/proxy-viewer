package com.proxyviewer;

import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import com.proxyviewer.service.NodeSyncService;
import com.proxyviewer.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增量刷新与"失败只标记"策略的集成验证（H2 内存库）。
 * 这两点是本次修复中最容易回归的行为，因此用真实数据库覆盖。
 */
@IntegrationTest
class NodeSyncIntegrationTest {

    @Autowired
    private NodeSyncService nodeSyncService;

    @Autowired
    private ProxyNodeRepository repository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private static ProxyNode node(String server, String name) {
        ProxyNode node = new ProxyNode();
        node.setProtocol("vless");
        node.setServer(server);
        node.setPort(443);
        node.setUuid("uuid-" + server);
        node.setNetwork("ws");
        node.setPath("/ws");
        node.setTls("tls");
        node.setName(name);
        node.setCountryCode("US");
        node.setCountryName("美国");
        node.setOriginalLink("vless://uuid-" + server + "@" + server + ":443?security=tls&type=ws#US-01");
        return node;
    }

    @Test
    void incrementalRefreshKeepsIdAndTestResultAndDropsStaleNodes() {
        NodeSyncService.SyncResult first = nodeSyncService.applyIncremental(List.of(node("a.com", "A")));
        assertThat(first.inserted()).isEqualTo(1);

        // 模拟已测出延迟
        ProxyNode stored = repository.findAll().get(0);
        Long originalId = stored.getId();
        stored.setLatencyMs(99L);
        stored.setSpeedKbps(1234L);
        stored.setLastTestResult("OK");
        stored.setLastTestTime(LocalDateTime.now());
        repository.save(stored);

        // 第二次刷新：a.com 还在（改名），新增 b.com
        NodeSyncService.SyncResult second = nodeSyncService.applyIncremental(
                List.of(node("a.com", "A-改名"), node("b.com", "B")));
        assertThat(second.inserted()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).isZero();

        ProxyNode kept = repository.findAll().stream()
                .filter(n -> "a.com".equals(n.getServer()))
                .findFirst().orElseThrow();
        assertThat(kept.getId()).isEqualTo(originalId);
        assertThat(kept.getName()).isEqualTo("A-改名");
        assertThat(kept.getLatencyMs()).isEqualTo(99L);
        assertThat(kept.getSpeedKbps()).isEqualTo(1234L);
        assertThat(kept.getLastTestResult()).isEqualTo("OK");

        // 第三次刷新：a.com 从订阅中消失 → 删除；b.com 保留且 id 不变
        ProxyNode b = repository.findAll().stream()
                .filter(n -> "b.com".equals(n.getServer())).findFirst().orElseThrow();
        NodeSyncService.SyncResult third = nodeSyncService.applyIncremental(List.of(node("b.com", "B")));
        assertThat(third.deleted()).isEqualTo(1);
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().get(0).getId()).isEqualTo(b.getId());
    }

    @Test
    void failedNodesAreMarkedNotDeletedAndCanBePurged() throws Exception {
        repository.saveAll(List.of(node("ok.com", "OK"), node("bad.com", "BAD")));
        ProxyNode bad = repository.findAll().stream()
                .filter(n -> "bad.com".equals(n.getServer())).findFirst().orElseThrow();
        bad.setLastTestResult(NodeSyncService.FAILED_PREFIX + "TIMEOUT");
        bad.setConsecutiveFailures(3);
        bad.setLatencyMs(-1L);
        bad.setSpeedKbps(-1L);
        repository.save(bad);

        // 失败节点仍留在库里，并且页面能渲染出失败标记与清理按钮
        assertThat(repository.count()).isEqualTo(2);
        mockMvc.perform(get("/").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bad.com")))
                .andExpect(content().string(containsString("TIMEOUT")))
                .andExpect(content().string(containsString("清理失败节点")))
                .andExpect(content().string(containsString("连续失败 3 次")));

        // 手动清理：只删失败节点，可达节点保留
        mockMvc.perform(post("/api/purge-failed")
                        .with(user("tester"))
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"deleted\":1")));

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().get(0).getServer()).isEqualTo("ok.com");
    }

    @Test
    void copyEndpointReturnsOriginalLinks() throws Exception {
        ProxyNode saved = repository.save(node("copy.com", "COPY"));

        mockMvc.perform(post("/api/copy")
                        .with(user("tester"))
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + saved.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("vless://uuid-copy.com@copy.com:443")));
    }
}
