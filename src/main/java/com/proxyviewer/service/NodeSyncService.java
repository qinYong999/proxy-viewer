package com.proxyviewer.service;

import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点集合的持久化：增量对账入库 + 手动清理失败节点。
 *
 * <p>抓取（网络 I/O）留在 {@link SubscriptionService}，事务只覆盖数据库操作，
 * 避免长时间占用连接。</p>
 */
@Service
public class NodeSyncService {

    private static final Logger log = LoggerFactory.getLogger(NodeSyncService.class);

    /** 失败节点标记前缀，页面与清理逻辑共用 */
    public static final String FAILED_PREFIX = "FAILED:";

    private final ProxyNodeRepository repository;

    public NodeSyncService(ProxyNodeRepository repository) {
        this.repository = repository;
    }

    public record SyncResult(int inserted, int updated, int deleted, int duplicateIncoming, int duplicateExisting) {
        public int total() {
            return inserted + updated;
        }
    }

    /** 把抓取结果增量合并进数据库 */
    @Transactional
    public SyncResult applyIncremental(List<ProxyNode> incoming) {
        List<ProxyNode> existing = repository.findAll();
        NodeReconciler.Plan plan = NodeReconciler.plan(existing, incoming);

        if (!plan.deletes().isEmpty()) {
            repository.deleteAll(plan.deletes());
        }
        List<ProxyNode> toSave = new ArrayList<>(plan.updates().size() + plan.inserts().size());
        toSave.addAll(plan.updates());
        toSave.addAll(plan.inserts());
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }

        SyncResult result = new SyncResult(plan.inserts().size(), plan.updates().size(),
                plan.deletes().size(), plan.duplicateIncoming(), plan.duplicateExisting());
        log.info("增量入库完成: 新增={} 更新={} 删除={} 订阅内重复={} 库内重复={}",
                result.inserted(), result.updated(), result.deleted(),
                result.duplicateIncoming(), result.duplicateExisting());
        return result;
    }

    /** 清理被标记为失败的节点（用户显式触发，替代原来"测试失败即删除"的行为） */
    @Transactional
    public int purgeFailed() {
        List<ProxyNode> failed = repository.findByLastTestResultStartingWith(FAILED_PREFIX);
        if (failed.isEmpty()) {
            return 0;
        }
        repository.deleteAll(failed);
        log.info("已清理失败节点 {} 个", failed.size());
        return failed.size();
    }
}
