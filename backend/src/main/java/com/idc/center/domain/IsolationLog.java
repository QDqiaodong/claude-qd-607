package com.idc.center.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 隔离操作记录：CREATE 创建 / EFFECT 生效成功 / FAIL 生效失败保持待生效 /
 * RELEASE 解除 / CANCEL 取消。任何生效尝试（包括失败与重复提交）都在这里留痕。
 */
@Entity
@Table(name = "idc_isolation_log")
@Data
public class IsolationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "isolation_id", nullable = false)
    private Long isolationId;

    /** CREATE / EFFECT / FAIL / RELEASE / CANCEL */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "log_time", nullable = false)
    private LocalDateTime logTime;
}
