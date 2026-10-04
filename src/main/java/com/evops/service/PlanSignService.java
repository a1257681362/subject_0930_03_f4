package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.ConstructionTaskCreateRequest;
import com.evops.dto.PlanAttachmentDto;
import com.evops.dto.PlanReturnRequest;
import com.evops.dto.PlanSignRequest;
import com.evops.dto.PlanSignerDto;
import com.evops.dto.PlanVersionCreateRequest;
import com.evops.entity.ConstructionTask;
import com.evops.entity.PlanSignAttachment;
import com.evops.entity.PlanSignStep;
import com.evops.entity.Relic;
import com.evops.entity.RepairPlanVersion;
import com.evops.entity.RepairProject;
import com.evops.mapper.ConstructionTaskMapper;
import com.evops.mapper.PlanSignAttachmentMapper;
import com.evops.mapper.PlanSignStepMapper;
import com.evops.mapper.RepairPlanVersionMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物修复方案会签：
 *
 * <p>方案按版本管理。会签顺序按文物风险（保存等级）配置：
 * 保管人员 -&gt; 馆内专家 -&gt; 外部专家；一级文物三类角色缺一不可，
 * 二/三级文物外部专家可选（若参与必排在最后）。只能按顺序签署，
 * 缺席专家不能由管理员或他人代签。</p>
 *
 * <p>任一专家退回后版本只读，只能修订产生新版本；旧版本与已签署事实
 * 永久保留可查。每次签署/退回记录意见、签名时间与依据附件。</p>
 *
 * <p>会签截止同一口径：签署/退回时间不晚于 sign_deadline（含临界时刻）
 * 才归属本版本；超期后任何角色都不能再签署或退回，只能修订新版本。
 * 全部会签完成（APPROVED）才允许生成施工任务。</p>
 */
@Service
public class PlanSignService {

    /** 会签角色顺序口径：保管人员 -> 馆内专家 -> 外部专家 */
    private static final List<String> ROLE_ORDER =
            Arrays.asList(ROLE_CURATOR, ROLE_INTERNAL_EXPERT, ROLE_EXTERNAL_EXPERT);

    private final RepairPlanVersionMapper versionMapper;
    private final PlanSignStepMapper stepMapper;
    private final PlanSignAttachmentMapper attachmentMapper;
    private final ConstructionTaskMapper taskMapper;
    private final RepairProjectMapper projectMapper;
    private final RelicService relicService;
    private final LedgerService ledgerService;

    public PlanSignService(RepairPlanVersionMapper versionMapper,
                           PlanSignStepMapper stepMapper,
                           PlanSignAttachmentMapper attachmentMapper,
                           ConstructionTaskMapper taskMapper,
                           RepairProjectMapper projectMapper,
                           RelicService relicService,
                           LedgerService ledgerService) {
        this.versionMapper = versionMapper;
        this.stepMapper = stepMapper;
        this.attachmentMapper = attachmentMapper;
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
    }

    // --------------------------------------------------------------------
    // 提交方案版本
    // --------------------------------------------------------------------

    /**
     * 提交方案新版本：按文物风险校验会签顺序；项目内至多一个会签中版本，
     * 超期未完成的会签中版本随新版本提交自动归档为「已被取代」（只读）。
     */
    @Transactional
    public RepairPlanVersion createVersion(Long projectId, PlanVersionCreateRequest req) {
        RepairProject project = requireProject(projectId);
        if (!PROJECT_PLANNED.equals(project.getStatus())
                && !PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException(
                    "项目已闭环或已作废，不能再提交方案会签: " + project.getProjectNo());
        }
        Relic relic = relicService.requireActiveRelic(project.getRelicId());
        String riskLevel = relic.getPreservationGrade();
        validateSignerChain(riskLevel, req.getSigners());

        if (req.getBaseVersionId() != null) {
            RepairPlanVersion base = requireVersion(req.getBaseVersionId());
            if (!projectId.equals(base.getProjectId())) {
                throw new IllegalArgumentException("修订来源版本不属于本项目: " + req.getBaseVersionId());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 项目内至多一个会签中版本；超期（含临界口径：当前时间晚于截止时刻）的自动归档只读
        RepairPlanVersion active = findVersionByStatus(projectId, PLAN_SIGNING);
        if (active != null) {
            if (!isOverdue(active, now)) {
                throw new IllegalArgumentException(
                        "项目存在会签中的方案版本（第 " + active.getVersionNo()
                                + " 版），须待其退回或超期后才能修订新版本");
            }
            active.setStatus(PLAN_SUPERSEDED);
            versionMapper.updateById(active);
            ledgerService.append(active.getRelicId(), projectId, null,
                    ACTION_PLAN_SUPERSEDE, planBizNo(project, active),
                    "方案第 " + active.getVersionNo() + " 版会签超期（截止 "
                            + active.getSignDeadline() + "），随新版本提交归档只读", now);
        }

        RepairPlanVersion version = new RepairPlanVersion();
        version.setProjectId(projectId);
        version.setRelicId(relic.getId());
        version.setVersionNo(nextVersionNo(projectId));
        version.setPlanContent(req.getPlanContent());
        version.setRiskLevel(riskLevel);
        version.setStatus(PLAN_SIGNING);
        version.setSignDeadline(req.getSignDeadline());
        version.setSubmittedBy(req.getSubmittedBy());
        version.setSubmittedAt(now);
        version.setBaseVersionId(req.getBaseVersionId());
        versionMapper.insert(version);

        int seq = 1;
        for (PlanSignerDto signer : req.getSigners()) {
            PlanSignStep step = new PlanSignStep();
            step.setVersionId(version.getId());
            step.setSeqNo(seq++);
            step.setSignerRole(signer.getRole());
            step.setSignerName(signer.getSignerName());
            step.setStatus(STEP_PENDING);
            stepMapper.insert(step);
        }

        ledgerService.append(relic.getId(), projectId, null,
                ACTION_PLAN_SUBMIT, planBizNo(project, version),
                "方案第 " + version.getVersionNo() + " 版提交会签（风险等级 " + riskLevel
                        + "，会签 " + req.getSigners().size() + " 环节），提交人："
                        + req.getSubmittedBy(), now);
        return version;
    }

    /** 按文物风险校验会签顺序与角色构成 */
    private void validateSignerChain(String riskLevel, List<PlanSignerDto> signers) {
        Set<String> names = new HashSet<>();
        Set<String> roles = new HashSet<>();
        int lastRank = 0;
        for (PlanSignerDto signer : signers) {
            int rank = ROLE_ORDER.indexOf(signer.getRole());
            if (rank < 0) {
                throw new IllegalArgumentException(
                        "会签角色只能为 CURATOR/INTERNAL_EXPERT/EXTERNAL_EXPERT: " + signer.getRole());
            }
            if (rank < lastRank) {
                throw new IllegalArgumentException(
                        "签名顺序不符合风险口径：须按 保管人员→馆内专家→外部专家 排序");
            }
            lastRank = rank;
            if (!names.add(signer.getSignerName())) {
                throw new IllegalArgumentException(
                        "同一会签人不能在同一版本中重复签署: " + signer.getSignerName());
            }
            roles.add(signer.getRole());
        }
        if (!roles.contains(ROLE_CURATOR) || !roles.contains(ROLE_INTERNAL_EXPERT)) {
            throw new IllegalArgumentException("会签须至少包含保管人员与馆内专家");
        }
        if (GRADE_ONE.equals(riskLevel) && !roles.contains(ROLE_EXTERNAL_EXPERT)) {
            throw new IllegalArgumentException("一级文物（高风险）会签须包含保管人员、馆内专家与外部专家");
        }
    }

    // --------------------------------------------------------------------
    // 会签签署 / 退回
    // --------------------------------------------------------------------

    /** 签署当前环节：记录意见、签名时间与依据附件；全部签署完成版本自动通过 */
    @Transactional
    public PlanSignStep sign(Long versionId, PlanSignRequest req) {
        RepairPlanVersion version = requireSigningVersion(versionId);
        LocalDateTime actedAt = req.getSignedAt() != null ? req.getSignedAt() : LocalDateTime.now();
        requireWithinDeadline(version, actedAt, "签署");

        PlanSignStep current = requireCurrentStep(versionId);
        requireDesignatedSigner(version, current, req.getSignerName());

        current.setStatus(STEP_SIGNED);
        current.setOpinion(req.getOpinion());
        current.setSignedAt(actedAt);
        stepMapper.updateById(current);

        if (req.getAttachments() != null) {
            int sort = 1;
            for (PlanAttachmentDto att : req.getAttachments()) {
                PlanSignAttachment attachment = new PlanSignAttachment();
                attachment.setStepId(current.getId());
                attachment.setVersionId(versionId);
                attachment.setFileName(att.getFileName());
                attachment.setFileUrl(att.getFileUrl());
                attachment.setSortNo(sort++);
                attachmentMapper.insert(attachment);
            }
        }

        RepairProject project = requireProject(version.getProjectId());
        ledgerService.append(version.getRelicId(), version.getProjectId(), null,
                ACTION_PLAN_SIGN, planBizNo(project, version),
                "方案第 " + version.getVersionNo() + " 版第 " + current.getSeqNo()
                        + " 环节（" + roleLabel(current.getSignerRole()) + "）由 "
                        + current.getSignerName() + " 签署", actedAt);

        // 全部环节签署完成 -> 版本通过，同项目旧通过版本归档只读（项目内至多一个现行有效）
        Long pending = stepMapper.selectCount(new LambdaQueryWrapper<PlanSignStep>()
                .eq(PlanSignStep::getVersionId, versionId)
                .eq(PlanSignStep::getStatus, STEP_PENDING));
        if (pending != null && pending == 0) {
            version.setStatus(PLAN_APPROVED);
            version.setCompletedAt(actedAt);
            versionMapper.updateById(version);
            supersedeApprovedVersions(version, actedAt);
            ledgerService.append(version.getRelicId(), version.getProjectId(), null,
                    ACTION_PLAN_APPROVE, planBizNo(project, version),
                    "方案第 " + version.getVersionNo() + " 版会签全部完成，通过生效", actedAt);
        }
        return current;
    }

    /** 退回：任一环节专家退回即终止本版本会签，版本只读，只能修订新版本 */
    @Transactional
    public PlanSignStep returnVersion(Long versionId, PlanReturnRequest req) {
        RepairPlanVersion version = requireSigningVersion(versionId);
        LocalDateTime actedAt = req.getReturnedAt() != null ? req.getReturnedAt() : LocalDateTime.now();
        requireWithinDeadline(version, actedAt, "退回");

        PlanSignStep current = requireCurrentStep(versionId);
        requireDesignatedSigner(version, current, req.getSignerName());

        current.setStatus(STEP_RETURNED);
        current.setOpinion(req.getOpinion());
        current.setReturnedAt(actedAt);
        stepMapper.updateById(current);

        version.setStatus(PLAN_RETURNED);
        versionMapper.updateById(version);

        RepairProject project = requireProject(version.getProjectId());
        ledgerService.append(version.getRelicId(), version.getProjectId(), null,
                ACTION_PLAN_RETURN, planBizNo(project, version),
                "方案第 " + version.getVersionNo() + " 版第 " + current.getSeqNo()
                        + " 环节（" + roleLabel(current.getSignerRole()) + "）由 "
                        + current.getSignerName() + " 退回：" + req.getOpinion()
                        + "；本版本只读，须修订新版本", actedAt);
        return current;
    }

    /** 会签中版本才允许签署/退回；退回、通过、被取代的版本一律只读 */
    private RepairPlanVersion requireSigningVersion(Long versionId) {
        RepairPlanVersion version = requireVersion(versionId);
        if (!PLAN_SIGNING.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "方案第 " + version.getVersionNo() + " 版已" + statusLabel(version.getStatus())
                            + "，只读不可再会签");
        }
        return version;
    }

    /**
     * 截止同一口径：动作时间不晚于 sign_deadline（含临界时刻）才归属本版本；
     * 超期后签署与退回同样拒绝，任何角色一视同仁。
     */
    private void requireWithinDeadline(RepairPlanVersion version, LocalDateTime actedAt, String action) {
        if (version.getSignDeadline() != null && actedAt.isAfter(version.getSignDeadline())) {
            throw new IllegalArgumentException(
                    "会签已超期（截止 " + version.getSignDeadline() + "），本版本不再接受" + action
                            + "，请修订新版本");
        }
    }

    private PlanSignStep requireCurrentStep(Long versionId) {
        return stepMapper.selectList(new LambdaQueryWrapper<PlanSignStep>()
                        .eq(PlanSignStep::getVersionId, versionId)
                        .eq(PlanSignStep::getStatus, STEP_PENDING)
                        .orderByAsc(PlanSignStep::getSeqNo)
                        .last("LIMIT 1"))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("方案版本没有待签署的会签环节: " + versionId));
    }

    /**
     * 缺席专家不能被代签：签署人必须是当前环节指定人；
     * 是后续环节专家的提示按顺序会签，完全不相关的（如管理员）按代签拒绝。
     */
    private void requireDesignatedSigner(RepairPlanVersion version, PlanSignStep current, String signerName) {
        if (current.getSignerName().equals(signerName)) {
            return;
        }
        List<PlanSignStep> steps = listSteps(version.getId());
        boolean isFutureSigner = steps.stream()
                .anyMatch(s -> s.getSignerName().equals(signerName));
        if (isFutureSigner) {
            throw new IllegalArgumentException(
                    "请按会签顺序签署：当前为第 " + current.getSeqNo() + " 环节（"
                            + roleLabel(current.getSignerRole()) + "「" + current.getSignerName()
                            + "」），" + signerName + " 的环节尚未到达");
        }
        throw new IllegalArgumentException(
                "「" + signerName + "」不是当前环节的指定会签人（第 " + current.getSeqNo()
                        + " 环节指定「" + current.getSignerName() + "」），缺席专家不能由管理员或他人代签");
    }

    /** 版本通过生效时，同项目其他已通过版本归档为「已被取代」（只读） */
    private void supersedeApprovedVersions(RepairPlanVersion approved, LocalDateTime now) {
        List<RepairPlanVersion> others = versionMapper.selectList(
                new LambdaQueryWrapper<RepairPlanVersion>()
                        .eq(RepairPlanVersion::getProjectId, approved.getProjectId())
                        .eq(RepairPlanVersion::getStatus, PLAN_APPROVED)
                        .ne(RepairPlanVersion::getId, approved.getId()));
        RepairProject project = requireProject(approved.getProjectId());
        for (RepairPlanVersion old : others) {
            old.setStatus(PLAN_SUPERSEDED);
            versionMapper.updateById(old);
            ledgerService.append(old.getRelicId(), old.getProjectId(), null,
                    ACTION_PLAN_SUPERSEDE, planBizNo(project, old),
                    "方案第 " + old.getVersionNo() + " 版被第 " + approved.getVersionNo()
                            + " 版取代，归档只读", now);
        }
    }

    // --------------------------------------------------------------------
    // 施工任务
    // --------------------------------------------------------------------

    /** 生成施工任务：仅当方案版本会签通过（APPROVED）才允许 */
    @Transactional
    public ConstructionTask createTask(Long projectId, ConstructionTaskCreateRequest req) {
        RepairProject project = requireProject(projectId);
        if (PROJECT_CANCELLED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目已作废，不能生成施工任务: " + project.getProjectNo());
        }

        RepairPlanVersion version;
        if (req.getPlanVersionId() != null) {
            version = requireVersion(req.getPlanVersionId());
            if (!projectId.equals(version.getProjectId())) {
                throw new IllegalArgumentException("方案版本不属于本项目: " + req.getPlanVersionId());
            }
        } else {
            version = findVersionByStatus(projectId, PLAN_APPROVED);
            if (version == null) {
                throw new IllegalArgumentException("项目尚无会签通过的方案版本，不能生成施工任务");
            }
        }
        if (!PLAN_APPROVED.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "方案第 " + version.getVersionNo() + " 版会签未完成，不能生成施工任务");
        }

        if (taskMapper.selectCount(new LambdaQueryWrapper<ConstructionTask>()
                .eq(ConstructionTask::getTaskNo, req.getTaskNo())) > 0) {
            throw new IllegalArgumentException("任务编号已存在: " + req.getTaskNo());
        }

        LocalDateTime now = LocalDateTime.now();
        ConstructionTask task = new ConstructionTask();
        task.setTaskNo(req.getTaskNo());
        task.setProjectId(projectId);
        task.setRelicId(project.getRelicId());
        task.setPlanVersionId(version.getId());
        task.setTaskName(req.getTaskName());
        task.setNote(req.getNote());
        task.setStatus(TASK_CREATED);
        taskMapper.insert(task);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_TASK_CREATE, task.getTaskNo(),
                "依据方案第 " + version.getVersionNo() + " 版生成施工任务【"
                        + req.getTaskName() + "】", now);
        return task;
    }

    // --------------------------------------------------------------------
    // 查询
    // --------------------------------------------------------------------

    public RepairPlanVersion requireVersion(Long versionId) {
        RepairPlanVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("方案版本不存在: id=" + versionId);
        }
        return version;
    }

    public List<RepairPlanVersion> listVersions(Long projectId) {
        return versionMapper.selectList(new LambdaQueryWrapper<RepairPlanVersion>()
                .eq(RepairPlanVersion::getProjectId, projectId)
                .orderByAsc(RepairPlanVersion::getVersionNo));
    }

    public List<PlanSignStep> listSteps(Long versionId) {
        return stepMapper.selectList(new LambdaQueryWrapper<PlanSignStep>()
                .eq(PlanSignStep::getVersionId, versionId)
                .orderByAsc(PlanSignStep::getSeqNo));
    }

    /** 版本详情：版本 + 会签环节（含各环节依据附件） */
    public Map<String, Object> versionDetail(Long versionId) {
        RepairPlanVersion version = requireVersion(versionId);
        List<PlanSignStep> steps = listSteps(versionId);
        List<PlanSignAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<PlanSignAttachment>()
                        .eq(PlanSignAttachment::getVersionId, versionId)
                        .orderByAsc(PlanSignAttachment::getStepId)
                        .orderByAsc(PlanSignAttachment::getSortNo));
        Map<Long, List<PlanSignAttachment>> byStep = new HashMap<>();
        for (PlanSignAttachment att : attachments) {
            byStep.computeIfAbsent(att.getStepId(), k -> new ArrayList<>()).add(att);
        }
        List<Map<String, Object>> stepViews = new ArrayList<>();
        for (PlanSignStep step : steps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("step", step);
            view.put("attachments", byStep.getOrDefault(step.getId(), new ArrayList<>()));
            stepViews.add(view);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("steps", stepViews);
        return detail;
    }

    public List<ConstructionTask> listTasks(Long projectId) {
        return taskMapper.selectList(new LambdaQueryWrapper<ConstructionTask>()
                .eq(ConstructionTask::getProjectId, projectId)
                .orderByAsc(ConstructionTask::getId));
    }

    // --------------------------------------------------------------------

    private RepairProject requireProject(Long projectId) {
        RepairProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("修复项目不存在: id=" + projectId);
        }
        return project;
    }

    private RepairPlanVersion findVersionByStatus(Long projectId, String status) {
        return versionMapper.selectOne(new LambdaQueryWrapper<RepairPlanVersion>()
                .eq(RepairPlanVersion::getProjectId, projectId)
                .eq(RepairPlanVersion::getStatus, status)
                .orderByDesc(RepairPlanVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 超期口径：当前时间晚于截止时刻（临界时刻本身仍属有效） */
    private boolean isOverdue(RepairPlanVersion version, LocalDateTime now) {
        return version.getSignDeadline() != null && now.isAfter(version.getSignDeadline());
    }

    private int nextVersionNo(Long projectId) {
        Integer max = versionMapper.selectList(new LambdaQueryWrapper<RepairPlanVersion>()
                        .eq(RepairPlanVersion::getProjectId, projectId)
                        .orderByDesc(RepairPlanVersion::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(RepairPlanVersion::getVersionNo).orElse(0);
        return max + 1;
    }

    private String planBizNo(RepairProject project, RepairPlanVersion version) {
        return project.getProjectNo() + "-V" + version.getVersionNo();
    }

    private String roleLabel(String role) {
        switch (role) {
            case ROLE_CURATOR: return "保管人员";
            case ROLE_INTERNAL_EXPERT: return "馆内专家";
            case ROLE_EXTERNAL_EXPERT: return "外部专家";
            default: return role;
        }
    }

    private String statusLabel(String status) {
        switch (status) {
            case PLAN_APPROVED: return "通过";
            case PLAN_RETURNED: return "退回";
            case PLAN_SUPERSEDED: return "被新版本取代";
            default: return status;
        }
    }
}
