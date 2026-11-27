package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonalOrderHttpTest {
    @TempDir Path dir;
    private static final String PATH = "/api/me/orders";
    private final AtomicReference<String> bootstrap = new AtomicReference<>("stock-order-fixture");

    private void as(String who) throws Exception {
        cookie = null;
        var login = post("/api/session", JSON.createObjectNode().put("bootstrap",bootstrap.get()).toString());
        assertEquals(200,login.statusCode()); remember(login);
        var selected = post("/api/session/person", JSON.createObjectNode().put("personId",who).toString());
        assertEquals(200,selected.statusCode()); remember(selected);
    }

    private JsonNode page() throws Exception {
        var answer = get(PATH);
        assertEquals(200,answer.statusCode());
        return JSON.readTree(answer.body());
    }

    private ObjectNode command(String action,String account,String id) throws Exception {
        return JSON.createObjectNode().put("commandId",id).put("observedMillis",page().required("observedMillis").textValue())
                .put("action",action).put("accountId",account);
    }

    private void apply(WorldWebHost host,ObjectNode cmd) throws Exception {
        var sent=post(PATH,cmd.toString());
        assertEquals(200,sent.statusCode(),sent.body());
        var answer=JSON.readTree(sent.body());
        long scheduled=host.adminSnapshot().position();
        assertEquals(answer,JSON.readTree(post(PATH,cmd.toString()).body()));
        assertEquals(scheduled,host.adminSnapshot().position());
        host.advance(scheduled,answer.required("dueTimeMillis").asLong());
        long executed=host.adminSnapshot().position();
        assertEquals(answer,JSON.readTree(post(PATH,cmd.toString()).body()));
        assertEquals(executed,host.adminSnapshot().position());
    }

    @Test void ordinaryCommandsWithTheSameNameAreNeverAttributedAsManualOrders() throws Exception {
        Path file = dir.resolve("source.journal");
        String prefix = "web-personal-order:8:investor:";
        String childId = "receipt-origin";
        BranchProfile profile;
        String symbol;
        try (var parent = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(file)) {
            parent.advanceTo(34_200_000L);
            symbol = parent.roleView("investor").market().get(0).symbol();
            var cmd = prefix + "parent";
            parent.scheduleOrder(cmd, cmd + ":task", 34_200_001, parent.snapshot().accounts().get("HOUSE-NINA").ownerId(), "HOUSE-NINA", symbol,
                    edu.cufe.auction.model.Side.SELL, edu.cufe.auction.model.OrderType.LIMIT, BigDecimal.TEN, 1_000_000);
            parent.advanceTo(34_200_001);
            parent.createBranch(childId, parent.snapshot().position(), BranchOrigin.Kind.GOD_TAKEOVER, "检查手工命令来源");
            profile = parent.profile();
        }
        var repo = new BranchRepository(dir.resolve("runs"));
        try (var child = repo.open(childId, profile)) {
            var cmd = prefix + "child";
            child.scheduleOrder(cmd, cmd + ":task", 34_200_002, child.snapshot().accounts().get("INV").ownerId(), "INV", symbol,
                    edu.cufe.auction.model.Side.SELL, edu.cufe.auction.model.OrderType.LIMIT, BigDecimal.TEN, 1_000_000);
            child.advanceTo(34_200_002);
            assertTrue(child.snapshot().exchange().orElseThrow().reports().containsKey(cmd + ":task"));
            assertTrue(PersonalOrderDesk.view(child, "investor").required("receipts").isEmpty());
        }
    }

    @Test void twoOwnersTradeAtTheRestingPriceAndCancelOnlyTheRemainingOwnShares() throws Exception {
        Path file=dir.resolve("parent.journal"), keyFile=dir.resolve("keys.txt");
        Files.writeString(keyFile,"sk-order-fixture-001\nsk-order-fixture-002\nsk-order-fixture-003\nsk-order-fixture-004\nsk-order-fixture-005");
        String childId="manual-personal-orders"; String symbol; BigDecimal price;
        BranchProfile profile; BigDecimal sellerCash,buyerCash;
        try(var w=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(file)) {
            w.advanceTo(34_200_000L);
            var seller=w.roleView("investor");
            symbol=seller.holdings().stream().filter(h->h.available()>=4).findFirst().orElseThrow().symbol();
            price=seller.market().stream().filter(q->q.symbol().equals(symbol)).findFirst().orElseThrow().reference().price();
            sellerCash=w.snapshot().accounts().get("INV").cash();buyerCash=w.snapshot().accounts().get("HOUSE-NINA").cash();
            w.createBranch(childId,w.snapshot().position(),BranchOrigin.Kind.GOD_TAKEOVER,"个人交易复核");
            profile=w.profile();
        }
        byte[] parent=Files.readAllBytes(file);
        var repository=new BranchRepository(dir.resolve("runs"));
        try(var routes=SceneRoutes.open(dir);
            var host=new WorldWebHost(()->new WorldWebHost.Opened(repository.open(childId,profile),childId));
            var server=new WorldWebServer(host,KeySlots.load(keyFile),routes,0,bootstrap.get(),null,line->{
                if(line.contains("引导凭据")) bootstrap.set(line.substring(line.indexOf('：')+1).trim());
            })) {
            server.start(); base=server.baseUrl(); cookie=null;
            assertEquals(401,get(PATH).statusCode());
            as("dave"); assertFalse(page().required("enabled").asBoolean());
            as("investor");
            var sell=command("LIMIT","INV","seller-offer").put("symbol",symbol).put("side","SELL")
                    .put("limitPrice",price.toPlainString()).put("quantity","4");
            assertEquals(400,post(PATH,sell.deepCopy().put("accountId","HOUSE-NINA").toString()).statusCode());
            assertEquals(400,post(PATH,sell.deepCopy().put("limitPrice","9.501").toString()).statusCode());
            apply(host,sell);
            assertEquals(400,post(PATH,sell.deepCopy().put("quantity","6").toString()).statusCode());
            var standing=page().at("/openOrders/0");
            String ref=standing.required("orderRef").asText();
            assertEquals("4",standing.required("remaining").asText());
            as("nina"); assertTrue(page().required("openOrders").isEmpty());
            assertEquals(400,post(PATH,command("CANCEL","HOUSE-NINA","forged").put("orderRef",ref).toString()).statusCode());
            apply(host,command("LIMIT","HOUSE-NINA","buyer-bid").put("symbol",symbol).put("side","BUY")
                    .put("limitPrice",price.add(new BigDecimal("0.01")).toPlainString()).put("quantity","2"));
            assertEquals("FILLED",page().at("/receipts/0/status").asText());
            assertFalse(page().toString().contains("seller-offer"));
            as("investor");
            assertEquals("2",page().at("/openOrders/0/remaining").asText());
            apply(host,command("CANCEL","INV","cancel-balance").put("orderRef",ref));
            assertTrue(page().required("openOrders").isEmpty());assertEquals("CANCELLED",page().at("/receipts/0/status").asText());
            var bad=command("LIMIT","INV","too-many").put("symbol",symbol).put("side","SELL")
                    .put("limitPrice",price.toPlainString()).put("quantity","1000000");
            apply(host,bad);
            assertFalse(page().at("/receipts/0/accepted").asBoolean());assertFalse(page().at("/receipts/0/reason").asText().isBlank());
        } finally {cookie=null;}
        try(var w=repository.open(childId,profile)) {
            var snap=w.snapshot();var deals=snap.exchange().orElseThrow().trades();
            assertEquals(1,deals.size());assertEquals(2,deals.get(0).quantity());assertEquals(price,deals.get(0).price());
            BigDecimal amount=price.multiply(BigDecimal.valueOf(2));
            assertEquals(sellerCash.add(amount),snap.accounts().get("INV").cash());
            assertEquals(buyerCash.subtract(amount),snap.accounts().get("HOUSE-NINA").cash());
        }
        assertArrayEquals(parent,Files.readAllBytes(file));
    }
}
