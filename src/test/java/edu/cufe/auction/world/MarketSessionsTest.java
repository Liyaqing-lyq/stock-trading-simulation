package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static edu.cufe.auction.world.MarketSessions.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketSessionsTest {
    @TempDir Path dir;
    private static final BigDecimal BID = new BigDecimal("1.00");

    @Test void sharedCalendarCancelsOnlyAffectedRemaindersAndRestoresAcrossWeekends() throws Exception {
        Path journal = dir.resolve("sessions");
        String other;
        try (var w = ComposedIndustryScenario.openMarketSessionsOperating(journal)) {
            other = w.profile().registry().securities().stream().filter(s -> !SYMBOL.equals(s.symbol())).findFirst().orElseThrow().symbol();
            assertFalse(w.autonomyRoleObservation("nina").at("/engineering/exchangeSessions/sessionOpen").asBoolean());
            assertEquals("SESSION_CLOSED", adminBid(w, "early", other, OPEN-1).reason());
            assertTrue(adminBid(w, "rest", other, OPEN).accepted());
            assertNull(PersonalMarketTest.decide(w, "omar", "foreign", PersonalMarket.SUBMIT, args("HOUSE-NINA", other)));
            var request = PersonalMarketTest.decide(w, "nina", "personal-hydro", PersonalMarket.SUBMIT, args("HOUSE-NINA", SYMBOL));
            w.advanceTo(request.dueMillis());
            assertTrue(exchange(w).reports().get(request.taskId()).accepted());
            var seller = w.snapshot().accounts().get("HY-FOUNDER");
            w.scheduleOrder("sale", "sale-task", w.snapshot().timeMillis()+1, seller.ownerId(), "HY-FOUNDER", SYMBOL, Side.SELL, OrderType.LIMIT, new BigDecimal("20.00"), 2);
            w.scheduleOrder("fill", "fill-task", w.snapshot().timeMillis()+2, "investor", "INV", SYMBOL, Side.BUY, OrderType.LIMIT, new BigDecimal("20.00"), 1);
            w.advanceTo(HALT_AT-1);
            assertEquals(3, exchange(w).liveOrders().size());
            assertEquals(1, exchange(w).trades().size());
            assertTrue(w.snapshot().securities().orElseThrow().reservations().stream().anyMatch(r -> r.accountId().equals("HY-FOUNDER")));
            assertEquals(BID, WorldExchange.frozenCash(exchange(w), "HOUSE-NINA"));
        }
        try (var w = ComposedIndustryScenario.openMarketSessionsOperating(journal)) {
            var cash = w.snapshot().accounts();
            w.advanceTo(HALT_AT);
            assertEquals(1, exchange(w).liveOrders().size());
            assertEquals(other, exchange(w).liveOrders().get(0).symbol());
            assertTrue(exchange(w).reports().values().stream().anyMatch(r -> HALT.equals(r.reason()) && r.cancelledOrderIds().size()==2));
            assertEquals(0, WorldExchange.frozenCash(exchange(w), "HOUSE-NINA").signum());
            for (String account : List.of("HY-FOUNDER","INV","HOUSE-NINA"))
                assertEquals(cash.get(account), w.snapshot().accounts().get(account));
            assertEquals(1, exchange(w).trades().size());
            assertTrue(w.snapshot().securities().orElseThrow().reservations().stream().noneMatch(r -> r.accountId().equals("HY-FOUNDER")));
            assertTrue(exchange(w).orders().stream().anyMatch(o -> o.accountId().equals("HY-FOUNDER")
                    && o.filled()==1 && o.status()==WorldExchange.OrderStatus.CANCELLED));
            var during = PersonalMarketTest.decide(w, "nina", "during", PersonalMarket.SUBMIT, args("HOUSE-NINA", SYMBOL));
            w.advanceTo(during.dueMillis());
            assertEquals(HALT, exchange(w).reports().get(during.taskId()).reason());
            assertTrue(adminBid(w, "resume", SYMBOL, RESUME_AT).accepted());
            assertEquals("SESSION_CLOSED", adminBid(w, "lunch", SYMBOL, BREAK).reason());
            assertEquals(2, exchange(w).liveOrders().size());
            assertTrue(adminBid(w, "afternoon", other, AFTERNOON).accepted());
            assertEquals("SESSION_CLOSED", adminBid(w, "close", other, CLOSE).reason());
            assertTrue(exchange(w).liveOrders().isEmpty());
            assertEquals(0, WorldExchange.frozenCash(exchange(w), "INV").signum());
            assertTrue(adminBid(w, "tuesday", SYMBOL, DAY + OPEN).accepted());
            assertEquals("SESSION_CLOSED", adminBid(w, "weekend", other, 5*DAY + OPEN).reason());
            assertTrue(exchange(w).liveOrders().isEmpty());
            assertTrue(adminBid(w, "monday", other, 7*DAY + OPEN).accepted());
        }
        try (var w = ComposedIndustryScenario.openMarketSessionsOperating(journal)) {
            assertEquals(1, exchange(w).liveOrders().size());
            assertEquals(1, exchange(w).trades().size());
        }
        try (var old = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("legacy"))) {
            assertTrue(old.autonomyRoleObservation("nina").at("/engineering/exchangeSessions").isMissingNode());
            assertTrue(adminBid(old, "unrestricted", other, 5*DAY + OPEN).accepted());
        }
    }

    @Test void institutionalChildNeedsRealMandateAndIsRecheckedAtExecution() throws Exception {
        try (var w = ComposedIndustryScenario.openMarketSessionsOperating(dir.resolve("fund"))) {
            long t = OPEN;
            String doc = "company-governance:eng-a:1";
            read(w,"alice",doc,t);
            t = w.snapshot().timeMillis()+1;
            w.openRoleSession("alice").submitResearch("research", "research-task", t,
                    new WorldRoleSession.ResearchDraft("note", "cufe", "fund-a", SYMBOL, "fund-note", "报价核实",
                            "核对发行人与项目资料。", List.of(new WorldRoleSession.Citation(doc,1))));
            w.advanceTo(t);
            read(w,"bob","fund-note",t+1);
            t = w.snapshot().timeMillis()+1;
            w.openRoleSession("bob").setTarget("target","target-task",t,new WorldRoleSession.TargetDraft("plan",0,
                    "cufe","fund-a","ACC-FUND",SYMBOL,5,BID,2*DAY,"note"));
            w.advanceTo(t);
            w.openRoleSession("carol").reviewTarget("risk","risk-task",++t,new WorldRoleSession.ReviewDraft("plan",1,true,
                    5,2,BID,2*DAY,"核对额度"));
            w.advanceTo(t);
            w.openRoleSession("bob").confirmTarget("confirm","confirm-task",++t,"plan",1,1);
            w.advanceTo(t);
            w.openRoleSession("bob").submitChild("wrong-trader","wrong-trader-task",++t,
                    new WorldRoleSession.ChildDraft("plan",1,1,"wrong",1,BID));
            w.advanceTo(t);
            assertTrue(exchange(w).liveOrders().isEmpty());
            child(w,"first",t+1);
            assertEquals(1, exchange(w).liveOrders().size());
            child(w,"at-halt",HALT_AT);
            assertTrue(exchange(w).liveOrders().isEmpty());
            assertEquals(0, WorldExchange.frozenCash(exchange(w), "ACC-FUND").signum());
            assertTrue(w.roleTradingView(w.openRoleSession("dave")).notices().stream().anyMatch(n -> HALT.equals(n.reason())));
            child(w,"after-resume",RESUME_AT);
            assertEquals(1, exchange(w).liveOrders().size());
        }
    }

    private static void child(WorldRuntime w, String id, long at) throws Exception {
        w.openRoleSession("dave").submitChild("child-"+id,"child-task-"+id,at,new WorldRoleSession.ChildDraft("plan",1,1,id,1,BID));
        w.advanceTo(at);
    }
    private static void read(WorldRuntime w,String who,String doc,long at) throws Exception {
        w.openRoleSession(who).startReading("read-"+who,"read-task-"+who,at,doc,1);
        w.advanceTo(at);
        w.advanceTo(w.roleView(who).readings().get(0).dueAt());
    }
    private static WorldExchange.State exchange(WorldRuntime w) { return w.snapshot().exchange().orElseThrow(); }
    private static com.fasterxml.jackson.databind.node.ObjectNode args(String account,String symbol) {
        return new ObjectMapper().createObjectNode().put("accountId",account).put("symbol",symbol).put("side","BUY").put("quantity",1).put("limitPrice","1.00");
    }
    private static WorldExchange.OrderReport adminBid(WorldRuntime w, String id, String symbol, long at) throws Exception {
        w.scheduleOrder(id,"task-"+id,at,"investor","INV",symbol,Side.BUY,OrderType.LIMIT,BID,1);
        w.advanceTo(at);
        return exchange(w).reports().get("task-"+id);
    }
}
