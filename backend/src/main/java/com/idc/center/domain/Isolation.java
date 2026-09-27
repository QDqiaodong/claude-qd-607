package com.idc.center.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块六：机柜停电检修隔离单。
 * 状态流转：PENDING 待生效 → ISOLATING 隔离中 → RELEASED 已解除；
 *           PENDING 可被取消为 CANCELLED 已取消，其余状态不可取消。
 * 同一机柜任一时刻最多一条 ISOLATING，由数据库唯一索引 uk_isolation_active 兜底。
 */
@Entity
@Table(name = "idc_isolation")
@DynamicUpdate
@Data
public class Isolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "isolation_no", nullable = false, length = 30)
    private String isolationNo;

    @Column(name = "cabinet_id", nullable = false)
    private Long cabinetId;

    @Column(name = "cabinet_code", nullable = false, length = 20)
    private String cabinetCode;

    @Column(name = "cabinet_name", length = 50)
    private String cabinetName;

    @Column(name = "room_name", length = 50)
    private String roomName;

    @Column(name = "plan_start", nullable = false)
    private LocalDateTime planStart;

    @Column(name = "plan_end", nullable = false)
    private LocalDateTime planEnd;

    @Column(name = "reason", nullable = false, length = 300)
    private String reason;

    /** 发起人 */
    @Column(name = "initiator", nullable = false, length = 30)
    private String initiator;

    /** 现场负责人 */
    @Column(name = "site_owner", nullable = false, length = 30)
    private String siteOwner;

    /** PENDING / ISOLATING / RELEASED / CANCELLED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "effective_time")
    private LocalDateTime effectiveTime;

    @Column(name = "release_time")
    private LocalDateTime releaseTime;

    @Column(name = "release_note", length = 300)
    private String releaseNote;

    @Column(name = "release_user", length = 30)
    private String releaseUser;

    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    /** 最近一次生效失败原因：保持待生效时给值班人员可再次操作的明确提示 */
    @Column(name = "last_fail_reason", length = 500)
    private String lastFailReason;

    @Column(name = "snapshot_count")
    private Integer snapshotCount;

    /** 生成列（ISOLATING=1，其余 NULL），Hibernate 只读不写 */
    @Column(name = "active_flag", insertable = false, updatable = false)
    private Integer activeFlag;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "remark", length = 200)
    private String remark;
}
