package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankingProfile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResearchLicensingWorldTest {
    static final long DAY = 86_400_000L;
    static final String COMPANY = "pharma", CUSTOMER = ExternalDemandOpening.ACCOUNT_ID;
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    // 同一个独立五证券药企的原生初始资本、章程与项目，另外登记实际付款客户。
    static WorldRuntime sector(Path file, boolean prototypeClock) throws Exception {
        return sector(file,prototypeClock,ExternalDemandOpening.account());
    }

    private static WorldRuntime sector(Path file, boolean prototypeClock, CashLedger.OpeningAccount customer) throws Exception {
        var old = ComposedPharmaScenario.manifest();
        var manifest = new RunManifest("pharma-license-sector-" + prototypeClock, prototypeClock ? 13 : old.seed(),
                old.rulesVersion(), old.moduleVersions(), Map.of("profile","pharma-debug-v1",
                "operating-observation","v2","role-account-projection","authorized-v1","company-decision","v1",
                "role-context", RoleDeliberation.CONTEXTUAL_VERSION,
                RunManifest.PHARMA_LICENSING, RunManifest.APPROVED_USE_CONTRACT), old.modelConfigVersion());
        var funds = new ArrayList<>(ComposedPharmaScenario.openings()); funds.add(customer);
        var oldRegistry = ComposedPharmaScenario.registry();
        var parties = new ArrayList<>(oldRegistry.parties()); var owners = new ArrayList<>(oldRegistry.owners());
        var owner = ExternalDemandOpening.OWNER_ID;
        parties.add(new OpeningRegistry.Party(owner, OpeningRegistry.PartyKind.ORGANIZATION, "EXP有限外部客户"));
        owners.add(new OpeningRegistry.PropertyOwner(owner, OpeningRegistry.OwnerKind.ORGANIZATION, owner));
        var registry = new OpeningRegistry(parties, owners, oldRegistry.securities(), oldRegistry.allocations());
        var bank = ComposedPharmaScenario.bankOpening().config().profile();
        var payers = new TreeMap<>(bank.parties());
        payers.put(owner, new BankingProfile.PartyRef(owner, owner, CUSTOMER, null));
        var profile = new BankingProfile(bank.banks(),payers,bank.custodians(),bank.buckets(),bank.groups(),bank.loanAccounts(),
                bank.llpAccounts(),bank.depositAccounts(),bank.providerKey(),bank.subordinatedOwnerKey(),bank.backgroundBorrowerKey());
        var opening = BankOpeningRequest.of(ComposedWorldScenario.config(profile), ComposedWorldScenario.endowment(), Map.of(), Map.of());
        var cfg = ComposedPharmaScenario.debugDecisions(); var actions = new TreeMap<>(cfg.actionMillisByTool());
        actions.put(ResearchLicensing.TOOL, ResearchDecisionTools.ACTION_MILLIS);
        var decisions = new DecisionModel.Config(cfg.providerKind(),cfg.providerId(),cfg.pricingVersion(),cfg.thinkMillisByWake(),
                actions,cfg.limits(),cfg.budget(),cfg.profile());
        boolean fresh = !Files.exists(file) || Files.size(file)==0;
        var w = ComposedWorldScenario.open(file, manifest, funds, registry, opening, decisions, ComposedPharmaScenario.extras());
        if (fresh) w.initializeResearch("boot",COMPANY,new ResearchModel.Config("pharma-owner","RND-PHARMA",BigDecimal.ZERO),
                prototypeClock ? PrototypeCompanies.researchProjects() : WorldWebScenario.researchProjects(), 1);
        return w;
    }

    static String work(WorldRuntime w,String person,String id,String action) throws Exception {
        long now = w.snapshot().timeMillis();
        w.scheduleResearchLicense(person,action,"p1",id,now);
        w.advanceTo(now + 1001);
        return w.taskResultForTest(WorldRuntime.roleTaskId(person, id + "-task") + ":finish");
    }

    static void meeting(WorldRuntime w,String id,boolean hiring) throws Exception {
        var voters=ComposedPharmaScenario.DIRECTORS.subList(0,4);
        String chair=voters.get(0), doc=id+"-proposal";
        long pub=w.snapshot().timeMillis()+1L;
        String text=hiring ? "提议任命 Kate 为本公司经理，任期90模拟日。" : "提议批准本公司第0期采购预算上限900元。";
        w.schedulePublishDocument(doc,doc,pub,doc,chair,InformationModel.Audience.publicAudience(),"药企会议材料",text,text,1000,List.of());
        w.advanceTo(pub);
        for (String person:voters) EditorialReviewFeedbackTest.read(w,person,doc,1);
        long at=w.snapshot().timeMillis()+1000;
        ConnectedWorldBootstrap.convene(w,id,id,at,COMPANY,chair,id,at+200,at+500,at+4000,hiring);
        for(int i=0;i<voters.size();i++) {
            String part=id+"-"+i;
            ConnectedWorldBootstrap.attend(w,part+"-att",part+"-att",at+50+i,COMPANY,voters.get(i),id);
            ConnectedWorldBootstrap.vote(w,part+"-vote",part+"-vote",at+2000+i,COMPANY,voters.get(i),id,BoardModel.Vote.YES,1000);
        }
        ConnectedWorldBootstrap.material(w,id+"-mat",id+"-mat",at+100,COMPANY,chair,id,"m1",doc,1);
        if(hiring) ConnectedWorldBootstrap.appoint(w,id+"-appoint",id+"-appt-task",at+140,COMPANY,chair,id,
                new BoardModel.AppointmentRequest(id+"-office","kate",COMPANY,RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.APPOINT,at+6000,at+6000+90*DAY));
        else ConnectedWorldBootstrap.budget(w,id+"-budget",id+"-budget",at+140,COMPANY,chair,id,List.of(
                new BoardModel.BudgetLine(0,BoardModel.BudgetCategory.PROCUREMENT,new BigDecimal("900.00"))));
        ConnectedWorldBootstrap.implement(w,id+"-implement",id+"-implement",at+4500,COMPANY,chair,id);
        w.advanceTo(at+6000);
        assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,w.boardOfCompany(COMPANY).orElseThrow().board().meeting(id).status());
        if(hiring) assertTrue(w.isActiveManager(COMPANY,"kate",w.snapshot().timeMillis()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void signedRightsDeliveryAndBankSettlementRemainIndependentAndRecover(boolean funded) throws Exception {
        Path file = dir.resolve("license.journal");
        var customer=funded ? ExternalDemandOpening.account() : new CashLedger.OpeningAccount(CUSTOMER,ExternalDemandOpening.OWNER_ID,
                new BigDecimal("299.99"),"独立分项的有限客户余额，较本次许可价少一分");
        WorldRuntime.Snapshot cut;
        try (var w=sector(file,false,customer)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(w);
            long docs=ComposedPharmaWorldTest.completeP1(w);
            assertEquals("COMMERCIAL_APPROVAL_REQUIRED", work(w,"ivan","before-approval","SIGN"));
            ComposedPharmaWorldTest.readEvidence(w,docs,List.of("frank","grace","heidi","ivan"));
            long at=w.snapshot().timeMillis()+1000;
            ComposedPharmaWorldTest.runCommercializationMeeting(w,at,List.of("frank","grace","heidi"));
            w.openRoleSession("ivan").approveCommercialization("approve","approve-task",at+5200,COMPANY,"p1","ph-cm-1",1000);
            w.advanceTo(at+5200);
            assertEquals(ResearchModel.ProjectStatus.APPROVED,w.researchRun().orElseThrow().state().projects().get("p1").status());
            assertNull(w.researchRun().orElseThrow().state().licenses());
            assertEquals("LICENSE_SIGNED",work(w,"ivan","sign","SIGN"));
            var sign=w.researchRun().orElseThrow().state().licenses().get("p1");
            assertEquals("LICENSE_ALREADY_SIGNED",work(w,"ivan","sign-again","SIGN"));
            assertEquals("LICENSE_DELIVERY_NOT_DUE",work(w,"ivan","early-delivery","DELIVER"));
            w.advanceTo(sign.deliverableAt()-1001);
            assertEquals("LICENSE_DELIVERED",work(w,"ivan","deliver","DELIVER"));
            var report=ResearchModel.report(w.researchRun().orElseThrow().state(),w.snapshot().accounts());
            assertTrue(report.balanced());
            assertEquals(new BigDecimal("1100.00"),report.companyCash());
            assertEquals(new BigDecimal("300.00"),report.licensing().receivable());
            assertEquals(report.licensing().receivable(),report.licensing().customerPayable());
            assertEquals(new BigDecimal("100.00"),report.profit());
            assertEquals("LICENSE_ALREADY_DELIVERED",work(w,"ivan","deliver-again","DELIVER"));
            assertEquals("LICENSE_PAYMENT_NOT_DUE",work(w,"ivan","early-cash","COLLECT"));
            w.advanceTo(w.researchRun().orElseThrow().state().licenses().get("p1").dueAt()-1001);
            long observed=w.snapshot().timeMillis();
            String scheduled=w.scheduleResearchLicense("ivan","COLLECT","p1","payment",observed);
            w.advanceTo(observed+1001);
            assertEquals(scheduled,w.scheduleResearchLicense("ivan","COLLECT","p1","payment",observed));
            assertThrows(IllegalArgumentException.class,()->w.scheduleResearchLicense("ivan","SIGN","p1","payment",observed));
            assertEquals(funded?"LICENSE_ALREADY_PAID":"LICENSE_CUSTOMER_INSUFFICIENT_FUNDS",work(w,"ivan","collect-again","COLLECT"));
            report=ResearchModel.report(w.researchRun().orElseThrow().state(),w.snapshot().accounts());
            assertEquals(new BigDecimal(funded?"89700.00":"299.99"),w.snapshot().accounts().get(CUSTOMER).cash());
            assertEquals(new BigDecimal(funded?"1400.00":"1100.00"),report.companyCash());
            assertEquals(new BigDecimal("300.00"),report.licensing().revenue());
            assertEquals(new BigDecimal(funded?"300.00":"0.00"),report.licensing().cashReceived());
            assertEquals(new BigDecimal(funded?"0.00":"300.00"),report.licensing().customerPayable());
            assertEquals(report.licensing().customerPayable(),report.licensing().receivable());
            assertTrue(report.balanced());
            w.advanceTo(90*DAY-500L);
            assertEquals("NOT_AUTHORIZED",work(w,"ivan","before-office-expiry","SIGN"));
            assertThrows(IllegalArgumentException.class,()->w.scheduleResearchLicense("ivan","COLLECT","p1","former-manager",w.snapshot().timeMillis()));
            cut=w.snapshot();
        }
        byte[] raw=Files.readAllBytes(file);
        try(var w=sector(file,false,customer)) {assertEquals(cut,w.snapshot());}
        assertArrayEquals(raw,Files.readAllBytes(file));
    }

    @Test void newOrdinaryOpeningRestrictsBothHumanAndModelToAppointedManagerAndShowsActualRefusal() throws Exception {
        Path file=dir.resolve("ordinary.journal");
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            meeting(w,"appoint-kate",true);
            assertThrows(IllegalArgumentException.class,()->w.scheduleResearchLicense("frank","SIGN","p1","foreign",w.snapshot().timeMillis()));
            assertFalse(w.autonomyRoleObservation("frank").at("/engineering/researchOperations").has("licensing"));
            var view=w.autonomyRoleObservation("kate");
            assertEquals(CUSTOMER,view.at("/engineering/researchOperations/licensing/payerAccount").asText());
            assertTrue(w.openRoleSession("kate").view().tools().contains(ResearchLicensing.TOOL));
            long observed=w.snapshot().timeMillis();
            assertEquals("COMMERCIAL_APPROVAL_REQUIRED",work(w,"kate","not-ready","SIGN"));
            assertThrows(IllegalStateException.class,()->w.scheduleResearchLicense("kate","SIGN","p1","stale",observed));
            w.beginDecisionSession("controlled");
            var route=new DecisionModel.Route("governance","key-1","deepseek-flash",1,25,1_000_000,2048,1);
            w.openDecisions("open",List.of(new DecisionRun.Request("kate","choose-license","wake",route)));
            var c=w.dispatchLiveDecision("dispatch","choose-license").call();
            var input=JSON.readTree(c.inputText());
            var reply=JSON.createObjectNode().put("tool",ResearchLicensing.TOOL);
            reply.putObject("args").put("action","SIGN").put("projectId","p1");
            reply.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis","核实本人能否签订该项许可").put("reviewAfterMillis",60000);
            w.offerDecisionCallback(new DecisionModel.Reply(c.runId(),c.actionId(),c.attemptId(),c.attemptNo(),c.recoveryGeneration(),
                    reply.toString(), DecisionModel.Usage.unknownCost(1,20,20),"local-fixture"));
            w.drainDecisionCallbacks();
            var bound=w.snapshot().decisions().orElseThrow().actionRef("choose-license");
            assertNotNull(bound);
            w.advanceTo(bound.dueMillis()+1000);
            var feedback=w.autonomyRoleObservation("kate").at("/engineering/deliberation/lastActionResult");
            assertEquals("COMMERCIAL_APPROVAL_REQUIRED",feedback.path("reason").asText(),feedback.toString());
            assertEquals("REJECTED",feedback.path("execution").asText());
        }
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {assertNull(w.researchRun().orElseThrow().state().licenses());}
        try(var old=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(dir.resolve("v10.journal"))) {
            meeting(old,"appoint-kate",true);
            assertFalse(old.openRoleSession("kate").view().tools().contains(ResearchLicensing.TOOL));
            assertFalse(old.autonomyRoleObservation("kate").at("/engineering/researchOperations").has("licensing"));
            assertThrows(IllegalArgumentException.class,()->old.scheduleResearchLicense("kate","SIGN","p1","old",old.snapshot().timeMillis()));
        }
    }
}
