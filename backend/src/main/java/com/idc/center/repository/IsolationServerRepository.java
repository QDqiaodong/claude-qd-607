package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.IsolationServer;

@Repository
public interface IsolationServerRepository extends JpaRepository<IsolationServer, Long> {

    List<IsolationServer> findByIsolationIdOrderByStartUAsc(Long isolationId);

    boolean existsByIsolationId(Long isolationId);
}
