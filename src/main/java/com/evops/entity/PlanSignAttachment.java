package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会签依据附件：签署时提交的依据材料（检测报告、分析数据等）。
 * 只追加、不删除，随签署记录永久留痕（与入库流水同风格，无更新字段）。
 */
@Data
@TableName("t_plan_sign_attachment")
public class PlanSignAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stepId;

    private Long versionId;

    private String fileName;

    private String fileUrl;

    private Integer sortNo;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
