package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.WorkOrder;

/**
 * 工单：列表同样走 Query by Example（按类型/状态精确匹配，单号、申请人模糊匹配）。
 */
@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    List<WorkOrder> findByServerIdOrderByIdDesc(Long serverId);

    boolean existsByOrderNo(String orderNo);
}
