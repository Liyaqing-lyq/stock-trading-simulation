package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** 有界真实模型输入对照；预先阅读为实验控制，回执不自动写成世界业务动作。 */
public final class RoleEvidenceProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PEOPLE = List.of("mfgstruct-d1", "digital-company-director-1", "hydro-director-1");
    private static final List<String> COMPANIES = List.of("mfg-struct", "digital-company", "hydro");

    /** args为独立输出目录、五槽凭据文件；最多18次调用，每条结果即时落盘。 */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        if (Files.exists(out.resolve("comparison.json"))) throw new IllegalArgumentException("不得覆盖已有实验");
        var keys = KeySlots.load(Path.of(args[1]));
        var client = new DeepSeekClient(() -> keys.credential("key-1"));
        var inputs = JSON.createObjectNode();
        for (String arm : List.of("legacy", "authority", "evidence")) {
            Path journal = out.resolve(arm + ".journal");
            try (WorldRuntime world = arm.equals("legacy") ? ComposedIndustryScenario.openEventAutonomous(journal)
                    : ComposedIndustryScenario.openEvidenceAutonomous(journal, arm.equals("authority"))) {
                for (int i = 0; i < PEOPLE.size(); i++) {
                    world.scheduleStartReading("opening-read-" + i, "opening-read-task-" + i, 2L,
                            PEOPLE.get(i), "company-governance:" + COMPANIES.get(i) + ":1", 1L);
                }
                world.advanceTo(1_002L);
                if (arm.equals("evidence")) {
                    for (int i = 0; i < PEOPLE.size(); i++) {
                        world.scheduleStartReading("evidence-read-" + i, "evidence-read-task-" + i, 1_003L,
                                PEOPLE.get(i), "role-evidence:" + COMPANIES.get(i) + ":1", 1L);
                    }
                }
                world.advanceTo(20_001L);
                var rows = inputs.putObject(arm);
                for (String person : PEOPLE) rows.set(person, world.autonomyRoleObservation(person));
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("inputs.json").toFile(), inputs);
        var results = JSON.createArrayNode();
        // 每个角色、每次重复交替三个实验臂，降低连续调用顺序偏差；不声称统计显著。
        for (int repeat = 0; repeat < 2; repeat++) {
            for (String person : PEOPLE) {
                for (String arm : repeat == 0 ? List.of("legacy", "authority", "evidence") : List.of("evidence", "authority", "legacy")) {
                    String input = inputs.path(arm).path(person).toString();
                    var row = results.addObject();
                    row.put("repeat", repeat).put("person", person).put("arm", arm);
                    row.put("inputSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))));
                    row.put("requestedModel", "deepseek-flash").put("maxOutputTokens", 2048);
                    row.put("systemSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(LiveDecisionProvider.PROTOTYPE_SYSTEM_TEXT.getBytes(StandardCharsets.UTF_8))));
                    try {
                        var response = client.send(new DeepSeekClient.Request("deepseek-flash", LiveDecisionProvider.PROTOTYPE_SYSTEM_TEXT, input, 2048, 60_000));
                        row.put("outcome", response.outcome().name()).put("content", response.content());
                        row.put("reportedModel", response.model());
                        row.set("usage", JSON.valueToTree(response.usage()));
                    } catch (DeepSeekClient.Failure failure) {
                        row.put("failureCode", failure.code().name());
                    }
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("comparison.json").toFile(), results);
                    System.out.println("completed=" + results.size() + " arm=" + arm + " person=" + person);
                }
            }
        }
    }
}
