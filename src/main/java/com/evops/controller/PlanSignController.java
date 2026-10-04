package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ConstructionTaskCreateRequest;
import com.evops.dto.PlanReturnRequest;
import com.evops.dto.PlanSignRequest;
import com.evops.dto.PlanVersionCreateRequest;
import com.evops.entity.ConstructionTask;
import com.evops.entity.PlanSignStep;
import com.evops.entity.RepairPlanVersion;
import com.evops.service.PlanSignService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物修复方案会签：方案版本按文物风险配置签名顺序，顺序会签、
 * 退回只读、修订新版本；全部会签完成后才允许生成施工任务。
 */
@RestController
public class PlanSignController {

    private final PlanSignService planSignService;

    public PlanSignController(PlanSignService planSignService) {
        this.planSignService = planSignService;
    }

    /** 提交方案新版本：按文物风险校验会签顺序，生成会签环节链 */
    @PostMapping("/api/repair-projects/{id}/plan-versions")
    public ApiResponse<RepairPlanVersion> createVersion(
            @PathVariable Long id,
            @Valid @RequestBody PlanVersionCreateRequest request) {
        return ApiResponse.ok(planSignService.createVersion(id, request));
    }

    /** 项目的方案版本列表（含已退回/被取代的只读旧版本） */
    @GetMapping("/api/repair-projects/{id}/plan-versions")
    public ApiResponse<List<RepairPlanVersion>> listVersions(@PathVariable Long id) {
        return ApiResponse.ok(planSignService.listVersions(id));
    }

    /** 版本详情：版本 + 会签环节（意见、签名时间、依据附件） */
    @GetMapping("/api/plan-versions/{versionId}")
    public ApiResponse<Map<String, Object>> versionDetail(@PathVariable Long versionId) {
        return ApiResponse.ok(planSignService.versionDetail(versionId));
    }

    /** 会签签署：仅当前环节指定人可签，记录意见/签名时间/依据附件 */
    @PostMapping("/api/plan-versions/{versionId}/sign")
    public ApiResponse<PlanSignStep> sign(@PathVariable Long versionId,
                                          @Valid @RequestBody PlanSignRequest request) {
        return ApiResponse.ok(planSignService.sign(versionId, request));
    }

    /** 会签退回：任一专家退回即终止本版本，版本只读，只能修订新版本 */
    @PostMapping("/api/plan-versions/{versionId}/return")
    public ApiResponse<PlanSignStep> returnVersion(@PathVariable Long versionId,
                                                   @Valid @RequestBody PlanReturnRequest request) {
        return ApiResponse.ok(planSignService.returnVersion(versionId, request));
    }

    /** 生成施工任务：仅当方案版本会签通过才允许 */
    @PostMapping("/api/repair-projects/{id}/construction-tasks")
    public ApiResponse<ConstructionTask> createTask(
            @PathVariable Long id,
            @Valid @RequestBody ConstructionTaskCreateRequest request) {
        return ApiResponse.ok(planSignService.createTask(id, request));
    }

    /** 项目的施工任务列表 */
    @GetMapping("/api/repair-projects/{id}/construction-tasks")
    public ApiResponse<List<ConstructionTask>> listTasks(@PathVariable Long id) {
        return ApiResponse.ok(planSignService.listTasks(id));
    }
}
