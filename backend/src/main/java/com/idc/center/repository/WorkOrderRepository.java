package com.idc.center.repository;

import java.util.Collection;
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

    /** 与某机柜相关、且仍会推进的工单：目标机柜是它（上架/迁入），或下架/迁出它（server 当前在该柜） */
    List<WorkOrder> findByCabinetIdAndStatusIn(Long cabinetId, Collection<String> statuses);
}
