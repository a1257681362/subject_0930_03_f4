package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 会签环节：方案版本内一条有序签名链。
 * 只能按 seq_no 顺序签署；签署人必须是当前环节指定人，
 * 缺席专家不能由管理员或他人代签。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_plan_sign_step")
public class PlanSignStep extends BaseEntity {

    private Long versionId;

    /** 会签顺序号，版本内从 1 递增 */
    private Integer seqNo;

    /** CURATOR 保管人员 / INTERNAL_EXPERT 馆内专家 / EXTERNAL_EXPERT 外部专家 */
    private String signerRole;

    /** 指定签署人（不可代签） */
    private String signerName;

    /** PENDING / SIGNED / RETURNED */
    private String status;

    /** 会签意见 */
    private String opinion;

    /** 签名时间 */
    private LocalDateTime signedAt;

    /** 退回时间 */
    private LocalDateTime returnedAt;
}
