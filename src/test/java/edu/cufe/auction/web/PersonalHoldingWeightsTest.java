package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.OwnerPortfolioAccess;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersonalHoldingWeightsTest {
    @TempDir Path dir;
    private static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");

    private static RoleView.Holding holding(RoleView view, String symbol) {
        return view.holdings().stream().filter(row -> row.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test void ownBalancesAreValuedAtOneCutAndDelegatedRowsOnlyUsePermittedQuantities() throws Exception {
        Path file = dir.resolve("personal.journal");
        Map<String, String> after;
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            var own = w.roleView("nina");
            assertEquals(Map.of("ownerNavPercent","4.4444","issuedSharePercent","2.5000"),
                    PersonalHoldingWeights.holdingWeights(w,own).get(holding(own,"AGED")));
            var delegated = w.roleView("bob");
            var product = PersonalHoldingWeights.holdingWeights(w,delegated);
            assertTrue(product.keySet().stream().allMatch(row -> row.accountId().equals("ACC-FUND")));
            assertEquals("10.0000",product.get(holding(delegated,"AGED")).get("issuedSharePercent"));
            var productWeight = new BigDecimal("100000").divide(w.valuation("fund-a").netWorth(),4,java.math.RoundingMode.HALF_UP);
            assertEquals(productWeight.toPlainString(),product.get(holding(delegated,"AGED")).get("ownerNavPercent"));
            var accounts = new java.util.HashMap<>(w.snapshot().accounts());
            var registry = w.openingRegistry().orElseThrow();
            assertTrue(OwnerPortfolioAccess.canView(accounts,registry,"bob",delegated.accounts(),delegated.accounts().get(0)));
            // 同一产品还有未在本岗位委托中列出的账户时，其整主体净资产包含本岗位无权查看的财产。
            accounts.put("SECOND-PRODUCT-ACCOUNT", new CashLedger.CashAccount("fund-a",new BigDecimal("300.00"),"测试期初"));
            assertFalse(OwnerPortfolioAccess.canView(accounts,registry,"bob",delegated.accounts(),delegated.accounts().get(0)));
            assertTrue(OwnerPortfolioAccess.canView(accounts,registry,"nina",own.accounts(),own.accounts().get(0)));
            var partialProduct = PersonalHoldingWeights.percentages(holding(delegated,"AGED"),registry.security("AGED"),null,null);
            assertNull(partialProduct.get("ownerNavPercent")); assertEquals("10.0000",partialProduct.get("issuedSharePercent"));
            assertTrue(PersonalHoldingWeights.holdingWeights(w,w.roleView("erin")).isEmpty());
            long at = 570*60_000L;
            w.advanceTo(at);
            w.scheduleOrder("freeze","sell",++at,"nina","HOUSE-NINA","AGED",Side.SELL,OrderType.LIMIT,new BigDecimal("10.50"),5);
            w.advanceTo(at);
            var before = w.roleView("nina");
            var reserved = holding(before,"AGED");
            assertEquals(25,reserved.quantity()); assertEquals(5,reserved.frozen()); assertEquals(20,reserved.available());
            assertEquals("2.5000",PersonalHoldingWeights.holdingWeights(w,before).get(reserved).get("issuedSharePercent"));
            w.scheduleOrder("cross","buy",++at,"investor","INV","AGED",Side.BUY,OrderType.LIMIT,new BigDecimal("10.50"),5);
            w.advanceTo(at);
            var changed = w.roleView("nina");
            assertEquals(20,holding(changed,"AGED").quantity());
            after = PersonalHoldingWeights.holdingWeights(w,changed).get(holding(changed,"AGED"));
            assertEquals("2.0000",after.get("issuedSharePercent"));
            assertEquals(new BigDecimal("5637.50"),w.valuation("nina").netWorth());
            assertEquals("3.7251",after.get("ownerNavPercent")); // 20 × 10.50 / 5637.50 × 100
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, dir.resolve("unused.json"))) {
            var current = w.roleView("nina");
            assertEquals(after,PersonalHoldingWeights.holdingWeights(w,current).get(holding(current,"AGED")));
        }
        try (var previous = ComposedIndustryScenario.openOpeningCopiesOperating(dir.resolve("previous.journal"), DRAFT)) {
            assertTrue(PersonalHoldingWeights.holdingWeights(previous,previous.roleView("nina")).isEmpty());
        }
    }

    @Test void signedInPersonReceivesDistinctPercentagesAndNoOtherPersonsBalances() throws Exception {
        Path keys = dir.resolve("dummy-fixture.txt");
        Files.writeString(keys,java.util.stream.IntStream.rangeClosed(1,5)
                .mapToObj(n -> "sk-weights-test-only-000" + n).collect(java.util.stream.Collectors.joining("\n")));
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openOwnerBankValuationOperating(dir.resolve("http.journal"),DRAFT);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        }); var routes = SceneRoutes.open(dir);
             var server = new WorldWebServer(host,KeySlots.load(keys),routes,0,"weight-test",null,s -> {})) {
            server.enableLocalAccess();
            server.start();
            var client = HttpClient.newHttpClient();
            var root = server.baseUrl();
            String admin = sessionCookie(client,root,"local","{}",null);
            String nina = sessionCookie(client,root,"person","{\"personId\":\"nina\"}",admin);
            var get = HttpRequest.newBuilder(URI.create(root+"/api/me/view")).header("Cookie",nina).build();
            var response = client.send(get,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            var body = new ObjectMapper().readTree(response.body());
            var holder = java.util.stream.StreamSupport.stream(body.path("holdings").spliterator(),false)
                    .filter(row -> row.path("symbol").asText().equals("AGED")).findFirst().orElseThrow();
            assertEquals("25",holder.path("quantity").asText());
            assertEquals("4.4444",holder.path("ownerNavPercent").asText());
            assertEquals("2.5000",holder.path("issuedSharePercent").asText());
            assertEquals(1,body.path("portfolios").size());
            assertEquals("nina",body.at("/portfolios/0/ownerId").asText());
            assertEquals("5625.00",body.at("/portfolios/0/netWorth").asText());
        }
    }

    private static String sessionCookie(HttpClient client,String root,String route,String json,String prior) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(root+"/api/session/"+route))
                .header("Origin",root).header("X-World-Request","1").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (prior != null) request.header("Cookie",prior);
        var response = client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
    }

    @Test void aMissingQuoteOrNonpositiveOwnerEquityLeavesOnlyTheShareCountRatio() {
        var row = new RoleView.Holding("mine","S",5,3,2);
        var security = new OpeningRegistry.Security("S","issuer",250,new BigDecimal("10.00"));
        var mark = new ValuationModel.Price("S",new BigDecimal("10.00"),ValuationModel.PriceSource.OPENING_REFERENCE,0,10);
        var missing = new ValuationModel.Price("S",null,ValuationModel.PriceSource.MISSING,-1,-1);
        var balances = new RoleView.BankAmounts("0.00","0.00","0.00","0.00","0.00");
        for (String worth : List.of("0.00","-3.00")) {
            var value = new RoleView.OwnerPortfolio("person",RoleView.AccountKind.PERSONAL,ValuationModel.Status.COMPLETE,
                    worth,"0.00","50.00","0.00",new BigDecimal("50.00").subtract(new BigDecimal(worth)).toPlainString(),balances);
            assertNull(PersonalHoldingWeights.percentages(row,security,mark,value).get("ownerNavPercent"));
            assertEquals("2.0000",PersonalHoldingWeights.percentages(row,security,mark,value).get("issuedSharePercent"));
        }
        var partial = new RoleView.OwnerPortfolio("person",RoleView.AccountKind.PERSONAL,ValuationModel.Status.MISSING_PRICE,
                null,"0.00",null,"0.00","0.00",balances);
        assertNull(PersonalHoldingWeights.percentages(row,security,missing,partial).get("ownerNavPercent"));
        assertNull(PersonalHoldingWeights.percentages(row,security,mark,partial).get("ownerNavPercent"));
        assertEquals("2.0000",PersonalHoldingWeights.percentages(row,security,missing,partial).get("issuedSharePercent"));
        assertNull(PersonalHoldingWeights.percentages(row,null,missing,partial).get("issuedSharePercent"));
    }
}
