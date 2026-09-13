package com.proxyviewer.config;

import com.proxyviewer.service.NodeTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时任务 —— 周期性测试节点连通性。
 *
 * <p>周期与开关均由配置控制（{@code app.test.schedule-*}），默认 6 小时一次、首次延迟 1 小时。
 * 测试失败只打标记，不再自动删除节点，因此定时任务不会造成数据丢失。</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.test.schedule-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    private final NodeTestService nodeTestService;

    public ScheduledTasks(NodeTestService nodeTestService) {
        this.nodeTestService = nodeTestService;
    }

    @Scheduled(fixedRateString = "${app.test.schedule-fixed-rate-ms:21600000}",
            initialDelayString = "${app.test.schedule-initial-delay-ms:3600000}")
    public void testNodes() {
        log.info("⏰ 定时任务触发：节点连通性测试");
        try {
            nodeTestService.runTest("SCHEDULED");
        } catch (IllegalStateException e) {
            log.warn("跳过本次定时测试：{}", e.getMessage());
        } catch (Exception e) {
            log.error("定时测试失败", e);
        }
    }
}
