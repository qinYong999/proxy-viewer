package com.proxyviewer.model;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProxyNodeRepository extends JpaRepository<ProxyNode, Long> {

    Page<ProxyNode> findByCountryName(String countryName, Pageable pageable);

    long countByProtocol(String protocol);

    long countByNetwork(String network);

    long countByProtocolAndCountryName(String protocol, String countryName);

    long countByNetworkAndCountryName(String network, String countryName);

    // ---- 测试结果统计（配合"失败只打标记"的新策略） ----

    long countByLastTestResult(String result);

    long countByLastTestResultStartingWith(String prefix);

    long countByLastTestResultIsNull();

    long countByCountryNameAndLastTestResult(String countryName, String result);

    long countByCountryNameAndLastTestResultStartingWith(String countryName, String prefix);

    long countByCountryNameAndLastTestResultIsNull(String countryName);

    List<ProxyNode> findByLastTestResultStartingWith(String prefix);

    @Query("SELECT DISTINCT p.countryName FROM ProxyNode p WHERE p.countryName IS NOT NULL AND p.countryName <> '' ORDER BY p.countryName")
    List<String> findDistinctCountryNames();

    /**
     * 按真实延迟排序（含国家筛选）。
     *
     * <p>{@code latency_ms = -1} 是"未测/不可达"的哨兵值，数值上最小；
     * 如果直接按数值排，升序时失败节点会全部顶到最前面。这里用 CASE 把负值和 NULL
     * 归为"无效延迟"并永远排在有效延迟之后，再按数值与 id 稳定排序。</p>
     *
     * @param country 国家名；传 {@code null} 表示不筛选
     * @param asc     true = 延迟从低到高
     */
    @Query(value = """
            SELECT * FROM proxy_nodes p
            WHERE (:country IS NULL OR p.country_name = :country)
            ORDER BY
              CASE
                WHEN p.latency_ms IS NULL OR p.latency_ms < 0
                  THEN CASE WHEN :asc = true THEN 1 ELSE 0 END
                ELSE CASE WHEN :asc = true THEN 0 ELSE 1 END
              END,
              CASE WHEN :asc = true THEN p.latency_ms END ASC,
              CASE WHEN :asc = false THEN p.latency_ms END DESC,
              p.id ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM proxy_nodes p
            WHERE (:country IS NULL OR p.country_name = :country)
            """,
            nativeQuery = true)
    Page<ProxyNode> findByLatencyOrder(String country, boolean asc, Pageable pageable);
}
