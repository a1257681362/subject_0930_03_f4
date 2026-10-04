package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复方案版本：方案按版本管理，只追加、不覆盖。
 * 状态链路：SIGNING -> APPROVED（全部会签完成，项目内至多一个现行有效）
 *          SIGNING -> RETURNED（任一专家退回，版本只读，只能修订新版本）
 *          APPROVED/SIGNING(超期) -> SUPERSEDED（被新版本取代，只读）。
 * 会签截止口径：签署/退回时间不晚于 sign_deadline（含临界时刻）才归属本版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_plan_version")
public class RepairPlanVersion extends BaseEntity {

    private Long projectId;

    private Long relicId;

    /** 版本号，项目内从 1 递增 */
    private Integer versionNo;

    /** 方案内容 */
    private String planContent;

    /** 风险等级快照（文物保存等级 ONE/TWO/THREE），决定签名顺序口径 */
    private String riskLevel;

    /** SIGNING / APPROVED / RETURNED / SUPERSEDED */
    private String status;

    /** 会签截止时间，可空；签署/退回时间 ≤ 该时刻（含临界）才归属本版本 */
    private LocalDateTime signDeadline;

    /** 方案提交人 */
    private String submittedBy;

    private LocalDateTime submittedAt;

    /** 全部会签完成时间 */
    private LocalDateTime completedAt;

    /** 修订来源版本（退回/取代后修订时为上一版本 id） */
    private Long baseVersionId;
}
