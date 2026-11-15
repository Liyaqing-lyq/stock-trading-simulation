package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 新开局材料走真实发布、实读和任免，不用测试另造证据资料。 */
class CompanyOpeningDocumentsTest {
    @TempDir Path dir;

    @Test void realOpeningMaterialSupportsAppointmentOnlyAfterReadingAndVoting() throws Exception {
        Path journal = dir.resolve("ready.journal");
        String company = "pharma";
        String document = CompanyOpeningDocuments.documentId(company);
        List<String> voters = List.of("frank", "grace", "heidi", "ivan");
        try (WorldRuntime world = ComposedPharmaScenario.openRunnable(journal)) {
            assertEquals(1L, world.snapshot().timeMillis());
            var information = world.snapshot().information().orElseThrow().information();
            assertTrue(information.documents().stream().anyMatch(doc -> doc.docId().equals(document)));
            assertTrue(information.observations().isEmpty(), "发布不得伪造人物已读经历");
            assertFalse(world.isActiveManager(company, "kate", 1L));
            assertTrue(world.boardOfCompany(company).orElseThrow().board().budgets().isEmpty());
            for (int i = 0; i < voters.size(); i++) {
                String person = voters.get(i);
                world.scheduleStartReading("read-" + person, "read-task-" + person,
                        20L + i, person, document, 1L);
            }
            world.advanceTo(1_019L);
            assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "frank", document, 1L, 1_019L));
            world.advanceTo(1_100L);
            assertTrue(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "frank", document, 1L, 1_100L));
            assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "kate", document, 1L, 1_100L));
            world.scheduleCompanyBoardConvene("appointment", "appointment-task", 6_000L,
                    company, "frank", "opening-appointment", 6_500L, 7_000L, 10_000L, true);
            for (int i = 0; i < voters.size(); i++) {
                world.scheduleCompanyBoardAttend("attend-" + i, "attend-task-" + i,
                        6_100L + i, company, voters.get(i), "opening-appointment");
            }
            world.scheduleCompanyBoardMaterial("material", "material-task", 6_300L,
                    company, "frank", "opening-appointment", "m1", document, 1L);
            world.scheduleCompanyBoardAppointmentProposal("proposal", "proposal-task", 6_400L,
                    company, "frank", "opening-appointment", new BoardModel.AppointmentRequest(
                            "opening-kate", "kate", company, RoleAccess.Role.COMPANY_MANAGER,
                            BoardModel.AppointmentAction.APPOINT, 12_000L, 86_400_000L));
            for (int i = 0; i < voters.size(); i++) {
                world.scheduleCompanyBoardVote("vote-" + i, "vote-task-" + i, 9_000L + i,
                        company, voters.get(i), "opening-appointment", BoardModel.Vote.YES, 1_000L);
            }
            world.scheduleCompanyBoardImplement("implement", "implement-task", 11_000L,
                    company, "frank", "opening-appointment");
            world.advanceTo(12_000L);
            assertTrue(world.isActiveManager(company, "kate", 12_000L));
            assertTrue(world.boardOfCompany(company).orElseThrow().board().budgets().isEmpty());
            assertEquals("2000.00", world.snapshot().accounts().get("RND-PHARMA").cash().toPlainString());
        }
        byte[] before = Files.readAllBytes(journal);
        try (WorldRuntime world = ComposedPharmaScenario.openRunnable(journal)) {
            assertTrue(world.isActiveManager(company, "kate", 12_000L));
            assertTrue(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "frank", document, 1L, world.snapshot().timeMillis()));
        }
        assertArrayEquals(before, Files.readAllBytes(journal), "重开不重复发布开局资料");
    }

    @Test void oldDebugProfileRemainsWithoutNewOpeningDocuments() throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(dir.resolve("old.journal"))) {
            assertEquals(0L, world.snapshot().timeMillis());
            assertFalse(world.profile().manifest().dataVersions().containsKey("company-introduction"));
            assertTrue(world.snapshot().information().orElseThrow().information().documents().isEmpty());
        }
    }
}
