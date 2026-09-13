package com.proxyviewer.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NodeTestRecordRepository extends JpaRepository<NodeTestRecord, Long> {
}
