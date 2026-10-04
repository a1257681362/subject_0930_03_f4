package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 施工任务：仅当方案版本会签通过（APPROVED）才允许生成。
 * 任务锚定具体方案版本，后续方案修订不改写已生成任务的历史事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_construction_task")
public class ConstructionTask extends BaseEntity {

    /** 任务编号，全库唯一 */
    private String taskNo;

    private Long projectId;

    private Long relicId;

    /** 依据的方案版本（必须会签通过） */
    private Long planVersionId;

    private String taskName;

    private String note;

    /** CREATED 已生成 */
    private String status;
}
