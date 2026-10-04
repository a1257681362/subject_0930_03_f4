package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 会签退回：必须给出退回意见；退回后本版本只读，只能修订新版本。
 */
@Data
public class PlanReturnRequest {

    @NotBlank(message = "退回人不能为空")
    private String signerName;

    @NotBlank(message = "退回意见不能为空")
    private String opinion;

    /** 退回时间，可空（默认当前时间）；与签署同一截止口径 */
    private LocalDateTime returnedAt;
}
