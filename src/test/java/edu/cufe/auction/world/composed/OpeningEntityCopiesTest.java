package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.web.WorldWebHost;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OpeningEntityCopiesTest {
    @TempDir Path dir;
    private final Path example = Path.of("src/main/resources/world/opening-copies-example.json");
    private static BigDecimal sum(WorldRuntime w) {
        return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private static BigDecimal cash(WorldRuntime w,String account) { return w.snapshot().accounts().get(account).cash(); }

    private Path singleDraft(String field) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.createObjectNode();
        root.set(field, json.readTree(example.toFile()).required(field));
        Path file = dir.resolve(field+".json");
        json.writeValue(file.toFile(), root);
        return file;
    }

    @Test void standaloneInvestorHasOwnBankAndReadingWithoutCreatingAnEngineeringFirm() throws Exception {
        Path file=dir.resolve("investor.journal"), draft=singleDraft("investor");
        try (var control=ComposedIndustryScenario.openBaselinePartsOperating(dir.resolve("baseline.journal"));
             var w=ComposedIndustryScenario.openOpeningCopiesOperating(file,draft)) {
            var data=w.profile().manifest().dataVersions();
            assertEquals(OpeningEntityCopies.INDEPENDENT,data.get(OpeningEntityCopies.KEY));
            var plan=OpeningEntityCopies.fromManifest(data);
            assertNull(plan.company());
            assertEquals(control.openingRegistry().orElseThrow().securities(),w.openingRegistry().orElseThrow().securities());
            assertEquals(control.openingRegistry().orElseThrow().allocations(),w.openingRegistry().orElseThrow().allocations());
            assertEquals(control.openingRegistry().orElseThrow().parties().size()+1,w.openingRegistry().orElseThrow().parties().size());
            assertEquals(control.snapshot().accounts().size()+1,w.snapshot().accounts().size());
            for (var account:control.snapshot().accounts().entrySet()) assertEquals(account.getValue(),w.snapshot().accounts().get(account.getKey()));
            assertEquals(new BigDecimal("0.00"),cash(w,plan.investorAccount()));
            assertTrue(w.boardOfCompany("independent-engineering").isEmpty());
            assertFalse(w.snapshot().accounts().containsKey("COPY-COMPANY-independent-engineering"));
            assertTrue(w.autonomyRoleObservation(plan.investor().id()).at("/engineering/personalMarket").isObject());
            assertTrue(w.snapshot().information().orElseThrow().information().observations().stream().noneMatch(o->plan.investor().id().equals(o.personPartyId())));
            long readAt=w.snapshot().timeMillis()+10;
            w.openRoleSession(plan.investor().id()).startReading("mine","mine",readAt,"engineering-opportunity:A1",1);
            w.advanceTo(readAt+1000);
            assertTrue(w.snapshot().information().orElseThrow().information().observations().stream().anyMatch(o->plan.investor().id().equals(o.personPartyId())));
            assertFalse(w.autonomyRoleObservation(EngineeringCustomerPolicy.REPRESENTATIVE).toString().contains("NEW-INVITATION-1"));
        }
        Files.writeString(draft,"{}"); byte[] sealed=Files.readAllBytes(file);
        try (var w=ComposedIndustryScenario.openOpeningCopiesOperating(file,draft)) {
            assertNotNull(w.openingRegistry().orElseThrow().owner("independent-reader"));
            assertTrue(w.boardOfCompany("independent-engineering").isEmpty());
        }
        assertArrayEquals(sealed,Files.readAllBytes(file));
    }

    @Test void standaloneCompanyHasOwnBoardAndInvitationWithoutCreatingAnInvestor() throws Exception {
        Path file=dir.resolve("company.journal"),draft=singleDraft("company");
        try (var control=ComposedIndustryScenario.openBaselinePartsOperating(dir.resolve("baseline.journal"));
             var w=ComposedIndustryScenario.openOpeningCopiesOperating(file,draft)) {
            var plan=OpeningEntityCopies.fromManifest(w.profile().manifest().dataVersions());
            assertNull(plan.investor());
            assertNull(w.openingRegistry().orElseThrow().owner("independent-reader"));
            assertFalse(w.snapshot().accounts().containsKey("COPY-PERSON-independent-reader"));
            assertEquals(control.openingRegistry().orElseThrow().parties().size()+1,w.openingRegistry().orElseThrow().parties().size());
            assertEquals(control.openingRegistry().orElseThrow().allocations(),w.openingRegistry().orElseThrow().allocations());
            assertEquals(control.snapshot().accounts().size()+1,w.snapshot().accounts().size());
            assertEquals(sum(control),sum(w)); assertEquals(new BigDecimal("0.00"),cash(w,plan.companyAccount()));
            String firm=plan.company().id();
            assertEquals(plan.company().directors(),w.boardOfCompany(firm).orElseThrow().board().charter().seats().stream().map(BoardModel.Seat::partyId).toList());
            assertTrue(w.snapshot().engineering().orElseThrow().stateOf(firm).contracts().isEmpty());
            assertTrue(ComposedCompanyReport.of(w,firm).balanced());
            assertTrue(w.autonomyRoleObservation(plan.company().directors().get(0)).at("/engineering/directorships").toString().contains(firm));
            assertFalse(w.autonomyRoleObservation("nina").at("/engineering/directorships").toString().contains(firm));
            assertEquals(plan.company().invitationId(),w.autonomyRoleObservation(EngineeringCustomerPolicy.REPRESENTATIVE)
                    .at("/engineering/customerMandate/additionalInvitations/0/opportunities/0").textValue());
        }
        Files.writeString(draft,Files.readString(example)); byte[] sealed=Files.readAllBytes(file);
        try (var w=ComposedIndustryScenario.openOpeningCopiesOperating(file,draft)) {
            assertNull(OpeningEntityCopies.fromManifest(w.profile().manifest().dataVersions()).investor());
            assertTrue(w.boardOfCompany("independent-engineering").isPresent());
        }
        assertArrayEquals(sealed,Files.readAllBytes(file));
    }

    @Test void independentDraftsStillRequireRealOwnersSeatsAndCustomerSources() throws Exception {
        String investor=Files.readString(singleDraft("investor"));
        String company=Files.readString(singleDraft("company"));
        for (String bad : List.of(investor.replace("nina","absent-source"), investor.replace("independent-reader","nina"),
                company.replace("mfgtech-d4","absent-director"), company.replace("eng-a-customer","absent-customer"),
                company.replace("A1","absent-quote"))) {
            var draft=Files.createTempFile(dir,"invalid",".json");
            Files.writeString(draft,bad);
            Path target=dir.resolve(draft.getFileName()+".journal");
            var error=assertThrows(Exception.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(target,draft));
            assertTrue(error instanceof IllegalArgumentException || error instanceof com.fasterxml.jackson.databind.JsonMappingException,
                    error::toString);
            assertTrue(!Files.exists(target) || Files.size(target)==0);
        }
    }

    @Test void validDigestsCannotMixPairedAndSingleSchemasOrUpgradeSiblingWorlds() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var old=json.readValue(example.toFile(),OpeningEntityCopies.class);
        var solo=json.readValue(singleDraft("company").toFile(),OpeningEntityCopies.class);
        var data=new java.util.TreeMap<String,String>();
        old.freeze(data); data.put(OpeningEntityCopies.KEY,OpeningEntityCopies.INDEPENDENT);
        assertThrows(IllegalArgumentException.class,()->OpeningEntityCopies.fromManifest(data));
        solo.freeze(data); data.put(OpeningEntityCopies.KEY,OpeningEntityCopies.VERSION);
        assertThrows(IllegalArgumentException.class,()->OpeningEntityCopies.fromManifest(data));
        solo.freeze(data);
        assertThrows(IllegalArgumentException.class,()->OpeningEntityCopies.fromManifest(data,RunManifest.OWNER_BANK_VALUATION_RUN_ID));
        var sibling=dir.resolve("other.journal");
        assertThrows(IllegalArgumentException.class,()->ComposedIndustryScenario.openOwnerBankValuationOperating(sibling,singleDraft("company")));
        assertFalse(Files.exists(sibling));
        var noPlan=dir.resolve("none.json");Files.writeString(noPlan,"{}");
        assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(sibling,noPlan));
        assertFalse(Files.exists(sibling));
    }

    @Test void actualDraftCreatesIndependentAccountsOneUnlistedCompanyAndNoPrivateHistory() throws Exception {
        Path journal=dir.resolve("copies.journal"),draft=dir.resolve("my-draft.json");
        String custom=Files.readString(example).replace("independent-reader","new-investor-2")
                .replace("independent-engineering","new-firm-2").replace("NEW-INVITATION-1","TENDER-2");
        Files.writeString(draft,custom);
        long end;
        try(var control=ComposedIndustryScenario.openBaselinePartsOperating(dir.resolve("control.journal"));
            var w=ComposedIndustryScenario.openOpeningCopiesOperating(journal,draft)) {
            var plan=OpeningEntityCopies.fromManifest(w.profile().manifest().dataVersions());
            String investor=plan.investor().id(), company=plan.company().id();
            assertEquals("new-firm-2",company);
            var registry=w.openingRegistry().orElseThrow(); var old=control.openingRegistry().orElseThrow();
            assertEquals(old.allocations(),registry.allocations()); assertEquals(old.securities(),registry.securities());
            assertEquals(old.parties().size()+2,registry.parties().size());
            for(var a:control.snapshot().accounts().entrySet()) assertEquals(a.getValue(),w.snapshot().accounts().get(a.getKey()));
            assertEquals(sum(control),sum(w)); assertEquals(new BigDecimal("0.00"),cash(w,plan.investorAccount()));
            assertEquals(new BigDecimal("0.00"),cash(w,plan.companyAccount()));
            var newCompany=ComposedCompanyReport.of(w,company); assertTrue(newCompany.balanced());
            assertTrue(newCompany.equity().values().stream().allMatch(x->x.signum()==0));
            var engineering=w.snapshot().engineering().orElseThrow();
            assertEquals(control.snapshot().engineering().orElseThrow().stateOf("eng-a").contracts(),engineering.stateOf("eng-a").contracts());
            assertTrue(engineering.stateOf(company).contracts().isEmpty()); assertTrue(engineering.stateOf(company).workOrders().isEmpty());
            assertEquals(plan.company().directors(),engineering.boardOf(company).board().charter().seats().stream().map(BoardModel.Seat::partyId).toList());
            var v=w.autonomyRoleObservation(investor);
            assertTrue(v.at("/engineering/personalMarket").isObject());
            assertTrue(v.at("/engineering/directorships").isEmpty()); assertTrue(v.at("/engineering/managerships").isEmpty());
            assertFalse(v.toString().contains("household-plans-v1/nina"));
            assertTrue(w.snapshot().information().orElseThrow().information().observations().stream().noneMatch(o->investor.equals(o.personPartyId())));
            // 原人真实阅读不会为副本制造记忆；副本可以随后用自己的账户和身份阅读。
            long at=w.snapshot().timeMillis()+10; String doc="engineering-opportunity:TENDER-2";
            w.openRoleSession("nina").startReading("source-read","source-read",at,doc,1); w.advanceTo(at+1000);
            assertTrue(w.snapshot().information().orElseThrow().information().observations().stream().noneMatch(o->investor.equals(o.personPartyId())));
            at=w.snapshot().timeMillis()+10;
            w.openRoleSession(investor).startReading("copy-read","copy-read",at,doc,1); w.advanceTo(at+1000);
            assertTrue(w.snapshot().information().orElseThrow().information().observations().stream().anyMatch(o->investor.equals(o.personPartyId()) && doc.equals(o.docId())));
            w.scheduleTransfer("own-transfer","own-transfer",w.snapshot().timeMillis()+10,"nina","HOUSE-NINA",plan.investorAccount(),new BigDecimal("15.00"),0);
            w.advanceTo(w.snapshot().timeMillis()+10);
            assertEquals("TRANSFER_UNCLASSIFIED",w.taskCommitResult("own-transfer"));
            assertEquals(new BigDecimal("0.00"),cash(w,plan.investorAccount()));
            w.scheduleCapitalFlow("named-contribution","named-contribution",w.snapshot().timeMillis()+10,"nina",
                    "HOUSE-NINA",plan.investorAccount(),new BigDecimal("15.00"),0,"Nina 从自有现金向新登记投资者投入 15 元");
            w.advanceTo(w.snapshot().timeMillis()+10);
            assertEquals(new BigDecimal("15.00"),cash(w,plan.investorAccount()));
            w.advanceTo(570*60000L);
            assertEquals("OPEN",MarketSessions.status("MT1",w.snapshot().timeMillis(),false));
            var buy=IssuerCreditDecisionTest.JSON.createObjectNode().put("accountId",plan.investorAccount())
                    .put("symbol","MT1").put("side","BUY").put("limitPrice","10.00").put("quantity",1);
            var placed=IssuerCreditDecisionTest.decide(w,investor,"new-person-order",PersonalMarket.SUBMIT,buy);
            assertNotNull(placed); w.advanceTo(placed.dueMillis()+1);
            assertEquals(new BigDecimal("10.00"),WorldExchange.frozenCash(w.snapshot().exchange().orElseThrow(),plan.investorAccount()),
                    w.autonomyRoleObservation(investor).at("/engineering/deliberation/lastActionResult").toString());
            ComposedWorldSelfTest.appoint(w,w.snapshot().timeMillis()+1,company,investor,"copy-office","copy-election");
            long due=w.snapshot().timeMillis()+2000;
            w.scheduleEngineeringAction("copy-sign","copy-sign-task",due,company,investor,"SIGN","TENDER-2","",0,
                    0,BigDecimal.ZERO,"",0,1000); w.advanceTo(due);
            var report=w.snapshot().engineering().orElseThrow().reports().get("copy-sign-task:finish");
            assertTrue(report.accepted(),report.toString());
            assertTrue(w.snapshot().engineering().orElseThrow().stateOf(company).contracts().containsKey("TENDER-2"));
            assertTrue(w.snapshot().engineering().orElseThrow().stateOf("eng-a").contracts().isEmpty());
            var invitation=w.autonomyRoleObservation(EngineeringCustomerPolicy.REPRESENTATIVE)
                    .at("/engineering/customerMandate/additionalInvitations/0");
            assertEquals(company,invitation.path("contractor").asText());
            assertEquals("TENDER-2",invitation.at("/opportunities/0").asText());
            assertTrue(invitation.path("invoices").isArray());
            assertTrue(invitation.path("invoices").isEmpty(),"未开票的合同不能产生应收");
            assertEquals(cash(control,"ENG-A"),cash(w,"ENG-A"));
            assertEquals(new BigDecimal("0.00"),cash(w,plan.companyAccount()));
            assertEquals(cash(control,"ENG-A-CUS").subtract(cash(w,"ENG-A-CUS")),cash(w,plan.companyAccount()));
            assertEquals(sum(control),sum(w)); assertTrue(ComposedCompanyReport.of(w,company).balanced());
            end=w.snapshot().position();
        }
        Files.writeString(draft,"{}");
        try(var w=ComposedIndustryScenario.openOpeningCopiesOperating(journal,draft)){
            assertEquals(end,w.snapshot().position());
            var c=OpeningEntityCopies.fromManifest(w.profile().manifest().dataVersions());
            assertEquals("new-investor-2",c.investor().id());assertEquals(new BigDecimal("15.00"),cash(w,c.investorAccount()));
            assertTrue(w.snapshot().engineering().orElseThrow().stateOf(c.company().id()).contracts().containsKey("TENDER-2"));
        }
    }

    @Test void badReferencesAndUnknownFieldsCannotCreateAWorld() throws Exception {
        var original=Files.readString(example);
        for(String bad:List.of(original.replace("independent-reader","nina"),
                original.replace("mfgtech-d1","unknown-director"),original.replace("NEW-INVITATION-1","A2"),
                original.replaceFirst("\"source\":\"nina\"","\"cash\":1000,\"source\":\"nina\""))) {
            Path input=dir.resolve("bad.json"), journal=dir.resolve("bad.journal");
            Files.writeString(input,bad);
            if (bad.contains("\"cash\"")) assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                    ()->ComposedIndustryScenario.openOpeningCopiesOperating(journal,input));
            else if (bad.contains("\"id\":\"nina\"")) assertThrows(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                    ()->ComposedIndustryScenario.openOpeningCopiesOperating(journal,input));
            else assertThrows(IllegalArgumentException.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(journal,input));
            assertFalse(Files.exists(journal) && Files.size(journal)>0);
        }
    }

    @Test void aCompanyCanStartWithoutAnInvitationOrACustomerClaim() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(example.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("company")).remove(List.of("customer","sourceQuote","invitationId"));
        Path draft=dir.resolve("no-invitation.json");json.writeValue(draft.toFile(),config);
        try(var w=ComposedIndustryScenario.openOpeningCopiesOperating(dir.resolve("empty.journal"),draft)) {
            var engine=w.snapshot().engineering().orElseThrow();
            var spec=engine.config().specOf("independent-engineering");
            assertEquals("eng-a",spec.templateSource());
            assertTrue(spec.contracts().isEmpty());
            assertTrue(w.autonomyRoleObservation(EngineeringCustomerPolicy.REPRESENTATIVE)
                    .at("/engineering/customerMandate/additionalInvitations").isMissingNode());
            assertTrue(engine.stateOf(spec.companyPartyId()).contracts().isEmpty());
            assertEquals(2,w.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d->d.docId().startsWith("engineering-opportunity:")).count());
            assertTrue(ComposedCompanyReport.of(w,spec.companyPartyId()).balanced());
            var catalog=w.roleView("independent-reader").catalog();
            assertEquals(List.of("engineering-opportunity:A1","engineering-opportunity:A2"),catalog.stream()
                    .map(entry->entry.docId()).filter(id->id.startsWith("engineering-opportunity:")).sorted().toList());
        }
        try(var host=new WorldWebHost(()->{
            var w=ComposedIndustryScenario.openOpeningCopiesOperating(dir.resolve("empty.journal"),draft);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        })) {
            var publicView=json.valueToTree(host.worldOverview());
            var firm=java.util.stream.StreamSupport.stream(publicView.path("companies").spliterator(),false)
                    .filter(row->"independent-engineering".equals(row.path("id").asText())).findFirst().orElseThrow();
            assertTrue(firm.path("publications").isEmpty()); assertTrue(firm.path("symbol").isNull());
            assertFalse(publicView.toString().contains("NEW-INVITATION-1"));
        }
    }

    @Test void previousWorldAndPartialFirstFrameCannotBeBackfilledByAValidDraft() throws Exception {
        Path legacy=dir.resolve("previous.journal");
        long originalPosition;
        try(var earlier=ComposedIndustryScenario.openBaselinePartsOperating(legacy)) {
            originalPosition=earlier.snapshot().position();
        }
        byte[] previous=Files.readAllBytes(legacy);
        assertThrows(IllegalArgumentException.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(legacy,example));
        assertArrayEquals(previous,Files.readAllBytes(legacy));
        try(var earlier=ComposedIndustryScenario.openBaselinePartsOperating(legacy)) {
            assertEquals(originalPosition,earlier.snapshot().position());
            assertFalse(earlier.snapshot().accounts().containsKey("COPY-PERSON-independent-reader"));
        }
        assertArrayEquals(previous,Files.readAllBytes(legacy));
        Files.write(legacy,new byte[]{1,2,3},java.nio.file.StandardOpenOption.APPEND);
        byte[] partialTail=Files.readAllBytes(legacy);
        assertThrows(IllegalArgumentException.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(legacy,example));
        assertArrayEquals(partialTail,Files.readAllBytes(legacy));
        Path prefix=dir.resolve("incomplete-opening.journal");
        Files.write(prefix,java.util.Arrays.copyOf(previous,9));
        byte[] partialFirst=Files.readAllBytes(prefix);
        assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(prefix,example));
        assertArrayEquals(partialFirst,Files.readAllBytes(prefix));
    }

    @Test void changingTheFrozenInvitationCannotResumeWithTheOriginalHash() throws Exception {
        Path genuine=dir.resolve("genuine.journal"),changed=dir.resolve("changed.journal");
        try(var w=ComposedIndustryScenario.openOpeningCopiesOperating(genuine,example)){
            assertTrue(w.snapshot().position()>1);
        }
        try(var from=new WorldJournal(genuine,RunManifest.OPENING_COPIES_RUN_ID);
            var to=new WorldJournal(changed,RunManifest.OPENING_COPIES_RUN_ID)){
            for(var c:from.commits()){
                String content=c.commandContent();
                if (c.position()==1) content=content.replace("NEW-INVITATION-1","UNAUTHORIZED-INVITATION");
                to.commit(c.commandId(),content,c.result(),c.events());
            }
        }
        byte[] before=Files.readAllBytes(changed);
        assertThrows(IllegalArgumentException.class,()->ComposedIndustryScenario.openOpeningCopiesOperating(changed,example));
        assertArrayEquals(before,Files.readAllBytes(changed));
    }
}
