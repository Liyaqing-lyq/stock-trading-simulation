package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立复核用例：第三人零对象零数量、本人未读标题与正文都为空、精确 401/403，以及上帝视图双方真实阅读。
 *
 * <p>复用候选的输入驱动夹具（{@link DialogueWebHttpTest.Harness}），因此同样只验证 Web 接线与权限，
 * 不主张官方模型自主行为；本类不引入任何内部命名常量。</p>
 */
class IndepDialogueProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void thirdPartyAndUnreadAreTrimmed() throws Exception {
        try (DialogueWebHttpTest.Harness harness = new DialogueWebHttpTest.Harness()) {
            DialogueWebHttpTest.Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_000L);
            String[] script = {"rita", "rita", "investor", "rita", "investor", "investor",
                "rita", "rita", "investor", "investor"};
            for (int index = 0; index < script.length; index += 1) {
                admin.round(script[index]);
                if (index == 0) {
                    admin.advance(admin.timeMillis() + 8_000L);
                } else if (index == 2) {
                    admin.advance(harness.provider.sessionStart() + 2_000L);
                } else {
                    admin.advance(admin.timeMillis() + 4_000L);
                }
            }

            // 1) 精确状态码：匿名 401（两个端点）、管理员访问个人端点 403、管理员访问审计 200
            DialogueWebHttpTest.Client anonymous =
                    new DialogueWebHttpTest.Client(harness.baseUrl());
            assertEquals(401, anonymous.getStatus("/api/me/dialogue"), "匿名读本人对话必须 401");
            assertEquals(401, anonymous.getStatus("/api/admin/dialogue"), "匿名读审计必须 401");
            assertEquals(403, admin.getStatus("/api/me/dialogue"), "管理员读本人对话必须 403");
            assertEquals(200, admin.getStatus("/api/admin/dialogue"), "管理员读审计");

            // 2) 上帝视图：三轮都真实存在，且每轮都被对方真实读完
            JsonNode audit = admin.get("/api/admin/dialogue");
            assertEquals(3, audit.path("turns").path("total").asInt(), "三次真实发言");
            assertEquals(1, audit.path("sessions").path("total").asInt(), "一个真实会话");
            assertEquals(1, audit.path("closes").path("total").asInt(), "一次真实关闭");
            for (JsonNode row : audit.path("turns").path("rows")) {
                assertFalse(row.path("speakerRead").asBoolean(),
                        "发言不产生本人已读：" + row.path("docId").asText());
                assertTrue(row.path("peerRead").asBoolean(),
                        "对方必须真实读完：" + row.path("docId").asText());
                assertFalse(row.path("body").asText().isEmpty(), "上帝视图含真实正文");
            }
            assertEquals("rita", audit.path("turns").path("rows").get(0).path("speakerPartyId").asText());
            assertEquals("investor", audit.path("turns").path("rows").get(1).path("speakerPartyId").asText());
            assertEquals("rita", audit.path("turns").path("rows").get(2).path("speakerPartyId").asText());

            // 3) 第三人：是当前世界可见人物，但投影里零对象、零数量、无任何标识
            harness.host.roleView("bob");
            String bobText = MAPPER.writeValueAsString(harness.host.dialogueView("bob"));
            JsonNode bob = MAPPER.readTree(bobText);
            assertEquals(0, bob.path("sessions").path("total").asInt(), "第三人会话数量为 0");
            assertEquals(0, bob.path("turns").path("total").asInt(), "第三人轮次数量为 0");
            assertEquals(0, bob.path("sessions").path("rows").size(), "第三人无会话对象");
            assertEquals(0, bob.path("turns").path("rows").size(), "第三人无轮次对象");
            assertFalse(bobText.contains("SESSION-"), "第三人投影不含会话标识");
            assertFalse(bobText.contains("dialogue-turn"), "第三人投影不含轮次文档标识");

            // 4) 双方本人投影：各 1 会话 3 轮；未读的标题与正文都为空，读了的两者都非空
            //    rita 只读到受访者那一轮；investor 读到记者两轮，自己那轮不算已读
            java.util.Map<String, Integer> expectedRead = java.util.Map.of("rita", 1, "investor", 2);
            for (String me : List.of("rita", "investor")) {
                JsonNode mine = MAPPER.readTree(
                        MAPPER.writeValueAsString(harness.host.dialogueView(me)));
                assertEquals(1, mine.path("sessions").path("total").asInt(), me + " 只一条本人会话");
                assertEquals(3, mine.path("turns").path("total").asInt(), me + " 三个本人轮次");
                int read = 0;
                int unread = 0;
                for (JsonNode row : mine.path("turns").path("rows")) {
                    if (row.path("readByMe").asBoolean()) {
                        read += 1;
                        assertFalse(row.path("title").asText().isEmpty(), me + " 已读必有标题");
                        assertFalse(row.path("body").asText().isEmpty(), me + " 已读必有正文");
                    } else {
                        unread += 1;
                        assertTrue(row.path("title").asText().isEmpty(),
                                me + " 未读不得返回标题：" + row.path("docId").asText());
                        assertTrue(row.path("body").asText().isEmpty(),
                                me + " 未读不得返回正文：" + row.path("docId").asText());
                    }
                }
                assertEquals(expectedRead.get(me).intValue(), read, me + " 读到的轮数");
                assertEquals(3 - expectedRead.get(me).intValue(), unread, me + " 未被自动标为已读的轮数");
            }

            // 5) HTTP 投影与 Host 投影一致；切身份后管理员端点 403
            JsonNode switchTo = admin.post("/api/session/person", Map.of("personId", "rita"));
            assertEquals("rita", switchTo.path("personId").asText());
            JsonNode httpMine = admin.get("/api/me/dialogue");
            JsonNode hostMine = MAPPER.readTree(
                    MAPPER.writeValueAsString(harness.host.dialogueView("rita")));
            assertEquals(hostMine.path("sessions").path("total").asInt(),
                    httpMine.path("sessions").path("total").asInt(), "HTTP 与 Host 会话数一致");
            assertEquals(hostMine.path("turns").path("total").asInt(),
                    httpMine.path("turns").path("total").asInt(), "HTTP 与 Host 轮次数一致");
            assertEquals(403, admin.getStatus("/api/admin/dialogue"), "人物会话读审计必须 403");
        }
    }
}
