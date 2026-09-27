package com.idc.center.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 隔离生效时与该机柜相关的上架 / 迁移施工快照：
 * conflict_flag=1 表示生效当时已处于 WORKING 施工中 —— 硬冲突，直接阻止隔离生效；
 * conflict_flag=0 的是 PENDING 待审批 / APPROVED 已批复工单，隔离期间冻结，不得继续推进，
 * 解除隔离后才恢复正常推进（若因别的业务规则本就不能推进，不会被误放行）。
 */
@Entity
@Table(name = "idc_isolation_order")
@Data
public class IsolationOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "isolation_id", nullable = false)
    private Long isolationId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;

    /** ON 上架 / OFF 下架 / MOVE 迁移 */
    @Column(name = "order_type", length = 20)
    private String orderType;

    @Column(name = "asset_no", length = 30)
    private String assetNo;

    @Column(name = "cabinet_id")
    private Long cabinetId;

    /** 生效当时的工单状态快照 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 1=施工中硬冲突；0=待审批/已批复，隔离期冻结 */
    @Column(name = "conflict_flag", nullable = false)
    private Integer conflictFlag;

    /** IN_CAB 本柜施工（下架/迁出）/ INTO_CAB 迁入或上架进本柜 */
    @Column(name = "relation", length = 20)
    private String relation;
}
