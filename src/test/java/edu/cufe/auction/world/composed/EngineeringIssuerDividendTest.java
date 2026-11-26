package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EngineeringIssuerDividendTest {
    @TempDir Path dir;

    @Test void externalFiveHundredBetweenRealPersonalAccountsDoesNotProduceReturn() throws Exception {
        Path file=dir.resolve("flow.journal"); WorldRuntime.Snapshot end;
        try(var w=ComposedIndustryScenario.openIssuerDividendOperating(file)) {
            var registry=w.openingRegistry().orElseThrow();
            var people=w.snapshot().accounts().entrySet().stream().filter(e -> registry.owner(e.getValue().ownerId()) != null
                    && registry.owner(e.getValue().ownerId()).kind() == OpeningRegistry.OwnerKind.PERSON && e.getValue().cash().compareTo(new BigDecimal("500.00"))>=0)
                    .sorted(java.util.Map.Entry.comparingByKey()).toList();
            var from=people.get(0);
            var to=people.stream().filter(e->!e.getValue().ownerId().equals(from.getValue().ownerId())).findFirst().orElseThrow();
            long start=w.snapshot().position(),now=w.snapshot().timeMillis();
            BigDecimal amount=new BigDecimal("500.00");
            w.scheduleCapitalFlow("fund-person","fund-person-task",now+10,from.getValue().ownerId(),from.getKey(),to.getKey(),amount,1,"个人资金追加的对照");
            w.advanceTo(now+11);
            assertEquals(from.getValue().cash().subtract(amount),w.snapshot().accounts().get(from.getKey()).cash());
            assertEquals(to.getValue().cash().add(amount),w.snapshot().accounts().get(to.getKey()).cash());
            assertEquals(amount,w.capitalFlows(to.getValue().ownerId()).get(0).amount());
            assertEquals(0,w.performance(to.getValue().ownerId(),start,w.snapshot().position()).returnRate().signum());
            end=w.snapshot();
        }
        try(var w=ComposedIndustryScenario.openIssuerDividendOperating(file)){assertEquals(end,w.snapshot());}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void registeredHoldersArePaidInFullOrRetainEveryClaim(boolean enoughCash) throws Exception {
        Path file = dir.resolve("issuer.journal");
        final String issuer = "eng-a", meeting = "distribution", scheme = "eng-a-pay";
        WorldRuntime.Snapshot end;
        try (var w = ComposedIndustryScenario.openIssuerDividendOperating(file)) {
            var registry = w.openingRegistry().orElseThrow();
            var security = registry.securities().stream().filter(s -> issuer.equals(s.issuerOrganizationId())).findFirst().orElseThrow();
            var spec = w.snapshot().engineering().orElseThrow().config().specOf(issuer);
            var charter = w.boardOfCompany(issuer).orElseThrow().board().charter();
            String chair = charter.seats().stream().filter(s -> charter.chairSeatId().equals(s.seatId())).findFirst().orElseThrow().partyId();
            var voters = charter.seats().stream().map(BoardModel.Seat::partyId).toList();
            long now=w.snapshot().timeMillis();
            var proposal = new DividendModel.SchemeRequest(scheme,security.symbol(),spec.companyOwnerId(),spec.companyAccountId(),
                    new BigDecimal(enoughCash ? "0.01" : "999.00"),now+20000,now+21000,now+22000);
            var wrong = new DividendModel.SchemeRequest("wrong",security.symbol(),"eng-b",spec.companyAccountId(),new BigDecimal("0.01"),
                    now+20000,now+21000,now+22000);
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession(chair).issuerDividend("bad","bad-task",now+100,"PROPOSE",meeting,0,0,0,wrong));
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession("investor").issuerDividend("other","other-task",now+100,"CONVENE",meeting,now+1000,now+2000,now+3000,null));
            String doc=CompanyOpeningDocuments.documentId(issuer);
            for(int i=0;i<voters.size();i++)w.scheduleStartReading("read"+i,"rt"+i,now+5,voters.get(i),doc,1);
            w.advanceTo(now+2000);
            w.openRoleSession(chair).issuerDividend("meet","meet-task",now+3000,"CONVENE",meeting,now+6000,now+7000,now+10500,null);
            for(int i=0;i<voters.size();i++)w.scheduleEngineeringAttend("attend"+i,"at"+i,now+4000,issuer,voters.get(i),meeting,false);
            w.scheduleEngineeringMaterial("material","mt",now+4500,issuer,chair,meeting,"material",doc,1);
            w.openRoleSession(chair).issuerDividend("propose","propose-task",now+5000,"PROPOSE",meeting,0,0,0,proposal);
            w.advanceTo(now+5500);
            assertNotNull(w.snapshot().dividends().orElseThrow().schemes().get(scheme),w.dividendReports().toString());
            for(int i=0;i<voters.size();i++) w.scheduleEngineeringVote("vote"+i,"v"+i,now+9000,issuer,voters.get(i),meeting,BoardModel.Vote.YES,1000);
            w.openRoleSession(chair).issuerDividend("authorize","authorize-task",now+11000,"AUTHORIZE",meeting,0,0,0,null);
            var beforeRegistration = w.snapshot().exchange();
            w.advanceTo(now+20000);
            assertEquals(DividendModel.Phase.REGISTERED, w.snapshot().dividends().orElseThrow().schemes().get(scheme).phase());
            assertEquals(beforeRegistration, w.snapshot().exchange());
            w.advanceTo(now+21000);
            assertEquals(beforeRegistration, w.snapshot().exchange());
            var div = w.snapshot().dividends().orElseThrow();
            assertEquals(DividendModel.Phase.EX_BOOKED,div.schemes().get(scheme).phase(),w.dividendReports().toString());
            var claims = DividendModel.claims(div,scheme);
            assertFalse(claims.isEmpty());
            BigDecimal total = claims.stream().map(DividendModel.Claim::face).reduce(BigDecimal.ZERO,BigDecimal::add);
            assertEquals(total,w.engineeringContext(issuer).orElseThrow().unpaidDividends());
            assertTrue(ComposedCompanyReport.of(w, "mfg-tech").balanced());
            assertFalse(w.boardOfCompany("mfg-tech").orElseThrow().board().meetings().containsKey(meeting));
            var holder = claims.get(0);
            var holderOwner = w.snapshot().accounts().get(holder.accountId()).ownerId();
            var exValue = w.valuation(holderOwner);
            var before=w.snapshot();
            w.advanceTo(now+22000);
            if (enoughCash) {
                assertEquals(before.accounts().get(spec.companyAccountId()).cash().subtract(total),
                        w.snapshot().accounts().get(spec.companyAccountId()).cash());
                for (var claim : claims) {
                    assertEquals(before.accounts().get(claim.accountId()).cash().add(claim.face()),
                            w.snapshot().accounts().get(claim.accountId()).cash());
                }
                assertEquals(0, w.engineeringContext(issuer).orElseThrow().unpaidDividends().signum());
                assertEquals(0, w.valuation(holderOwner).dividendReceivable().signum());
            } else {
                assertEquals(before.accounts(), w.snapshot().accounts());
                assertEquals(div, w.snapshot().dividends().orElseThrow());
                assertEquals(total, w.engineeringContext(issuer).orElseThrow().unpaidDividends());
                assertTrue(w.dividendReports().stream().anyMatch(r -> scheme.equals(r.schemeId())
                        && !r.accepted() && "INSUFFICIENT_CASH".equals(r.reason())));
            }
            assertEquals(exValue.netWorth(), w.valuation(holderOwner).netWorth());
            assertEquals(before.exchange(), w.snapshot().exchange(), "登记、除息和付款不造交易");
            assertTrue(ComposedCompanyReport.of(w,issuer).balanced());
            end=w.snapshot();
        }
        try(var w=ComposedIndustryScenario.openIssuerDividendOperating(file)){assertEquals(end,w.snapshot());}
        if (enoughCash) rejectInconsistentPaymentProof(file);
    }

    private void rejectInconsistentPaymentProof(Path file) throws Exception {
        Path altered = dir.resolve("wrong-cash.journal");
        boolean changed = false;
        try (var source = new WorldJournal(file, RunManifest.ISSUER_DIVIDEND_RUN_ID);
             var target = new WorldJournal(altered, RunManifest.ISSUER_DIVIDEND_RUN_ID)) {
            for (var frame : source.commits()) {
                var events = new java.util.ArrayList<>(frame.events());
                for (int i = 0; i < events.size(); i++) {
                    var event = events.get(i);
                    if ("DIVIDEND_CASH_PAYMENT_V1".equals(event.type())) {
                        var json = new com.fasterxml.jackson.databind.ObjectMapper();
                        var proof = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(event.payload());
                        proof.put("companyAccountId", "another-company-account");
                        events.set(i, new WorldEvent(event.eventId(), event.type(), proof.toString()));
                        changed = true;
                    }
                }
                target.commit(frame.commandId(), frame.commandContent(), frame.result(), events);
            }
        }
        assertTrue(changed);
        assertThrows(java.io.IOException.class, () -> ComposedIndustryScenario.openIssuerDividendOperating(altered));
    }

    @Test void predecessorRetainsOriginalCompanyGovernanceAndCannotOpenNewPolicy() throws Exception {
        Path archive = dir.resolve("previous.journal");
        try (var world = ComposedIndustryScenario.openBankCrisisOperating(archive)) {
            var before = world.snapshot();
            assertTrue(world.engineeringDividendIssuer().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession("investor")
                    .issuerDividend("meet", "task", 10000, "CONVENE", "prior", 11000, 12000, 13000, null));
            assertEquals(before, world.snapshot());
        }
        byte[] before = java.nio.file.Files.readAllBytes(archive);
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openIssuerDividendOperating(archive));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(archive));
    }
}
