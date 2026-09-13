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
}
