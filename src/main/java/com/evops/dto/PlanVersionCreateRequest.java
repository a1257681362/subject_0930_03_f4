package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交修复方案新版本：按文物风险配置会签顺序，任一专家退回后只能修订新版本。
 */
@Data
public class PlanVersionCreateRequest {

    @NotBlank(message = "方案内容不能为空")
    private String planContent;

    /** 会签截止时间，可空；签署/退回时间不晚于该时刻（含临界）才归属本版本 */
    private LocalDateTime signDeadline;

    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    /** 修订来源版本（退回/取代后修订时填上一版本 id），首版可空 */
    private Long baseVersionId;

    /** 会签人链：按期望签署顺序给出，系统按文物风险等级校验顺序口径 */
    @NotEmpty(message = "会签人不能为空")
    @Valid
    private List<PlanSignerDto> signers;
}
