package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 会签人配置：角色 + 指定签署人。
 * 角色：CURATOR 保管人员 / INTERNAL_EXPERT 馆内专家 / EXTERNAL_EXPERT 外部专家。
 */
@Data
public class PlanSignerDto {

    @NotBlank(message = "会签角色不能为空")
    private String role;

    @NotBlank(message = "会签人姓名不能为空")
    private String signerName;
}
