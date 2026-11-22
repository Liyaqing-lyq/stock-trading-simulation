package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VisibleCatalogReadingTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INVESTOR = "investor";
    private static final List<String> ISSUERS = List.of("north", "aged-company", "digital-company",
            "eng-a", "hydro", "mfg-struct", "mfg-tech", "resources", "mfg-versa", "pharma");

    private static JsonNode catalogEntry(JsonNode role, String id) {
        for (JsonNode item : role.at("/profile/catalog")) {
            if (id.equals(item.path("docId").asText())) return item;
        }
        return null;
    }

    private static long read(WorldRuntime w, String person, String doc, int version, long duration) throws Exception {
        long start = w.snapshot().timeMillis() + 1;
        String id = "read-" + person + "-" + start;
        w.openRoleSession(person).startReading(id, id, start, doc, version);
        w.advanceTo(start + duration);
        return w.snapshot().timeMillis();
    }

    private static String doc(String issuer) { return "company-governance:" + issuer + ":1"; }

    @Test void previouslyReadVersionSurvivesBodyTruncationAndFrozenDecisionReopens() throws Exception {
        Path file = dir.resolve("world.journal");
        String north = doc("north"), aged = doc("aged-company"), input;
        try (var w = ComposedIndustryScenario.openForwardBudgetOperating(file)) {
            long first = read(w, INVESTOR, north, 1, 1000);
            long second = read(w, INVESTOR, aged, 1, 1000);
            for (String issuer : ISSUERS.subList(2, ISSUERS.size())) read(w, INVESTOR, doc(issuer), 1, 1000);
            var role = w.autonomyRoleObservation(INVESTOR);
            assertTrue(role.path("recallTruncated").asBoolean());
            assertEquals(8, role.at("/profile/readExperiences").size());
            assertFalse(role.at("/profile/readExperiences").toString().contains(north));
            assertFalse(role.at("/profile/readExperiences").toString().contains(aged));
            assertEquals(first, catalogEntry(role, north).path("lastReadAtMillis").asLong(-1));
            assertEquals(second, catalogEntry(role, aged).path("lastReadAtMillis").asLong(-1));
            assertFalse(catalogEntry(role,north).has("body"));
            assertFalse(catalogEntry(w.autonomyRoleObservation("dave"), north).has("lastReadAtMillis"));
            var latest = read(w, INVESTOR, aged, 1, 1000);
            assertEquals(latest,catalogEntry(w.autonomyRoleObservation(INVESTOR),aged).path("lastReadAtMillis").asLong(-1));
            long now=w.snapshot().timeMillis();
            w.schedulePublishDocumentVersion("update","update",now+1,aged,"新版","修改后的正文","公开更正",1000,List.of());
            w.advanceTo(now+1);
            var updated=catalogEntry(w.autonomyRoleObservation(INVESTOR),aged);
            assertEquals(2,updated.get("version").asInt());assertFalse(updated.has("lastReadAtMillis"));
            long secondVersionRead = read(w, INVESTOR, aged, 2, 1000);
            assertEquals(secondVersionRead, catalogEntry(w.autonomyRoleObservation(INVESTOR), aged)
                    .path("lastReadAtMillis").asLong(-1));
            String actionId="capture-visible-catalog";
            w.beginDecisionSession("capture-session");
            w.openDecisions("capture-input", List.of(new DecisionRun.Request(INVESTOR,actionId,"wake",
                    new DecisionModel.Route("trading", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1024, 1))));
            input=w.snapshot().decisions().orElseThrow().state().decisions().stream()
                    .filter(d->actionId.equals(d.actionId())).findFirst().orElseThrow().inputText();
            assertEquals(first,catalogEntry(JSON.readTree(input),north).path("lastReadAtMillis").asLong(-1));
            assertEquals(secondVersionRead, catalogEntry(JSON.readTree(input), aged).path("lastReadAtMillis").asLong(-1));
        }
        long size=Files.size(file);
        try(var w=ComposedIndustryScenario.openForwardBudgetOperating(file)) {
            assertEquals(input,w.snapshot().decisions().orElseThrow().state().decisions().get(0).inputText());
        }
        assertEquals(size,Files.size(file));
    }

    @Test void unreadOrNoLongerAuthorizedCatalogDoesNotExposeReadReceipts() throws Exception {
        try(var w=ComposedIndustryScenario.openForwardBudgetOperating(dir.resolve("privacy.journal"))) {
            String privateDoc="private-team-note",publicDoc="ordinary-open-note";
            long now=w.snapshot().timeMillis();
            w.schedulePublishDocument("new-private","new-private",now+1,privateDoc,"dave",
                    InformationModel.Audience.organization("cufe"),"本机构资料","机构正文","机构原件",1000,List.of());
            w.schedulePublishDocument("new-public","new-public",now+1,publicDoc,"dave",
                    InformationModel.Audience.publicAudience(),"普通资料","公开正文","公开原件",1000,List.of());
            w.advanceTo(now+1);
            var session=w.autonomyRoleObservation("dave");String roleId=session.path("appointments").get(0).get("appointmentId").asText();
            assertFalse(catalogEntry(session,privateDoc).has("lastReadAtMillis"));
            assertNull(catalogEntry(w.autonomyRoleObservation(INVESTOR),privateDoc));
            long start=w.snapshot().timeMillis()+1;w.openRoleSession("dave").startReading("start","start",start,privateDoc,1);
            w.advanceTo(start);assertFalse(catalogEntry(w.autonomyRoleObservation("dave"),privateDoc).has("lastReadAtMillis"));
            w.advanceTo(start+1000);assertEquals(start+1000,catalogEntry(w.autonomyRoleObservation("dave"),privateDoc).path("lastReadAtMillis").asLong(-1));
            long completed=read(w,"dave",publicDoc,1,1000);
            long retry=completed+1;
            w.openRoleSession("dave").startReading("reread","reread",retry,publicDoc,1);
            w.advanceTo(retry);
            var reading=w.roleView("dave").readings().get(0).ref();
            w.openRoleSession("dave").cancelReading("cancel","cancel",retry+1,reading);
            w.advanceTo(retry+1);
            assertEquals(completed,catalogEntry(w.autonomyRoleObservation("dave"),publicDoc).path("lastReadAtMillis").asLong(-1));
            assertFalse(catalogEntry(w.autonomyRoleObservation(INVESTOR),publicDoc).has("lastReadAtMillis"));
            long end=w.snapshot().timeMillis()+1;w.scheduleRevokeAppointment("revoke","revoke",end,roleId);w.advanceTo(end);
            assertNull(catalogEntry(w.autonomyRoleObservation("dave"),privateDoc));
        }
    }

    @Test void oldInformationWorldKeepsItsOriginalCatalogShape() throws Exception {
        try(var w=ComposedIndustryScenario.openCompanyInformationOperating(dir.resolve("v5.journal"))) {
            read(w,INVESTOR,doc("north"),1,1000);var v=catalogEntry(w.autonomyRoleObservation(INVESTOR),doc("north"));
            assertEquals(3,v.size());assertFalse(v.has("lastReadAtMillis"));
        }
    }
}
