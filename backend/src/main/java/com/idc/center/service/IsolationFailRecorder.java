package com.idc.center.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.domain.Isolation;
import com.idc.center.domain.IsolationLog;
import com.idc.center.repository.IsolationLogRepository;
import com.idc.center.repository.IsolationRepository;

/**
 * 生效失败的留痕单独放一个 Bean：
 * 生效主事务会因冲突回滚（快照/状态翻转全部撤销），但「失败原因 + FAIL 操作记录」必须在
 * 独立事务里提交成功，值班人员刷新页面仍能看到为什么没生效、可以再次操作。
 */
@Component
public class IsolationFailRecorder {

    private final IsolationRepository isolationRepository;
    private final IsolationLogRepository logRepository;

    public IsolationFailRecorder(IsolationRepository isolationRepository,
                                 IsolationLogRepository logRepository) {
        this.isolationRepository = isolationRepository;
        this.logRepository = logRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistFail(Long isolationId, String reason, String operator) {
        isolationRepository.findById(isolationId).ifPresent(isolation -> {
            isolation.setLastFailReason(reason);
            isolationRepository.saveAndFlush(isolation);
        });
        IsolationLog log = new IsolationLog();
        log.setIsolationId(isolationId);
        log.setAction("FAIL");
        log.setDetail(reason);
        log.setOperator(operator);
        log.setLogTime(LocalDateTime.now());
        logRepository.save(log);
    }
}
