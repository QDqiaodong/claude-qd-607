package com.idc.center.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.Isolation;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.IsolationService;

import jakarta.validation.Valid;

/**
 * 模块六：机柜停电检修隔离。
 * 台账 / 创建 / 详情 / 生效 / 解除 / 取消，以及供机柜页、工单页使用的「正在隔离」徽章查询。
 */
@RestController
@RequestMapping("/api")
public class IsolationController {

    private final IsolationService isolationService;

    public IsolationController(IsolationService isolationService) {
        this.isolationService = isolationService;
    }

    /** 隔离台账（不分页，数据量小，直接全量过滤，前端做展示） */
    @GetMapping("/isolations")
    public ApiResponse<List<Isolation>> list(Dtos.IsolationQuery query) {
        return ApiResponse.success(isolationService.list(query));
    }

    @PostMapping("/isolations")
    public ApiResponse<Isolation> create(@Valid @RequestBody Dtos.IsolationCreateReq req) {
        return ApiResponse.success("隔离计划已创建，待生效", isolationService.create(req));
    }

    /** 详情：计划信息 + 生效快照 + 当前差异 + 冲突施工 + 操作记录 */
    @GetMapping("/isolations/{id}")
    public ApiResponse<Views.IsolationDetail> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(isolationService.detail(id));
    }

    /** 生效：固定快照并冻结相关施工；重复提交幂等，冲突时整单保持待生效 */
    @PostMapping("/isolations/{id}/effect")
    public ApiResponse<Isolation> effect(@PathVariable("id") Long id,
                                         @Valid @RequestBody Dtos.IsolationEffectReq req) {
        Isolation result = isolationService.effect(id, req);
        if ("ISOLATING".equals(result.getStatus()) && result.getEffectiveTime() != null) {
            return ApiResponse.success("隔离已生效，相关施工已冻结", result);
        }
        return ApiResponse.success("该隔离已处于隔离中，未重复执行", result);
    }

    /** 解除：负责人必须填写解除说明；重复提交幂等 */
    @PostMapping("/isolations/{id}/release")
    public ApiResponse<Isolation> release(@PathVariable("id") Long id,
                                          @Valid @RequestBody Dtos.IsolationReleaseReq req) {
        return ApiResponse.success("隔离已解除，相关施工恢复正常推进", isolationService.release(id, req));
    }

    /** 取消：只有待生效可以取消 */
    @PostMapping("/isolations/{id}/cancel")
    public ApiResponse<Isolation> cancel(@PathVariable("id") Long id,
                                         @Valid @RequestBody Dtos.IsolationCancelReq req) {
        return ApiResponse.success("隔离计划已取消", isolationService.cancel(id, req));
    }

    /** 机柜页徽章：该机柜是否正在隔离 */
    @GetMapping("/cabinets/{id}/active-isolation")
    public ApiResponse<Views.ActiveIsolationBanner> activeOfCabinet(@PathVariable("id") Long id) {
        return ApiResponse.success(isolationService.activeOfCabinet(id));
    }
}
