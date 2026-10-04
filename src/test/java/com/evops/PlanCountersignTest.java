package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物修复方案会签验收：
 * 按文物风险配置签名顺序、顺序会签、缺席不能代签、退回只读只能修订新版本、
 * 全部会签完成才允许生成施工任务；会签截止临界时刻按同一口径归属。
 * 使用独立内存库，编号互不干扰。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-plan-sign;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanCountersignTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 风险等级决定签名顺序与角色构成
    // ------------------------------------------------------------------

    @Test
    void risk_level_determines_signer_chain() throws Exception {
        long relicOne = createRelic("R-CS-101", "ONE");
        long projectOne = createProject(relicOne, "P-CS-101");

        // 一级文物（高风险）缺外部专家：拒绝
        expectFail(postJson("/api/repair-projects/" + projectOne + "/plan-versions", "{"
                + "\"planContent\":\"方案\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"}]}"), "一级文物");

        // 顺序不符（馆内专家排在保管人员前）：拒绝
        expectFail(postJson("/api/repair-projects/" + projectOne + "/plan-versions", "{"
                + "\"planContent\":\"方案\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"), "签名顺序");

        // 角色非法：拒绝
        expectFail(postJson("/api/repair-projects/" + projectOne + "/plan-versions", "{"
                + "\"planContent\":\"方案\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"ADMIN\",\"signerName\":\"管理员\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"), "会签角色");

        // 同一人重复签署：拒绝
        expectFail(postJson("/api/repair-projects/" + projectOne + "/plan-versions", "{"
                + "\"planContent\":\"方案\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"), "重复签署");

        // 一级文物完整链：保管人员 -> 馆内专家 -> 外部专家，通过
        JsonNode v1 = dataOf(postJson("/api/repair-projects/" + projectOne + "/plan-versions", "{"
                + "\"planContent\":\"清洗加固\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"));
        assertEquals(1, v1.get("versionNo").asInt());
        assertEquals("SIGNING", v1.get("status").asText());
        assertEquals("ONE", v1.get("riskLevel").asText());

        // 三级文物（低风险）：保管人员 + 馆内专家即可，外部专家可选
        long relicThree = createRelic("R-CS-102", "THREE");
        long projectThree = createProject(relicThree, "P-CS-102");
        JsonNode v = dataOf(postJson("/api/repair-projects/" + projectThree + "/plan-versions", "{"
                + "\"planContent\":\"表面除尘\",\"submittedBy\":\"修复师乙\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"}]}"));
        assertEquals("SIGNING", v.get("status").asText());

        // 缺保管人员：拒绝
        expectFail(postJson("/api/repair-projects/" + projectThree + "/plan-versions", "{"
                + "\"planContent\":\"方案\",\"submittedBy\":\"修复师乙\","
                + "\"signers\":[{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"}]}"),
                "保管人员与馆内专家");
    }

    // ------------------------------------------------------------------
    // 顺序会签、代签禁止、退回只读、修订新版本、施工任务闸门
    // ------------------------------------------------------------------

    @Test
    void countersign_lifecycle_rules() throws Exception {
        long relicId = createRelic("R-CS-201", "ONE");
        long projectId = createProject(relicId, "P-CS-201");

        JsonNode v1 = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"V1 方案\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"));
        long v1Id = v1.get("id").asLong();

        // 会签中不允许再提交新版本
        expectFail(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"抢跑版本\",\"submittedBy\":\"修复师甲\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"), "会签中的方案版本");

        // 会签未完成：不能生成施工任务（不指定版本 / 指定会签中版本同口径拒绝）
        expectFail(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-201\",\"taskName\":\"清洗施工\"}"), "不能生成施工任务");
        expectFail(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-201\",\"taskName\":\"清洗施工\",\"planVersionId\":" + v1Id + "}"),
                "会签未完成");

        // 缺席专家不能被管理员代签
        expectFail(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"管理员\",\"opinion\":\"代专家签署\"}"), "代签");

        // 不能跳环节：外部专家丙的环节尚未到达
        expectFail(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"外部专家丙\"}"), "按会签顺序");

        // 顺序会签：保管员甲（含意见、签名时间、依据附件）-> 馆内专家乙
        JsonNode sign1 = dataOf(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"保管员甲\",\"opinion\":\"现状记录完整，同意\","
                + "\"signedAt\":\"2026-09-01T10:00:00\","
                + "\"attachments\":[{\"fileName\":\"检测报告.pdf\",\"fileUrl\":\"files/jc.pdf\"},"
                + "{\"fileName\":\"成分分析.xlsx\",\"fileUrl\":\"files/fx.xlsx\"}]}"));
        assertEquals("SIGNED", sign1.get("status").asText());
        assertEquals("2026-09-01T10:00:00", sign1.get("signedAt").asText());
        expectOk(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"馆内专家乙\",\"opinion\":\"技术路线可行\"}"));

        // 外部专家丙退回：版本只读
        expectOk(postJson("/api/plan-versions/" + v1Id + "/return", "{"
                + "\"signerName\":\"外部专家丙\",\"opinion\":\"缓蚀剂选型依据不足，退回修订\"}"));
        expectFail(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"外部专家丙\"}"), "只读");
        expectFail(postJson("/api/plan-versions/" + v1Id + "/return", "{"
                + "\"signerName\":\"外部专家丙\",\"opinion\":\"再次退回\"}"), "只读");
        // 退回版本不能生成施工任务
        expectFail(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-201\",\"taskName\":\"清洗施工\",\"planVersionId\":" + v1Id + "}"),
                "会签未完成");

        // 旧版本只读保留：意见、签名时间、依据附件完整可查
        JsonNode v1Detail = dataOf(getJson("/api/plan-versions/" + v1Id));
        assertEquals("RETURNED", v1Detail.get("version").get("status").asText());
        JsonNode steps = v1Detail.get("steps");
        assertEquals(3, steps.size());
        assertEquals("SIGNED", steps.get(0).get("step").get("status").asText());
        assertEquals("现状记录完整，同意", steps.get(0).get("step").get("opinion").asText());
        assertEquals("2026-09-01T10:00:00", steps.get(0).get("step").get("signedAt").asText());
        assertEquals(2, steps.get(0).get("attachments").size());
        assertEquals("检测报告.pdf", steps.get(0).get("attachments").get(0).get("fileName").asText());
        assertEquals("SIGNED", steps.get(1).get("step").get("status").asText());
        assertEquals("RETURNED", steps.get(2).get("step").get("status").asText());
        assertEquals("缓蚀剂选型依据不足，退回修订", steps.get(2).get("step").get("opinion").asText());

        // 修订新版本 V2（基于退回的 V1），版本号递增
        JsonNode v2 = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"V2 方案：补充缓蚀剂选型依据\",\"submittedBy\":\"修复师甲\","
                + "\"baseVersionId\":" + v1Id + ","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"));
        long v2Id = v2.get("id").asLong();
        assertEquals(2, v2.get("versionNo").asInt());
        assertEquals(v1Id, v2.get("baseVersionId").asLong());

        // 全部会签完成 -> 版本通过
        expectOk(postJson("/api/plan-versions/" + v2Id + "/sign", "{\"signerName\":\"保管员甲\"}"));
        expectOk(postJson("/api/plan-versions/" + v2Id + "/sign", "{\"signerName\":\"馆内专家乙\"}"));
        expectOk(postJson("/api/plan-versions/" + v2Id + "/sign", "{\"signerName\":\"外部专家丙\"}"));
        JsonNode v2Detail = dataOf(getJson("/api/plan-versions/" + v2Id));
        assertEquals("APPROVED", v2Detail.get("version").get("status").asText());
        assertTrue(v2Detail.get("version").get("completedAt") != null
                && !v2Detail.get("version").get("completedAt").isNull());

        // 全部会签完成才允许生成施工任务；默认锚定现行通过版本
        JsonNode task1 = dataOf(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-201\",\"taskName\":\"清洗施工\"}"));
        assertEquals(v2Id, task1.get("planVersionId").asLong());
        assertEquals("CREATED", task1.get("status").asText());
        // 任务编号唯一
        expectFail(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-201\",\"taskName\":\"重复编号\"}"), "任务编号已存在");
        // 显式指定通过版本也可生成
        expectOk(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-202\",\"taskName\":\"加固施工\",\"planVersionId\":" + v2Id + "}"));

        // 台账留痕完整且有序（按业务发生时间排序：保管员甲签署时间早于版本提交时间，故排在最前）
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        List<String> actions = new ArrayList<>();
        dossier.get("ledger").forEach(e -> actions.add(e.get("action").asText()));
        assertEquals(Arrays.asList(
                "PLAN_SIGN", "PLAN_SUBMIT", "PLAN_SIGN", "PLAN_RETURN",
                "PLAN_SUBMIT", "PLAN_SIGN", "PLAN_SIGN", "PLAN_SIGN", "PLAN_APPROVE",
                "TASK_CREATE", "TASK_CREATE"), actions);
    }

    // ------------------------------------------------------------------
    // 临界时间：方案版本调到会签截止时刻，专家会签按同一口径归属
    // ------------------------------------------------------------------

    @Test
    void critical_time_attribution_same_rule() throws Exception {
        long relicId = createRelic("R-CS-301", "TWO");
        long projectId = createProject(relicId, "P-CS-301");

        // 把方案版本调到临界时间：会签截止时刻 T（过去 1 小时，便于后续超期）
        LocalDateTime t = LocalDateTime.now().minusHours(1).withNano(0);
        String deadline = t.format(FMT);
        String afterDeadline = t.plusSeconds(1).format(FMT);

        JsonNode v1 = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"V1 方案\",\"submittedBy\":\"修复师甲\","
                + "\"signDeadline\":\"" + deadline + "\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"));
        long v1Id = v1.get("id").asLong();

        // 临界时刻（含端点）：保管人员、馆内专家签署均归属本版本 —— 口径与角色无关
        JsonNode s1 = dataOf(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"保管员甲\",\"signedAt\":\"" + deadline + "\"}"));
        assertEquals(deadline, s1.get("signedAt").asText());
        JsonNode s2 = dataOf(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"馆内专家乙\",\"signedAt\":\"" + deadline + "\"}"));
        assertEquals(deadline, s2.get("signedAt").asText());

        // 超过截止 1 秒：外部专家签署与退回同一口径拒绝
        expectFail(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"外部专家丙\",\"signedAt\":\"" + afterDeadline + "\"}"), "会签已超期");
        expectFail(postJson("/api/plan-versions/" + v1Id + "/return", "{"
                + "\"signerName\":\"外部专家丙\",\"opinion\":\"超期退回\","
                + "\"returnedAt\":\"" + afterDeadline + "\"}"), "会签已超期");

        // 超期版本随新版本提交归档只读（SUPERSEDED），临界前签署仍归属 V1
        JsonNode v2 = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"V2 方案\",\"submittedBy\":\"修复师甲\",\"baseVersionId\":" + v1Id + ","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"));
        long v2Id = v2.get("id").asLong();
        assertEquals(2, v2.get("versionNo").asInt());

        JsonNode v1Detail = dataOf(getJson("/api/plan-versions/" + v1Id));
        assertEquals("SUPERSEDED", v1Detail.get("version").get("status").asText());
        JsonNode v1Steps = v1Detail.get("steps");
        assertEquals("SIGNED", v1Steps.get(0).get("step").get("status").asText());
        assertEquals(deadline, v1Steps.get(0).get("step").get("signedAt").asText());
        assertEquals("SIGNED", v1Steps.get(1).get("step").get("status").asText());
        assertEquals(deadline, v1Steps.get(1).get("step").get("signedAt").asText());
        assertEquals("PENDING", v1Steps.get(2).get("step").get("status").asText());

        // 被取代的旧版本只读：任何角色都不能再签署
        expectFail(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                + "\"signerName\":\"外部专家丙\",\"signedAt\":\"" + deadline + "\"}"), "只读");

        // 新版本会签通过后生成施工任务；台账含 PLAN_SUPERSEDE 留痕
        expectOk(postJson("/api/plan-versions/" + v2Id + "/sign", "{\"signerName\":\"保管员甲\"}"));
        expectOk(postJson("/api/plan-versions/" + v2Id + "/sign", "{\"signerName\":\"馆内专家乙\"}"));
        expectOk(postJson("/api/plan-versions/" + v2Id + "/sign", "{\"signerName\":\"外部专家丙\"}"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/construction-tasks", "{"
                + "\"taskNo\":\"SG-CS-301\",\"taskName\":\"加固施工\"}"));

        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        List<String> actions = new ArrayList<>();
        dossier.get("ledger").forEach(e -> actions.add(e.get("action").asText()));
        assertTrue(actions.contains("PLAN_SUPERSEDE"), "台账应记录版本被取代: " + actions);
        // 临界时刻的两次签署业务时间（T）早于版本提交时间，按发生时间排在最前
        assertEquals(Arrays.asList(
                "PLAN_SIGN", "PLAN_SIGN", "PLAN_SUBMIT",
                "PLAN_SUPERSEDE", "PLAN_SUBMIT",
                "PLAN_SIGN", "PLAN_SIGN", "PLAN_SIGN", "PLAN_APPROVE",
                "TASK_CREATE"), actions);
    }

    // ------------------------------------------------------------------
    // 临界时刻（含端点）对三类角色同一口径：全部按截止时刻签署即通过
    // ------------------------------------------------------------------

    @Test
    void critical_instant_signing_valid_for_all_roles() throws Exception {
        long relicId = createRelic("R-CS-302", "ONE");
        long projectId = createProject(relicId, "P-CS-302");

        LocalDateTime t = LocalDateTime.now().plusHours(1).withNano(0);
        String deadline = t.format(FMT);

        JsonNode v1 = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"临界方案\",\"submittedBy\":\"修复师甲\","
                + "\"signDeadline\":\"" + deadline + "\","
                + "\"signers\":[{\"role\":\"CURATOR\",\"signerName\":\"保管员甲\"},"
                + "{\"role\":\"INTERNAL_EXPERT\",\"signerName\":\"馆内专家乙\"},"
                + "{\"role\":\"EXTERNAL_EXPERT\",\"signerName\":\"外部专家丙\"}]}"));
        long v1Id = v1.get("id").asLong();

        // 保管人员 / 馆内专家 / 外部专家都恰在临界时刻签署：同一口径归属本版本
        for (String signer : new String[]{"保管员甲", "馆内专家乙", "外部专家丙"}) {
            JsonNode s = dataOf(postJson("/api/plan-versions/" + v1Id + "/sign", "{"
                    + "\"signerName\":\"" + signer + "\",\"signedAt\":\"" + deadline + "\"}"));
            assertEquals(deadline, s.get("signedAt").asText());
        }
        JsonNode detail = dataOf(getJson("/api/plan-versions/" + v1Id));
        assertEquals("APPROVED", detail.get("version").get("status").asText());
        assertEquals(deadline, detail.get("version").get("completedAt").asText());
    }

    // ------------------------------------------------------------------

    private long createRelic(String relicNo, String grade) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + relicNo + "\",\"name\":\"会签测试文物\","
                + "\"material\":\"青铜\",\"era\":\"商代\",\"source\":\"发掘移交\","
                + "\"preservationGrade\":\"" + grade + "\"}")).get("id").asLong();
    }

    private long createProject(long relicId, String projectNo) throws Exception {
        return dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + projectNo + "\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"修复方案\"}")).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
        return root.get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        dataOf(result);
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
