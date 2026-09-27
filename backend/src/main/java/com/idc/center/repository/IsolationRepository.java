package com.idc.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.Isolation;

@Repository
public interface IsolationRepository extends JpaRepository<Isolation, Long> {

    /** 该机柜当前是否已有隔离中的记录（生效事务内调用，配合行锁/唯一索引兜底并发） */
    Optional<Isolation> findByCabinetIdAndStatus(Long cabinetId, String status);

    boolean existsByIsolationNo(String isolationNo);

    List<Isolation> findByCabinetIdOrderByIdDesc(Long cabinetId);

    List<Isolation> findByStatusIn(Iterable<String> statuses, Sort sort);
}
