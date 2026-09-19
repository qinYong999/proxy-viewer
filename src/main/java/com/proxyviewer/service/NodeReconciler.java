package com.proxyviewer.service;

import com.proxyviewer.model.ProxyNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 增量对账：把"新抓取到的节点集合"与"库中已有节点"做差集，产出 新增 / 更新 / 删除 三类计划。
 *
 * <p>取代原来的 {@code deleteAll() + saveAll()} 全量替换，带来三个好处：</p>
 * <ul>
 *   <li>未变化的节点保留主键 → 页面选择、外部引用不再每次刷新都失效</li>
 *   <li>已测出的延迟/速度/测试时间不会被刷新清空</li>
 *   <li>订阅里重复出现的同一节点会被去重</li>
 * </ul>
 *
 * <p>本类是纯函数，便于单元测试，不依赖 Spring 与数据库。</p>
 */
public final class NodeReconciler {

    private NodeReconciler() {
    }

    /**
     * @param inserts            新节点（直接落库）
     * @param updates            已有节点（实体已被就地更新，保留 id 与测试结果）
     * @param deletes            库里存在但本次订阅已不存在的节点
     * @param duplicateIncoming  本次订阅里的重复条目数
     * @param duplicateExisting  库里原有的重复行数量
     */
    public record Plan(List<ProxyNode> inserts,
                       List<ProxyNode> updates,
                       List<ProxyNode> deletes,
                       int duplicateIncoming,
                       int duplicateExisting) {
    }

    /** 节点指纹：判断"同一条节点"的依据（不含易变字段，如 originalLink 的参数顺序） */
    public static String fingerprint(ProxyNode node) {
        if (node == null) {
            return "";
        }
        return String.join("|",
                lower(node.getProtocol()),
                lower(node.getServer()),
                String.valueOf(node.getPort()),
                lower(node.getUuid()),
                lower(node.getNetwork()),
                nullToEmpty(node.getPath()));
    }

    public static Plan plan(List<ProxyNode> existing, List<ProxyNode> incoming) {
        Map<String, ProxyNode> existingByKey = new LinkedHashMap<>();
        List<ProxyNode> deletes = new ArrayList<>();
        int duplicateExisting = 0;

        if (existing != null) {
            for (ProxyNode node : existing) {
                String key = fingerprint(node);
                if (existingByKey.putIfAbsent(key, node) != null) {
                    // 库中同一节点多行：保留第一行，其余作为多余行删除
                    duplicateExisting++;
                    deletes.add(node);
                }
            }
        }

        List<ProxyNode> inserts = new ArrayList<>();
        List<ProxyNode> updates = new ArrayList<>();
        Map<String, ProxyNode> seenIncoming = new LinkedHashMap<>();
        int duplicateIncoming = 0;

        if (incoming != null) {
            for (ProxyNode node : incoming) {
                if (node == null) {
                    continue;
                }
                String key = fingerprint(node);
                if (seenIncoming.containsKey(key)) {
                    duplicateIncoming++;
                    continue;
                }
                seenIncoming.put(key, node);

                ProxyNode current = existingByKey.remove(key);
                if (current == null) {
                    inserts.add(node);
                } else {
                    copyMutableFields(current, node);
                    updates.add(current);
                }
            }
        }

        // 订阅中已消失的节点（含库中多余重复行）
        deletes.addAll(existingByKey.values());

        return new Plan(inserts, updates, deletes, duplicateIncoming, duplicateExisting);
    }

    /**
     * 把订阅里解析出的新值覆盖到库中实体上，但保留主键与测试结果字段
     * （latency/speed/lastTestTime/lastTestResult/consecutiveFailures）。
     *
     * <p>这是白名单式拷贝：<b>实体新增字段必须同步加到这里</b>，否则刷新订阅时该字段
     * 永远不会被更新，内核配置会一直沿用首次入库时的旧参数。</p>
     */
    static void copyMutableFields(ProxyNode target, ProxyNode source) {
        target.setName(source.getName());
        target.setProtocol(source.getProtocol());
        target.setServer(source.getServer());
        target.setPort(source.getPort());
        target.setUuid(source.getUuid());
        target.setNetwork(source.getNetwork());
        target.setPath(source.getPath());
        target.setHost(source.getHost());
        target.setTls(source.getTls());
        target.setSni(source.getSni());
        target.setSecurity(source.getSecurity());
        target.setAid(source.getAid());
        target.setFp(source.getFp());
        target.setAlpn(source.getAlpn());
        target.setFlow(source.getFlow());
        target.setEncryption(source.getEncryption());
        target.setPublicKey(source.getPublicKey());
        target.setShortId(source.getShortId());
        target.setSpiderX(source.getSpiderX());
        target.setServiceName(source.getServiceName());
        target.setHeaderType(source.getHeaderType());
        target.setSkipCertVerify(source.isSkipCertVerify());
        target.setCountryCode(source.getCountryCode());
        target.setCountryName(source.getCountryName());
        target.setOriginalLink(source.getOriginalLink());
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
