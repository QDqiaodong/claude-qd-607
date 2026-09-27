package com.idc.center.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 隔离生效瞬间固定下来的受影响服务器快照：
 * 当时仍在架（ONLINE）或维修中（REPAIR）的服务器，连同 U 位、业务归属、状态一并冻结。
 * 只在生效事务里写一次，之后资产再怎么上下架迁移都不改这张表 —— 历史事实不靠实时查询还原。
 */
@Entity
@Table(name = "idc_isolation_server")
@Data
public class IsolationServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "isolation_id", nullable = false)
    private Long isolationId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "asset_no", nullable = false, length = 30)
    private String assetNo;

    @Column(name = "brand", length = 30)
    private String brand;

    @Column(name = "model", length = 40)
    private String model;

    @Column(name = "start_u")
    private Integer startU;

    @Column(name = "size_u")
    private Integer sizeU;

    @Column(name = "owner_name", length = 50)
    private String ownerName;

    /** 生效当时的状态：ONLINE 在架 / REPAIR 维修 */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "ip_address", length = 40)
    private String ipAddress;
}
