package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 生成施工任务：仅当方案版本会签通过（APPROVED）才允许。
 */
@Data
public class ConstructionTaskCreateRequest {

    @NotBlank(message = "任务编号不能为空")
    private String taskNo;

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    /** 依据的方案版本，可空（默认项目当前会签通过的版本） */
    private Long planVersionId;

    private String note;
}
