package com.idc.center.dto;

import java.math.BigDecimal;
import java.util.List;

import com.idc.center.domain.Cabinet;
import com.idc.center.domain.ServerAsset;

import lombok.Data;

/**
 * 读模型：把「给页面看」的组合数据在这里拼好，Controller 直接返回。
 */
public final class Views {

    private Views() {
    }

    /** 机架图上的一个格子：占 1U 或多 U（服务器占几个 U 就跨几格） */
    @Data
    public static class RackSlot {
        /** 格子顶部 U 位（渲染时按 U 位倒序，从 42U 画到 1U） */
        private Integer topU;
        /** 起始 U 位（最下面那格） */
        private Integer startU;
        private Integer sizeU;
        /** FREE / OCCUPIED / REPAIR / SCRAP */
        private String state;
        private Long serverId;
        private String assetNo;
        private String brand;
        private String model;
        private String serialNo;
        private String cpuModel;
        private Integer memoryGb;
        private String diskSpec;
        private String ipAddress;
        private String ownerName;
        private String status;

        public static RackSlot free(Integer u) {
            RackSlot slot = new RackSlot();
            slot.setTopU(u);
            slot.setStartU(u);
            slot.setSizeU(1);
            slot.setState("FREE");
            return slot;
        }

        public static RackSlot of(ServerAsset server) {
            RackSlot slot = new RackSlot();
            slot.setServerId(server.getId());
            slot.setStartU(server.getStartU());
            int size = server.getSizeU() == null || server.getSizeU() < 1 ? 1 : server.getSizeU();
            slot.setSizeU(size);
            slot.setTopU(server.getStartU() + size - 1);
            slot.setState(stateOf(server.getStatus()));
            slot.setAssetNo(server.getAssetNo());
            slot.setBrand(server.getBrand());
            slot.setModel(server.getModel());
            slot.setSerialNo(server.getSerialNo());
            slot.setCpuModel(server.getCpuModel());
            slot.setMemoryGb(server.getMemoryGb());
            slot.setDiskSpec(server.getDiskSpec());
            slot.setIpAddress(server.getIpAddress());
            slot.setOwnerName(server.getOwnerName());
            slot.setStatus(server.getStatus());
            return slot;
        }

        private static String stateOf(String status) {
            if ("REPAIR".equals(status)) {
                return "REPAIR";
            }
            if ("SCRAP".equals(status)) {
                return "SCRAP";
            }
            return "OCCUPIED";
        }
    }

    /** 一个机柜的机架图 */
    @Data
    public static class RackView {
        private Long cabinetId;
        private String cabinetCode;
        private String cabinetName;
        private Long roomId;
        private String roomName;
        private Integer totalU;
        private BigDecimal ratedPower;
        private Integer usedU;
        private Integer freeU;
        private List<RackSlot> slots;
        /** 该机柜正在隔离时的徽章（无则字段为 null） */
        private ActiveIsolationBanner activeIsolation;
    }

    /** 机柜 + 机房名（列表 / 下拉框用） */
    @Data
    public static class CabinetView {
        private Long id;
        private String code;
        private String name;
        private Long roomId;
        private String roomName;
        private Integer rowNo;
        private Integer colNo;
        private Integer totalU;
        private BigDecimal ratedPower;
        private Integer usedU;
        private String remark;
        /** 该机柜是否正在停电检修隔离（机柜树/列表挂徽章用） */
        private boolean isolating;
        private String isolationNo;
        private String isolationReason;

        public static CabinetView of(Cabinet cabinet, String roomName, Integer usedU) {
            CabinetView view = new CabinetView();
            view.setId(cabinet.getId());
            view.setCode(cabinet.getCode());
            view.setName(cabinet.getName());
            view.setRoomId(cabinet.getRoomId());
            view.setRoomName(roomName);
            view.setRowNo(cabinet.getRowNo());
            view.setColNo(cabinet.getColNo());
            view.setTotalU(cabinet.getTotalU());
            view.setRatedPower(cabinet.getRatedPower());
            view.setUsedU(usedU);
            view.setRemark(cabinet.getRemark());
            return view;
        }
    }

    /** 顶部汇总卡：能耗页用 */
    @Data
    public static class EnergySummary {
        private String month;
        private BigDecimal totalUsage;
        private BigDecimal totalFee;
        private Integer recordCount;
        private Integer cabinetCount;
        private List<CabinetUsageRow> ranking;
    }

    /** 按机柜分组的用电排行 */
    @Data
    public static class CabinetUsageRow {
        private Long cabinetId;
        private String cabinetCode;
        private BigDecimal usageKwh;
        private BigDecimal fee;
    }

    /** 巡检页顶部汇总 */
    @Data
    public static class InspectionSummary {
        private Long total;
        private Long abnormal;
        private Long unhandled;
    }

    /** 分页结果：比直接序列化 Spring Data 的 Page 干净，前端只看 content / total */
    @Data
    public static class PageResult<T> {
        private List<T> content;
        private long total;
        private int page;
        private int size;

        public static <T> PageResult<T> of(List<T> content, long total, int page, int size) {
            PageResult<T> result = new PageResult<>();
            result.setContent(content);
            result.setTotal(total);
            result.setPage(page);
            result.setSize(size);
            return result;
        }
    }

    // ============================ 模块六：机柜停电检修隔离 ============================

    /** 隔离服务器快照行：快照字段不可变，current* 字段为实时查询结果，仅用于展示差异 */
    @Data
    public static class IsolationServerView {
        /** 快照部分（生效当时固定，永不被资产变更改写） */
        private Long serverId;
        private String assetNo;
        private String brand;
        private String model;
        private Integer startU;
        private Integer sizeU;
        private String ownerName;
        private String status;
        private String ipAddress;

        /** 当前实况：null 表示这台资产已查不到（删除/下架消失） */
        private Integer currentStartU;
        private Integer currentSizeU;
        private String currentOwnerName;
        private String currentStatus;
        private Long currentCabinetId;
        private boolean currentMissing;

        /** SAME 一致 / CHANGED 与快照有差异 / REMOVED 已不在该机柜或已删除 */
        private String diffState;
        private List<String> diffs;
    }

    /** 隔离相关施工行：冻结当时快照 + 当前状态，差异用于提示，是否冻结以后端实时判定为准 */
    @Data
    public static class IsolationOrderView {
        private Long orderId;
        private String orderNo;
        private String orderType;
        private String assetNo;
        private Long cabinetId;
        /** 生效当时状态快照 */
        private String status;
        private Integer conflictFlag;
        private String relation;

        /** 当前工单状态（实时），null 表示工单已不存在 */
        private String currentStatus;
        private boolean currentMissing;
        /** 后端按当前隔离状态 + 工单状态实时计算：此刻推进是否会被拦 */
        private boolean frozenNow;
        private String frozenReason;
    }

    /** 隔离详情：计划信息 / 快照 / 当前差异 / 冲突施工 / 操作记录五块分清 */
    @Data
    public static class IsolationDetail {
        private com.idc.center.domain.Isolation isolation;
        private List<IsolationServerView> servers;
        private List<IsolationOrderView> orders;
        private List<com.idc.center.domain.IsolationLog> logs;
        /** 快照是否已生成（待生效且从未成功生效时为 false） */
        private boolean snapshotReady;
        /** 实时预检结果：当前再点生效会不会过（待生效记录页用来提示） */
        private String preflightMessage;
        private boolean preflightBlocked;
    }

    /** 机柜/工单页面上挂的「正在隔离」小徽章数据 */
    @Data
    public static class ActiveIsolationBanner {
        private Long isolationId;
        private String isolationNo;
        private Long cabinetId;
        private String cabinetCode;
        private String reason;
        private String siteOwner;
        private java.time.LocalDateTime planStart;
        private java.time.LocalDateTime planEnd;
        private java.time.LocalDateTime effectiveTime;
    }
}
