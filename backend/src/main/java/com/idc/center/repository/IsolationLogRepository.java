package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.IsolationLog;

@Repository
public interface IsolationLogRepository extends JpaRepository<IsolationLog, Long> {

    List<IsolationLog> findByIsolationIdOrderByIdAsc(Long isolationId);
}
