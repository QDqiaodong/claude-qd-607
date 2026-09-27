package com.idc.center.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.Isolation;
import com.idc.center.domain.IsolationLog;
import com.idc.center.domain.IsolationOrder;
import com.idc.center.domain.IsolationServer;
import com.idc.center.domain.Room;
import com.idc.center.domain.ServerAsset;
import com.idc.center.domain.WorkOrder;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.IsolationLogRepository;
import com.idc.center.repository.IsolationOrderRepository;
import com.idc.center.repository.IsolationRepository;
import com.idc.center.repository.IsolationServerRepository;
import com.idc.center.repository.RoomRepository;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.repository.WorkOrderRepository;

/**
 * 模块六：机柜停电检修隔离。
 *
 * 一致性要点：
 * 1. 生效是一个事务：固定服务器快照 + 固化相关施工清单 + 状态置隔离中，任何一步失败整体回滚，
 *    记录保持 PENDING，并写入 last_fail_reason / FAIL 日志，值班人员可以修正后再次生效。
 * 2. 幂等：已经是 ISOLATING / 终态的重复生效请求直接返回现状，绝不重复生成快照
 *    （刷新页面、服务重启后再点都安全）。
 * 3. 并发：同机柜两条 PENDING 同时生效，事务内先复查 + 数据库 uk_isolation_active 唯一索引兜底，
 *    只有一条成功；失败方拿到对方记录号。
 * 4. 冻结：相关工单推进统一走 {@link #assertOrderNotFrozen}，隔离中 PENDING/APPROVED 工单不得推进；
 *    WORKING 工单是硬冲突，在生效前就拦下，不会被悄悄改状态。
 * 5. 解除幂等可重试：只有解除事务提交后冻结才放开；本来因别的规则不能推进的工单不会被误放行。
 */
@Service
public class IsolationService {

    private static final DateTimeFormatter NO_SEQ = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IsolationRepository isolationRepository;
    private final IsolationServerRepository serverSnapshotRepository;
    private final IsolationOrderRepository orderSnapshotRepository;
    private final IsolationLogRepository logRepository;
    private final CabinetRepository cabinetRepository;
    private final RoomRepository roomRepository;
    private final ServerAssetRepository serverRepository;
    private final WorkOrderRepository orderRepository;
    private final IsolationFailRecorder failRecorder;
    /** 自注入代理：生效/解除需要独立事务边界，不能走类内自调用（否则 REQUIRES_NEW 不生效） */
    private final IsolationService self;

    public IsolationService(IsolationRepository isolationRepository,
                            IsolationServerRepository serverSnapshotRepository,
                            IsolationOrderRepository orderSnapshotRepository,
                            IsolationLogRepository logRepository,
                            CabinetRepository cabinetRepository,
                            RoomRepository roomRepository,
                            ServerAssetRepository serverRepository,
                            WorkOrderRepository orderRepository,
                            IsolationFailRecorder failRecorder,
                            @Lazy IsolationService self) {
        this.isolationRepository = isolationRepository;
        this.serverSnapshotRepository = serverSnapshotRepository;
        this.orderSnapshotRepository = orderSnapshotRepository;
        this.logRepository = logRepository;
        this.cabinetRepository = cabinetRepository;
        this.roomRepository = roomRepository;
        this.serverRepository = serverRepository;
        this.orderRepository = orderRepository;
        this.failRecorder = failRecorder;
        this.self = self;
    }

    // ============================== 台账查询 ==============================

    @Transactional(readOnly = true)
    public List<Isolation> list(Dtos.IsolationQuery query) {
        List<Isolation> all = isolationRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        return all.stream()
                .filter(row -> query.getCabinetId() == null || query.getCabinetId().equals(row.getCabinetId()))
                .filter(row -> query.getStatus() == null || query.getStatus().isBlank()
                        || query.getStatus().equals(row.getStatus()))
                .filter(row -> query.getIsolationNo() == null || query.getIsolationNo().isBlank()
                        || row.getIsolationNo().toLowerCase().contains(query.getIsolationNo().toLowerCase().trim()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Views.IsolationDetail detail(Long id) {
        Isolation isolation = mustGet(id);
        Views.IsolationDetail detail = new Views.IsolationDetail();
        detail.setIsolation(isolation);

        boolean snapshotReady = serverSnapshotRepository.existsByIsolationId(id)
                || orderSnapshotRepository.existsByIsolationId(id);
        detail.setSnapshotReady(snapshotReady);

        detail.setServers(buildServerViews(id));
        detail.setOrders(buildOrderViews(id, isolation));
        detail.setLogs(logRepository.findByIsolationIdOrderByIdAsc(id));

        if ("PENDING".equals(isolation.getStatus())) {
            String block = currentConflictMessage(isolation.getCabinetId());
            detail.setPreflightBlocked(block != null);
            detail.setPreflightMessage(block != null ? block : "预检通过：当前无施工中冲突，可以生效");
        }
        return detail;
    }

    /** 机柜页徽章：该机柜正在隔离中的记录（没有返回 null） */
    @Transactional(readOnly = true)
    public Views.ActiveIsolationBanner activeOfCabinet(Long cabinetId) {
        return isolationRepository.findByCabinetIdAndStatus(cabinetId, "ISOLATING")
                .map(this::toBanner).orElse(null);
    }

    /** 工单页/推进前：该工单是否被任何隔离冻结，返回提示语，不冻结返回 null */
    @Transactional(readOnly = true)
    public String frozenReasonForOrder(WorkOrder order) {
        if (order == null || order.getCabinetId() == null || order.getStatus() == null) {
            return null;
        }
        // 已在施工中的工单不可能被隔离挂住（隔离生效时就会被硬冲突拦住）；
        // DONE/REJECTED 是终态，也无需冻结。
        if (!"PENDING".equals(order.getStatus()) && !"APPROVED".equals(order.getStatus())) {
            return null;
        }
        // 目标机柜（上架/迁入/下架都指向被操作机柜）正在隔离 → 冻结
        Optional<Isolation> direct = isolationRepository
                .findByCabinetIdAndStatus(order.getCabinetId(), "ISOLATING");
        if (direct.isPresent()) {
            return "机柜 " + direct.get().getCabinetCode() + " 正在停电检修隔离（"
                    + direct.get().getIsolationNo() + "），隔离解除前该工单不得推进";
        }
        // 迁移/下架工单：服务器当前实际所在机柜正在隔离，也算相关
        if (order.getServerId() != null && ("MOVE".equals(order.getOrderType()) || "OFF".equals(order.getOrderType()))) {
            ServerAsset server = serverRepository.findById(order.getServerId()).orElse(null);
            if (server != null && server.getCabinetId() != null
                    && !server.getCabinetId().equals(order.getCabinetId())) {
                Optional<Isolation> source = isolationRepository
                        .findByCabinetIdAndStatus(server.getCabinetId(), "ISOLATING");
                if (source.isPresent()) {
                    return "服务器所在机柜 " + source.get().getCabinetCode() + " 正在停电检修隔离（"
                            + source.get().getIsolationNo() + "），隔离解除前该工单不得推进";
                }
            }
        }
        return null;
    }

    // ============================== 创建 ==============================

    @Transactional
    public Isolation create(Dtos.IsolationCreateReq req) {
        Cabinet cabinet = cabinetRepository.findById(req.getCabinetId())
                .orElseThrow(() -> new BizException("机柜不存在"));
        LocalDateTime now = LocalDateTime.now();
        if (req.getPlanStart() == null || req.getPlanEnd() == null) {
            throw new BizException("请填写计划起止时间");
        }
        if (!req.getPlanEnd().isAfter(req.getPlanStart())) {
            throw new BizException("计划结束时间必须晚于开始时间");
        }
        if (req.getPlanEnd().isBefore(now)) {
            throw new BizException("计划结束时间不能早于当前时间");
        }

        // 只约束「隔离中」唯一：待生效允许多条（不同值班人员可能各建一条计划），
        // 同时生效时由事务复查 + uk_isolation_active 保证只有一条成功。
        isolationRepository.findByCabinetIdAndStatus(cabinet.getId(), "ISOLATING").ifPresent(row -> {
            throw new BizException("机柜 " + cabinet.getCode() + " 已有隔离中的记录（"
                    + row.getIsolationNo() + "），解除前不能新建隔离");
        });

        Isolation isolation = new Isolation();
        isolation.setIsolationNo(generateNo());
        isolation.setCabinetId(cabinet.getId());
        isolation.setCabinetCode(cabinet.getCode());
        isolation.setCabinetName(cabinet.getName());
        if (cabinet.getRoomId() != null) {
            roomRepository.findById(cabinet.getRoomId())
                    .map(Room::getName).ifPresent(isolation::setRoomName);
        }
        isolation.setPlanStart(req.getPlanStart());
        isolation.setPlanEnd(req.getPlanEnd());
        isolation.setReason(req.getReason().trim());
        isolation.setInitiator(req.getInitiator().trim());
        isolation.setSiteOwner(req.getSiteOwner().trim());
        isolation.setStatus("PENDING");
        isolation.setCreateTime(now);
        isolation.setRemark(req.getRemark());
        Isolation saved = isolationRepository.save(isolation);
        writeLog(saved.getId(), "CREATE",
                "创建隔离计划：" + cabinet.getCode() + "，计划 "
                        + req.getPlanStart() + " ~ " + req.getPlanEnd(),
                saved.getInitiator());
        return saved;
    }

    // ============================== 生效 ==============================

    /**
     * 生效入口：先做无副作用的状态幂等处理，真正的状态翻转进独立事务。
     */
    public Isolation effect(Long id, Dtos.IsolationEffectReq req) {
        Isolation isolation = mustGet(id);

        // 幂等：已隔离中（重复提交/刷新/重启后再点）直接返回，不再生成任何明细
        if ("ISOLATING".equals(isolation.getStatus())) {
            return isolation;
        }
        if ("RELEASED".equals(isolation.getStatus()) || "CANCELLED".equals(isolation.getStatus())) {
            throw new BizException("隔离单已处于「" + IdcRule.isolationStatusName(isolation.getStatus())
                    + "」，不能再生效");
        }
        try {
            return self.effectInNewTransaction(id, req.getOperator());
        } catch (DataIntegrityViolationException dup) {
            // 并发兜底：uk_isolation_active 冲突，主事务已回滚（快照随事务撤销，不留半完成数据）。
            // 查出获胜方，给失败方写明原因并留 FAIL 日志（独立事务），记录仍保持待生效。
            Isolation winner = isolationRepository
                    .findByCabinetIdAndStatus(isolation.getCabinetId(), "ISOLATING")
                    .orElse(null);
            String message = winner != null
                    ? "机柜 " + winner.getCabinetCode() + " 已有隔离记录生效（" + winner.getIsolationNo()
                    + "），本次生效未执行，本单仍为待生效"
                    : "当前机柜已有隔离生效，本次操作未执行";
            failRecorder.persistFail(id, message, req.getOperator());
            throw new BizException(message);
        }
    }

    /**
     * 生效核心：独立事务，失败整体回滚。
     * 步骤顺序——先复查冲突（不落库）→ 写服务器快照 → 写施工清单 → 翻转状态，
     * 任一步抛异常都靠事务回滚，保证不会出现「已生效但快照缺失/施工未冻结」的半完成状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Isolation effectInNewTransaction(Long id, String operator) {
        Isolation isolation = isolationRepository.findById(id)
                .orElseThrow(() -> new BizException("隔离单不存在"));
        if (!"PENDING".equals(isolation.getStatus())) {
            // 并发下已被别的请求处理
            if ("ISOLATING".equals(isolation.getStatus())) {
                return isolation;
            }
            throw new BizException("隔离单当前状态为「"
                    + IdcRule.isolationStatusName(isolation.getStatus()) + "」，不能生效");
        }

        // 1) 并发复查：同柜已有隔离中（唯一索引之外的应用层快速失败）
        Isolation active = isolationRepository
                .findByCabinetIdAndStatus(isolation.getCabinetId(), "ISOLATING").orElse(null);
        if (active != null && !active.getId().equals(id)) {
            String reason = "机柜 " + active.getCabinetCode() + " 已有隔离记录生效（"
                    + active.getIsolationNo() + "），本单不能生效";
            markFail(isolation, reason, operator);
            throw new BizException(reason);
        }

        // 2) 硬冲突：与本柜相关且已进入施工中的工单，明确列出并阻止生效（不改工单状态）
        List<WorkOrder> related = relatedOrders(isolation.getCabinetId());
        List<WorkOrder> working = related.stream()
                .filter(o -> "WORKING".equals(o.getStatus()))
                .toList();
        if (!working.isEmpty()) {
            String detail = working.stream()
                    .map(o -> o.getOrderNo() + "（" + IdcRule.orderTypeName(o.getOrderType())
                            + " " + o.getAssetNo() + "，" + IdcRule.orderStatusName(o.getStatus()) + "）")
                    .collect(Collectors.joining("、"));
            String reason = "存在 " + working.size() + " 条已进入施工中的工单，必须先停工收口后才能隔离：" + detail;
            markFail(isolation, reason, operator);
            throw new BizException(reason);
        }

        // 3) 固定受影响服务器快照：当时仍在架或维修中的机器，带 U 位/业务归属/状态
        List<ServerAsset> servers = serverRepository
                .findByCabinetIdOrderByStartUAsc(isolation.getCabinetId()).stream()
                .filter(s -> "ONLINE".equals(s.getStatus()) || "REPAIR".equals(s.getStatus()))
                .sorted(Comparator.comparing(s -> s.getStartU() == null ? Integer.MAX_VALUE : s.getStartU()))
                .toList();
        List<IsolationServer> snapshots = new ArrayList<>();
        for (ServerAsset server : servers) {
            IsolationServer snap = new IsolationServer();
            snap.setIsolationId(id);
            snap.setServerId(server.getId());
            snap.setAssetNo(server.getAssetNo());
            snap.setBrand(server.getBrand());
            snap.setModel(server.getModel());
            snap.setStartU(server.getStartU());
            snap.setSizeU(server.getSizeU());
            snap.setOwnerName(server.getOwnerName());
            snap.setStatus(server.getStatus());
            snap.setIpAddress(server.getIpAddress());
            snapshots.add(snap);
        }
        serverSnapshotRepository.saveAll(snapshots);

        // 4) 固化相关施工清单（待审批/已批复 = 冻结对象；理论上 WORKING 已在第 2 步拦下）
        List<IsolationOrder> orderSnaps = new ArrayList<>();
        for (WorkOrder order : related) {
            IsolationOrder snap = new IsolationOrder();
            snap.setIsolationId(id);
            snap.setOrderId(order.getId());
            snap.setOrderNo(order.getOrderNo());
            snap.setOrderType(order.getOrderType());
            snap.setAssetNo(order.getAssetNo());
            snap.setCabinetId(order.getCabinetId());
            snap.setStatus(order.getStatus());
            snap.setConflictFlag("WORKING".equals(order.getStatus()) ? 1 : 0);
            snap.setRelation(relationOf(order, isolation.getCabinetId()));
            orderSnaps.add(snap);
        }
        orderSnapshotRepository.saveAll(orderSnaps);

        // 5) 翻转状态。唯一索引 uk_isolation_active 在这里做最后兜底：
        //    并发事务若已先提交，此处抛约束异常，整事务回滚，快照随之回滚（不产生重复快照）。
        isolation.setStatus("ISOLATING");
        isolation.setEffectiveTime(LocalDateTime.now());
        isolation.setLastFailReason(null);
        isolation.setSnapshotCount(snapshots.size());
        try {
            isolationRepository.saveAndFlush(isolation);
        } catch (DataIntegrityViolationException e) {
            throw e;
        }
        writeLog(id, "EFFECT",
                "隔离生效：固定受影响服务器 " + snapshots.size() + " 台，冻结相关施工 "
                        + orderSnaps.size() + " 单（待审批/已批复暂停推进）",
                operator);
        return isolation;
    }

    // ============================== 解除 ==============================

    /** 解除入口：已解除重复点击直接幂等返回；真正收口进独立事务保证可安全重试 */
    public Isolation release(Long id, Dtos.IsolationReleaseReq req) {
        Isolation isolation = mustGet(id);
        if ("RELEASED".equals(isolation.getStatus())) {
            return isolation;
        }
        if (!"ISOLATING".equals(isolation.getStatus())) {
            throw new BizException("只有隔离中的记录可以解除，当前为「"
                    + IdcRule.isolationStatusName(isolation.getStatus()) + "」");
        }
        return self.releaseInNewTransaction(id, req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Isolation releaseInNewTransaction(Long id, Dtos.IsolationReleaseReq req) {
        Isolation isolation = isolationRepository.findById(id)
                .orElseThrow(() -> new BizException("隔离单不存在"));
        if ("RELEASED".equals(isolation.getStatus())) {
            return isolation;
        }
        if (!"ISOLATING".equals(isolation.getStatus())) {
            throw new BizException("只有隔离中的记录可以解除");
        }
        LocalDateTime now = LocalDateTime.now();
        isolation.setStatus("RELEASED");
        isolation.setReleaseTime(now);
        isolation.setReleaseNote(req.getNote().trim());
        isolation.setReleaseUser(req.getOperator().trim());
        isolationRepository.saveAndFlush(isolation);
        // 状态翻成 RELEASED 后 active_flag 立即变 NULL，frozenReasonForOrder 自然放开；
        // 快照表保持不动，历史影响范围仍可查。
        writeLog(id, "RELEASE", "隔离解除：" + req.getNote().trim(), req.getOperator().trim());
        return isolation;
    }

    // ============================== 取消 ==============================

    @Transactional
    public Isolation cancel(Long id, Dtos.IsolationCancelReq req) {
        Isolation isolation = mustGet(id);
        if (!IdcRule.canCancelIsolation(isolation.getStatus())) {
            throw new BizException("只有待生效记录可以取消，当前为「"
                    + IdcRule.isolationStatusName(isolation.getStatus()) + "」");
        }
        // 待生效从未写过快照/冻结清单（生效失败会整体回滚），无需清理
        isolation.setStatus("CANCELLED");
        isolation.setCancelTime(LocalDateTime.now());
        isolation.setCancelReason(req.getReason().trim());
        isolationRepository.save(isolation);
        writeLog(id, "CANCEL", "取消隔离计划：" + req.getReason().trim(), req.getOperator().trim());
        return isolation;
    }

    // ============================== 工单推进前置校验 ==============================

    /**
     * 工单推进前由 OrderService 调用：隔离中的机柜相关工单一律不得推进。
     * 注意只拦 PENDING→APPROVED、APPROVED→WORKING 两步；WORKING 工单在隔离生效时已被硬冲突排除，
     * 因此这里不会出现「把施工中工单退回」的路径，也不改工单任何字段。
     */
    public void assertOrderNotFrozen(WorkOrder order) {
        String reason = frozenReasonForOrder(order);
        if (reason != null) {
            throw new BizException(reason);
        }
    }

    // ============================== 内部拼装 ==============================

    private Isolation mustGet(Long id) {
        return isolationRepository.findById(id)
                .orElseThrow(() -> new BizException("隔离记录不存在"));
    }

    private String generateNo() {
        String no = "ISO" + NO_SEQ.format(LocalDateTime.now());
        // 秒级序列撞号时尾缀补 A/B…（台账并发极低，保险而已）
        String candidate = no;
        int suffix = 0;
        while (isolationRepository.existsByIsolationNo(candidate)) {
            suffix++;
            candidate = no + (char) ('A' + suffix - 1);
        }
        return candidate;
    }

    /**
     * 与该机柜相关且还活着的施工：
     *  - 工单目标柜就是本柜（上架/迁入/本柜下架）；
     *  - 下架/迁移工单的服务器此刻实际还在本柜（工单申请后未施工，资产尚未按目标柜变化）。
     */
    private List<WorkOrder> relatedOrders(Long cabinetId) {
        Map<Long, WorkOrder> indexed = new HashMap<>();
        for (WorkOrder order : orderRepository.findByCabinetIdAndStatusIn(
                cabinetId, IdcRule.FROZEN_ORDER_STATUS)) {
            indexed.put(order.getId(), order);
        }
        for (WorkOrder order : orderRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))) {
            if (indexed.containsKey(order.getId()) || order.getStatus() == null
                    || !IdcRule.FROZEN_ORDER_STATUS.contains(order.getStatus())
                    || order.getServerId() == null) {
                continue;
            }
            if ("MOVE".equals(order.getOrderType()) || "OFF".equals(order.getOrderType())) {
                ServerAsset server = serverRepository.findById(order.getServerId()).orElse(null);
                if (server != null && cabinetId.equals(server.getCabinetId())) {
                    indexed.put(order.getId(), order);
                }
            }
        }
        return new ArrayList<>(indexed.values());
    }

    private String relationOf(WorkOrder order, Long cabinetId) {
        if (cabinetId.equals(order.getCabinetId())) {
            return "IN_CAB";
        }
        return "INTO_CAB";
    }

    /** 生效前实时预检：只检查施工中硬冲突 */
    private String currentConflictMessage(Long cabinetId) {
        List<WorkOrder> working = relatedOrders(cabinetId).stream()
                .filter(o -> "WORKING".equals(o.getStatus()))
                .toList();
        if (working.isEmpty()) {
            return null;
        }
        return "存在 " + working.size() + " 条施工中工单："
                + working.stream().map(WorkOrder::getOrderNo).collect(Collectors.joining("、"))
                + "，需先收口后才能生效";
    }

    private void markFail(Isolation isolation, String reason, String operator) {
        // 主流程随后抛异常回滚服务器/工单快照与状态翻转；失败原因与 FAIL 日志用独立事务落库，
        // 保证值班人员刷新后仍能看到「为什么没生效、可以再次操作」。
        failRecorder.persistFail(isolation.getId(), reason, operator);
    }

    private void writeLog(Long isolationId, String action, String detail, String operator) {
        IsolationLog log = new IsolationLog();
        log.setIsolationId(isolationId);
        log.setAction(action);
        log.setDetail(detail);
        log.setOperator(operator);
        log.setLogTime(LocalDateTime.now());
        logRepository.save(log);
    }

    private Views.ActiveIsolationBanner toBanner(Isolation isolation) {
        Views.ActiveIsolationBanner banner = new Views.ActiveIsolationBanner();
        banner.setIsolationId(isolation.getId());
        banner.setIsolationNo(isolation.getIsolationNo());
        banner.setCabinetId(isolation.getCabinetId());
        banner.setCabinetCode(isolation.getCabinetCode());
        banner.setReason(isolation.getReason());
        banner.setSiteOwner(isolation.getSiteOwner());
        banner.setPlanStart(isolation.getPlanStart());
        banner.setPlanEnd(isolation.getPlanEnd());
        banner.setEffectiveTime(isolation.getEffectiveTime());
        return banner;
    }

    // ============================== 详情视图拼装（快照 + 当前差异） ==============================

    private List<Views.IsolationServerView> buildServerViews(Long id) {
        List<IsolationServer> snaps = serverSnapshotRepository.findByIsolationIdOrderByStartUAsc(id);
        if (snaps.isEmpty()) {
            return List.of();
        }
        Long cabinetId = snapCabinetId(id);
        List<Long> ids = snaps.stream().map(IsolationServer::getServerId)
                .filter(java.util.Objects::nonNull).toList();
        Map<Long, ServerAsset> currentMap = ids.isEmpty() ? Map.of()
                : serverRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ServerAsset::getId, s -> s));

        List<Views.IsolationServerView> views = new ArrayList<>();
        for (IsolationServer snap : snaps) {
            Views.IsolationServerView view = new Views.IsolationServerView();
            view.setServerId(snap.getServerId());
            view.setAssetNo(snap.getAssetNo());
            view.setBrand(snap.getBrand());
            view.setModel(snap.getModel());
            view.setStartU(snap.getStartU());
            view.setSizeU(snap.getSizeU());
            view.setOwnerName(snap.getOwnerName());
            view.setStatus(snap.getStatus());
            view.setIpAddress(snap.getIpAddress());

            List<String> diffs = new ArrayList<>();
            ServerAsset current = snap.getServerId() == null ? null : currentMap.get(snap.getServerId());
            if (current == null) {
                view.setCurrentMissing(true);
                view.setDiffState("REMOVED");
                diffs.add("资产已不存在");
            } else {
                view.setCurrentStartU(current.getStartU());
                view.setCurrentSizeU(current.getSizeU());
                view.setCurrentOwnerName(current.getOwnerName());
                view.setCurrentStatus(current.getStatus());
                view.setCurrentCabinetId(current.getCabinetId());
                if (!java.util.Objects.equals(snap.getStartU(), current.getStartU())
                        || !java.util.Objects.equals(snap.getSizeU(), current.getSizeU())) {
                    diffs.add("U 位：" + snap.getStartU() + "U 起 → " + current.getStartU() + "U 起");
                }
                if (!java.util.Objects.equals(snap.getStatus(), current.getStatus())) {
                    diffs.add("状态：" + IdcRule.serverStatusName(snap.getStatus()) + " → "
                            + IdcRule.serverStatusName(current.getStatus()));
                }
                if (!java.util.Objects.equals(str(snap.getOwnerName()), str(current.getOwnerName()))) {
                    diffs.add("业务归属：" + str(snap.getOwnerName()) + " → " + str(current.getOwnerName()));
                }
                if (current.getCabinetId() != null && cabinetId != null
                        && !cabinetId.equals(current.getCabinetId())) {
                    diffs.add("已迁出本机柜（当前机柜 ID：" + current.getCabinetId() + "）");
                }
                if (!diffs.isEmpty()) {
                    view.setDiffState("CHANGED");
                } else if (current.getCabinetId() != null && cabinetId != null
                        && !cabinetId.equals(current.getCabinetId())) {
                    view.setDiffState("REMOVED");
                } else {
                    view.setDiffState("SAME");
                }
            }
            view.setDiffs(diffs);
            views.add(view);
        }
        return views;
    }

    /** 取隔离单机柜 id（buildServerViews 比对迁出用，简单查一次） */
    private Long snapCabinetId(Long isolationId) {
        return isolationRepository.findById(isolationId).map(Isolation::getCabinetId).orElse(null);
    }

    private List<Views.IsolationOrderView> buildOrderViews(Long id, Isolation isolation) {
        List<IsolationOrder> snaps = orderSnapshotRepository.findByIsolationIdOrderByIdAsc(id);
        if (snaps.isEmpty()) {
            return List.of();
        }
        Map<Long, WorkOrder> currentMap = orderRepository.findAllById(
                snaps.stream().map(IsolationOrder::getOrderId).toList()).stream()
                .collect(Collectors.toMap(WorkOrder::getId, o -> o));

        List<Views.IsolationOrderView> views = new ArrayList<>();
        for (IsolationOrder snap : snaps) {
            Views.IsolationOrderView view = new Views.IsolationOrderView();
            view.setOrderId(snap.getOrderId());
            view.setOrderNo(snap.getOrderNo());
            view.setOrderType(snap.getOrderType());
            view.setAssetNo(snap.getAssetNo());
            view.setCabinetId(snap.getCabinetId());
            view.setStatus(snap.getStatus());
            view.setConflictFlag(snap.getConflictFlag());
            view.setRelation(snap.getRelation());

            WorkOrder current = currentMap.get(snap.getOrderId());
            if (current == null) {
                view.setCurrentMissing(true);
                view.setFrozenNow(false);
            } else {
                view.setCurrentStatus(current.getStatus());
                boolean frozen = "ISOLATING".equals(isolation.getStatus())
                        && ("PENDING".equals(current.getStatus()) || "APPROVED".equals(current.getStatus()));
                view.setFrozenNow(frozen);
                view.setFrozenReason(frozen ? "隔离中，暂停推进" : null);
            }
            views.add(view);
        }
        return views;
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }
}
