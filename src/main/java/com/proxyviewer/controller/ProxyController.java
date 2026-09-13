package com.proxyviewer.controller;

import com.proxyviewer.config.AppProperties;
import com.proxyviewer.model.NodeTestRecord;
import com.proxyviewer.model.NodeTestRecordRepository;
import com.proxyviewer.model.OperationLog;
import com.proxyviewer.model.OperationLogRepository;
import com.proxyviewer.model.ProxyNode;
import com.proxyviewer.model.ProxyNodeRepository;
import com.proxyviewer.service.NodeSyncService;
import com.proxyviewer.service.NodeTestService;
import com.proxyviewer.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private static final Logger oplog = LoggerFactory.getLogger("OPLOG");

    private static final List<Integer> PAGE_SIZES = List.of(20, 50, 100, 200);

    private final AppProperties props;
    private final SubscriptionService subscriptionService;
    private final ProxyNodeRepository repository;
    private final OperationLogRepository logRepository;
    private final NodeTestService nodeTestService;
    private final NodeTestRecordRepository testRecordRepository;
    private final NodeSyncService nodeSyncService;

    public ProxyController(AppProperties props,
                           SubscriptionService subscriptionService,
                           ProxyNodeRepository repository,
                           OperationLogRepository logRepository,
                           NodeTestService nodeTestService,
                           NodeTestRecordRepository testRecordRepository,
                           NodeSyncService nodeSyncService) {
        this.props = props;
        this.subscriptionService = subscriptionService;
        this.repository = repository;
        this.logRepository = logRepository;
        this.nodeTestService = nodeTestService;
        this.testRecordRepository = testRecordRepository;
        this.nodeSyncService = nodeSyncService;
    }

    @GetMapping("/")
    public String index(@RequestParam(value = "page", defaultValue = "1") int page,
                        @RequestParam(value = "size", defaultValue = "50") int size,
                        @RequestParam(value = "url", defaultValue = "") String url,
                        @RequestParam(value = "country", defaultValue = "") String country,
                        @RequestParam(value = "sort", defaultValue = "") String sort,
                        @RequestParam(value = "order", defaultValue = "asc") String order,
                        Model model) {
        log.info("访问首页 page={} size={} country={} sort={} order={}", page, size, country, sort, order);
        return paginate(page, size, url, country, sort, order, model);
    }

    /**
     * 刷新订阅。使用 POST（状态变更不应通过 GET 触发）；
     * 成功后重定向回首页（PRG 模式），避免刷新页面导致重复抓取。
     */
    @PostMapping("/refresh")
    public String refresh(@RequestParam(value = "url", defaultValue = "") String url,
                          @RequestParam(value = "size", defaultValue = "50") int size,
                          RedirectAttributes attr,
                          HttpServletRequest request) {
        String targetUrl = (url == null || url.isBlank()) ? props.getSubscription().getDefaultUrl() : url.trim();
        String clientIp = getClientIp(request);

        if (targetUrl == null || targetUrl.isBlank()) {
            attr.addFlashAttribute("error", "未指定订阅链接，且未配置 app.subscription.default-url");
            return "redirect:/";
        }

        log.info("开始刷新订阅: {} (IP={})", targetUrl, clientIp);
        try {
            SubscriptionService.RefreshResult result = subscriptionService.refreshNodes(targetUrl);
            if (result.skipped()) {
                log.warn("刷新订阅返回空数据，保留原数据");
                saveLog("REFRESH", "订阅返回空数据，已保留现有节点", "FAILED", 0, targetUrl, clientIp);
                attr.addFlashAttribute("msg", "订阅返回空数据，已保留现有节点");
            } else {
                NodeSyncService.SyncResult sync = result.sync();
                String detail = String.format("新增 %d / 更新 %d / 删除 %d",
                        sync.inserted(), sync.updated(), sync.deleted());
                log.info("刷新订阅成功: {}", detail);
                oplog.info("[REFRESH] ✅ SUCCESS | {} | {}", detail, targetUrl);
                saveLog("REFRESH", "刷新订阅：" + detail, "SUCCESS", result.nodeCount(), targetUrl, clientIp);
                attr.addFlashAttribute("msg", "刷新成功：" + detail + "，当前共 " + result.nodeCount() + " 个节点");
            }
            attr.addAttribute("size", size);
            if (url != null && !url.isBlank()) {
                attr.addAttribute("url", url);
            }
        } catch (Exception e) {
            log.error("刷新订阅失败: {} - {}", e.getMessage(), targetUrl);
            oplog.info("[REFRESH] ❌ FAILED | {} | {}", e.getMessage(), targetUrl);
            saveLog("REFRESH", "刷新订阅失败: " + e.getMessage(), "FAILED", 0, targetUrl, clientIp);
            attr.addFlashAttribute("error", "刷新失败: " + e.getMessage());
            attr.addAttribute("size", size);
        }
        return "redirect:/";
    }

    @PostMapping("/api/copy")
    public ResponseEntity<String> copyNodes(@RequestBody Map<String, List<Long>> body,
                                            HttpServletRequest request) {
        List<Long> ids = body.getOrDefault("ids", List.of());
        if (ids.isEmpty()) {
            log.warn("复制请求 ID 列表为空");
            return ResponseEntity.ok("");
        }
        log.info("复制节点请求: {} 个ID", ids.size());
        List<ProxyNode> nodes = repository.findAllById(ids);
        String result = nodes.stream()
                .map(ProxyNode::getOriginalLink)
                .filter(link -> link != null && !link.isBlank())
                .collect(Collectors.joining("\n"));

        String clientIp = getClientIp(request);
        int validCount = (int) result.lines().count();
        log.info("复制节点成功: {} 个有效链接, IP={}", validCount, clientIp);
        oplog.info("[COPY] ✅ SUCCESS | {} 节点 | IP={}", validCount, clientIp);
        saveLog("COPY", "复制节点", "SUCCESS", validCount, null, clientIp);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE + "; charset=utf-8")
                .body(result);
    }

    @PostMapping("/api/delete")
    public ResponseEntity<Map<String, Object>> deleteNodes(@RequestBody Map<String, List<Long>> body,
                                                           HttpServletRequest request) {
        List<Long> ids = body.getOrDefault("ids", List.of());
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "msg", "ID列表为空"));
        }
        List<ProxyNode> nodes = repository.findAllById(ids);
        repository.deleteAll(nodes);
        String clientIp = getClientIp(request);
        log.info("删除节点: {} 个, IP={}", nodes.size(), clientIp);
        oplog.info("[DELETE] ✅ {} 节点 | IP={}", nodes.size(), clientIp);
        saveLog("DELETE", "删除节点", "SUCCESS", nodes.size(), null, clientIp);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("deleted", nodes.size());
        return ResponseEntity.ok(resp);
    }

    /** 清理被标记为失败的节点（替代旧的"测试失败即自动删除"） */
    @PostMapping("/api/purge-failed")
    public ResponseEntity<Map<String, Object>> purgeFailed(HttpServletRequest request) {
        int deleted = nodeSyncService.purgeFailed();
        String clientIp = getClientIp(request);
        log.info("清理失败节点: {} 个, IP={}", deleted, clientIp);
        oplog.info("[PURGE] ✅ {} 个失败节点 | IP={}", deleted, clientIp);
        saveLog("PURGE", "清理失败节点", "SUCCESS", deleted, null, clientIp);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("deleted", deleted);
        return ResponseEntity.ok(resp);
    }

    /** 操作日志页面 */
    @GetMapping("/logs")
    public String logs(@RequestParam(value = "page", defaultValue = "1") int page, Model model) {
        int pageIndex = Math.max(page, 1) - 1;
        Pageable pageable = PageRequest.of(pageIndex, 50, Sort.by(Sort.Direction.DESC, "id"));
        Page<OperationLog> logPage = logRepository.findAll(pageable);

        model.addAttribute("logs", logPage.getContent());
        model.addAttribute("totalCount", logPage.getTotalElements());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", logPage.getTotalPages());
        return "logs";
    }

    /** 手动触发节点连通性测试（POST，避免被预取/爬虫触发） */
    @PostMapping("/test")
    public String runTest(RedirectAttributes attr) {
        log.info("手动触发节点连通性测试");
        try {
            NodeTestRecord record = nodeTestService.runTest("MANUAL");
            attr.addFlashAttribute("msg", String.format(
                    "测试完成: 总计 %d, ✅可达 %d, ❌失败 %d, 自动删除 %d, 耗时 %dms",
                    record.getTotalNodes(), record.getSuccessCount(),
                    record.getFailedCount(), record.getDeletedCount(), record.getDurationMs()));
        } catch (Exception e) {
            log.error("手动测试失败", e);
            attr.addFlashAttribute("error", "测试失败: " + e.getMessage());
        }
        return "redirect:/test-logs";
    }

    /** 测试历史页面 */
    @GetMapping("/test-logs")
    public String testLogs(@RequestParam(value = "page", defaultValue = "1") int page, Model model) {
        int pageIndex = Math.max(page, 1) - 1;
        Pageable pageable = PageRequest.of(pageIndex, 50, Sort.by(Sort.Direction.DESC, "id"));
        Page<NodeTestRecord> testPage = testRecordRepository.findAll(pageable);

        model.addAttribute("tests", testPage.getContent());
        model.addAttribute("totalCount", testPage.getTotalElements());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", testPage.getTotalPages());
        model.addAttribute("testRunning", nodeTestService.isRunning());
        model.addAttribute("autoDeleteAfterFailures", props.getTest().getAutoDeleteAfterFailures());
        return "test-logs";
    }

    // ======================== 内部方法 ========================

    private void saveLog(String action, String detail, String result, int nodeCount,
                         String subscriptionUrl, String clientIp) {
        OperationLog entry = new OperationLog();
        entry.setAction(action);
        entry.setDetail(detail);
        entry.setResult(result);
        entry.setNodeCount(nodeCount);
        entry.setSubscriptionUrl(subscriptionUrl);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setClientIp(clientIp);
        logRepository.save(entry);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private String paginate(int page, int size, String url, String country,
                            String sort, String order, Model model) {
        int pageIndex = Math.max(page, 1) - 1;
        if (!PAGE_SIZES.contains(size)) {
            size = 50;
        }

        Sort sortObj = Sort.by(Sort.Direction.ASC, "id");
        if ("latencyMs".equals(sort)) {
            sortObj = Sort.by("asc".equals(order) ? Sort.Direction.ASC : Sort.Direction.DESC, "latencyMs")
                    .and(Sort.by(Sort.Direction.ASC, "id"));
        }
        Pageable pageable = PageRequest.of(pageIndex, size, sortObj);
        Page<ProxyNode> pageResult = filterByCountry(country, pageable);

        String defaultUrl = props.getSubscription().getDefaultUrl();
        model.addAttribute("nodes", pageResult.getContent());
        model.addAttribute("totalCount", pageResult.getTotalElements());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", pageResult.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("currentUrl", (url != null && !url.isBlank()) ? url : defaultUrl);
        model.addAttribute("currentCountry", country != null ? country : "");
        model.addAttribute("currentSort", sort != null ? sort : "");
        model.addAttribute("currentOrder", "latencyMs".equals(sort) ? order : "");
        model.addAttribute("countries", repository.findDistinctCountryNames());

        boolean filtered = country != null && !country.isBlank();
        model.addAttribute("vlessCount", filtered
                ? repository.countByProtocolAndCountryName("vless", country) : repository.countByProtocol("vless"));
        model.addAttribute("vmessCount", filtered
                ? repository.countByProtocolAndCountryName("vmess", country) : repository.countByProtocol("vmess"));
        model.addAttribute("wsCount", filtered
                ? repository.countByNetworkAndCountryName("ws", country) : repository.countByNetwork("ws"));
        model.addAttribute("tcpCount", filtered
                ? repository.countByNetworkAndCountryName("tcp", country) : repository.countByNetwork("tcp"));
        model.addAttribute("okCount", filtered
                ? repository.countByCountryNameAndLastTestResult(country, NodeTestService.RESULT_OK)
                : repository.countByLastTestResult(NodeTestService.RESULT_OK));
        model.addAttribute("failedCount", filtered
                ? repository.countByCountryNameAndLastTestResultStartingWith(country, NodeSyncService.FAILED_PREFIX)
                : repository.countByLastTestResultStartingWith(NodeSyncService.FAILED_PREFIX));
        model.addAttribute("testRunning", nodeTestService.isRunning());

        // 最近一条刷新日志的时间
        logRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .filter(l -> "REFRESH".equals(l.getAction()) && "SUCCESS".equals(l.getResult()))
                .findFirst().ifPresent(entry -> model.addAttribute("lastRefreshTime", entry.getCreatedAt()));

        model.addAttribute("error", null);
        return "index";
    }

    private Page<ProxyNode> filterByCountry(String country, Pageable pageable) {
        if (country != null && !country.isBlank()) {
            return repository.findByCountryName(country, pageable);
        }
        return repository.findAll(pageable);
    }
}
