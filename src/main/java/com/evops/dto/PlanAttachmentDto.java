package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 会签依据附件：签署时提交的依据材料（检测报告、分析数据等）。
 */
@Data
public class PlanAttachmentDto {

    @NotBlank(message = "附件名不能为空")
    private String fileName;

    @NotBlank(message = "附件地址不能为空")
    private String fileUrl;
}
