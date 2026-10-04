package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会签签署：记录意见、签名时间与依据附件。
 * 签署人必须是当前环节指定人，缺席专家不能由管理员或他人代签。
 */
@Data
public class PlanSignRequest {

    @NotBlank(message = "签署人不能为空")
    private String signerName;

    /** 会签意见 */
    private String opinion;

    /** 签名时间，可空（默认当前时间）；不晚于会签截止时刻（含临界）才归属本版本 */
    private LocalDateTime signedAt;

    /** 依据附件，可多个 */
    @Valid
    private List<PlanAttachmentDto> attachments;
}
