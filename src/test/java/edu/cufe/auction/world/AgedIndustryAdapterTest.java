package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 陈酿适配层的实质断言：严格字段与身份夹带拒绝、成熟期与货架期边界、寄售预收/售出/回款/关期
 * 的真实现金链、账面恒等式与经理投影的私源隔离。
 */
class AgedIndustryAdapterTest {

    private static final long PERIOD = AgedIndustryConfig.PERIOD_MILLIS;
    private static final String CO = AgedIndustryConfig.COMPANY_ACCOUNT;
    private static final String CHANNEL = AgedIndustryConfig.CHANNEL_ACCOUNT;
    private static final String TERMINAL = AgedIndustryConfig.TERMINAL_ACCOUNT;
    private static final String SUPPLIER = AgedIndustryConfig.SUPPLIER_ACCOUNT;
    private static final String CONVERSION = AgedIndustryConfig.CONVERSION_ACCOUNT;
    private static final String FIXED = AgedIndustryConfig.FIXED_ACCOUNT;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一条命令在真实现金上的执行器：接受才提交候选。 */
    private static final class World {

        private AgedCompanyRun run;
        private FundState cash;

        private World() {
            this.cash = PureCompanyTestSupport.funds(AgedIndustryConfig.accounts());
            this.run = AgedCompanyRun.open(AgedIndustryConfig.runConfig(), cash);
        }

        private AgedOperations.Outcome apply(AgedOperations.Action action,
                                             Map<String, String> arguments, long now) {
            AgedOperations.Outcome outcome = AgedOperations.apply(run, cash, now,
                    new AgedOperations.Request(action, arguments));
            if (outcome.accepted()) {
                run = run.withLedger(outcome.state());
                cash = outcome.funds();
            }
            return outcome;
        }

        private BigDecimal at(String accountId) {
            return cash.cash(accountId);
        }

        /** 到货并投产 100 单位，含两期陈酿等待。 */
        private void buyAndProduce() {
            assertTrue(apply(AgedOperations.Action.ORDER_RAW,
                    Map.of("orderId", "R-1", "units", "100"), 0L).accepted());
            assertTrue(apply(AgedOperations.Action.RECEIVE_RAW,
                    Map.of("orderId", "R-1"), PERIOD).accepted());
            assertTrue(apply(AgedOperations.Action.PRODUCE,
                    Map.of("batchId", "B-1", "units", "100"), PERIOD).accepted());
        }
    }

    @Test
    void requestRejectsIdentitySmugglingExtraFieldsAndFloatMoney() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("shipmentId", "S-1");
        fields.put("channelId", AgedIndustryConfig.CHANNEL);
        fields.put("units", "60");
        Map<String, String> smuggled = new LinkedHashMap<>(fields);
        smuggled.put("actor", "aged-manager");
        assertThrows(IllegalArgumentException.class, () -> new AgedOperations.Request(
                AgedOperations.Action.SHIP, smuggled));
        Map<String, String> owner = new LinkedHashMap<>(fields);
        owner.put("companyPartyId", "another-company");
        assertThrows(IllegalArgumentException.class, () -> new AgedOperations.Request(
                AgedOperations.Action.SHIP, owner));
        for (String bad : new String[]{"1.5", "1e3", "-1", " 60", "9223372036854775808"}) {
            Map<String, String> malformed = new LinkedHashMap<>(fields);
            malformed.put("units", bad);
            assertThrows(IllegalArgumentException.class, () -> new AgedOperations.Request(
                    AgedOperations.Action.SHIP, malformed), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new AgedOperations.Request(
                AgedOperations.Action.CLOSE_PERIOD, Map.of("period", "0")));
        assertThrows(UnsupportedOperationException.class,
                () -> new AgedOperations.Request(AgedOperations.Action.PAY_FIXED,
                        Map.of("amountCents", "100")).arguments().put("actor", "x"));
    }

    @Test
    void strictDraftParsingRefusesSystemToolsAndNumericJson() throws Exception {
        AgedOperations.Request request = AgedDecisionTools.request(new AgedDecisionTools.Draft(
                "COLLECT", json("{\"channelId\":\"AGED-CH\",\"amountCents\":\"192000\"}")));
        assertEquals(AgedOperations.Action.COLLECT, request.action());
        assertEquals(new BigDecimal("1920.00"), request.cents("amountCents"));

        assertThrows(IllegalArgumentException.class, () -> AgedDecisionTools.request(
                new AgedDecisionTools.Draft("SELL_THROUGH",
                        json("{\"saleId\":\"S\",\"channelId\":\"AGED-CH\",\"units\":\"1\"}"))));
        assertThrows(IllegalArgumentException.class, () -> AgedDecisionTools.request(
                new AgedDecisionTools.Draft("CLOSE_PERIOD", json("{}"))));
        assertThrows(IllegalArgumentException.class, () -> AgedDecisionTools.request(
                new AgedDecisionTools.Draft("SHIP",
                        json("{\"shipmentId\":\"S\",\"channelId\":\"AGED-CH\",\"units\":60}"))));
        assertThrows(IllegalArgumentException.class, () -> AgedDecisionTools.request(
                new AgedDecisionTools.Draft("SHIP", json("[]"))));
    }

    @Test
    void fullChainKeepsInventoryAndCashIdentities() {
        World world = new World();
        assertEquals(money("60000.00"), world.at(CO).add(world.at(CHANNEL))
                .add(world.at(TERMINAL)).add(world.at(SUPPLIER)).add(world.at(CONVERSION))
                .add(world.at(FIXED)));

        world.buyAndProduce();
        assertEquals(money("18500.00"), world.at(CO));
        assertEquals(money("1000.00"), world.at(SUPPLIER));
        assertEquals(money("500.00"), world.at(CONVERSION));
        assertEquals(money("1500.00"), AgedInventoryModel.books(world.run.ledger(),
                world.cash.accounts()).inventoryCost());

        assertTrue(world.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(), PERIOD).accepted());
        assertTrue(world.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(), 2L * PERIOD).accepted());
        assertEquals(money("18300.00"), world.at(CO));
        assertEquals(money("200.00"), world.at(FIXED));

        // 未成熟不得发货：批次成熟期是投产期 + 2。
        AgedOperations.Outcome tooEarly = world.apply(AgedOperations.Action.SHIP,
                Map.of("shipmentId", "S-0", "channelId", AgedIndustryConfig.CHANNEL,
                        "units", "60"), 2L * PERIOD);
        assertFalse(tooEarly.accepted());
        assertEquals(AgedInventoryModel.NO_CHANNEL_STOCK, tooEarly.reason());
        assertEquals(money("18300.00"), world.at(CO));

        assertTrue(world.apply(AgedOperations.Action.SHIP,
                Map.of("shipmentId", "S-1", "channelId", AgedIndustryConfig.CHANNEL,
                        "units", "60"), 3L * PERIOD).accepted());
        assertEquals(money("18780.00"), world.at(CO));
        assertEquals(money("19520.00"), world.at(CHANNEL));
        assertEquals(money("480.00"), world.run.ledger().channelLedgers().get(0)
                .contractLiability());

        assertTrue(world.apply(AgedOperations.Action.SELL_THROUGH,
                Map.of("saleId", "SL-1", "channelId", AgedIndustryConfig.CHANNEL, "units", "60"),
                3L * PERIOD).accepted());
        assertEquals(money("17600.00"), world.at(TERMINAL));
        assertEquals(money("21920.00"), world.at(CHANNEL));
        // 终端付给渠道，公司现金不动；公司这边只增加应收。
        assertEquals(money("18780.00"), world.at(CO));
        assertEquals(money("1920.00"), world.run.ledger().channelLedgers().get(0).receivable());

        assertTrue(world.apply(AgedOperations.Action.COLLECT,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "amountCents", "192000"),
                3L * PERIOD).accepted());
        assertEquals(money("20700.00"), world.at(CO));
        assertEquals(money("20000.00"), world.at(CHANNEL));

        assertTrue(world.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(), 3L * PERIOD).accepted());
        long due = AgedOperations.dueAt(world.run.ledger(), AgedOperations.Action.CLOSE_PERIOD);
        assertEquals(4L * PERIOD, due);
        assertTrue(world.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(), due).accepted());

        assertEquals(money("20500.00"), world.at(CO));
        assertEquals(money("400.00"), world.at(FIXED));
        AgedInventoryModel.Books books = AgedInventoryModel.books(world.run.ledger(),
                world.cash.accounts());
        assertEquals(money("600.00"), books.inventoryCost());
        assertEquals(money("0.00"), books.receivable());
        assertEquals(money("0.00"), books.liabilities());
        assertEquals(money("21100.00"), books.assets());
        assertEquals(money("21100.00"), books.equity());
        assertEquals(money("2400.00"), books.revenue());
        assertEquals(money("900.00"), books.cogs());
        assertEquals(40L, books.matureWarehouseUnits());
        assertEquals(60L, books.soldUnits());
        assertNull(AgedInventoryModel.validate(world.run.ledger(), world.cash.accounts()));
        assertEquals(money("60000.00"), world.at(CO).add(world.at(CHANNEL))
                .add(world.at(TERMINAL)).add(world.at(SUPPLIER)).add(world.at(CONVERSION))
                .add(world.at(FIXED)));
    }

    @Test
    void priceFloorAndWarehouseExpiryAreEnforcedByThePureCore() {
        World world = new World();
        AgedOperations.Outcome belowFloor = world.apply(AgedOperations.Action.SET_PRICE,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "priceCents", "2000"), 0L);
        assertFalse(belowFloor.accepted());
        assertEquals(AgedInventoryModel.PRICE_BELOW_FLOOR, belowFloor.reason());
        assertEquals(money("40.00"), world.run.ledger().currentPrices()
                .get(AgedIndustryConfig.CHANNEL));

        assertTrue(world.apply(AgedOperations.Action.SET_PRICE,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "priceCents", "3500"), 0L).accepted());
        assertEquals(money("35.00"), world.run.ledger().currentPrices()
                .get(AgedIndustryConfig.CHANNEL));

        assertTrue(world.apply(AgedOperations.Action.ORDER_RAW,
                Map.of("orderId", "R-9", "units", "10"), 0L).accepted());
        assertTrue(world.apply(AgedOperations.Action.RECEIVE_RAW,
                Map.of("orderId", "R-9"), PERIOD).accepted());
        assertTrue(world.apply(AgedOperations.Action.PRODUCE,
                Map.of("batchId", "B-9", "units", "10"), PERIOD).accepted());
        for (long period = 0L; period <= 6L; period++) {
            assertTrue(world.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(),
                    (period + 1) * PERIOD).accepted(), "close " + period);
        }
        AgedInventoryModel.Spoilage expired = world.run.ledger().spoilage()
                .get(world.run.ledger().spoilage().size() - 1);
        assertEquals("EXPIRED", expired.reason());
        assertEquals(10L, expired.units());
        assertEquals(0L, world.run.ledger().batches().get(0).remaining());
        assertNull(AgedInventoryModel.validate(world.run.ledger(), world.cash.accounts()));
    }

    @Test
    void observationHidesFrozenDemandChannelAccountsAndTerminalCash() throws Exception {
        World world = new World();
        JsonNode view = JSON.readTree(AgedDecisionTools.observation(world.run, world.cash, 0L,
                List.of(), null).toString());
        assertFalse(view.has("demand"));
        assertFalse(view.toString().contains("999"));
        assertFalse(view.toString().contains("aged-channel-cash"));
        assertFalse(view.toString().contains("aged-terminal-cash"));
        assertFalse(view.has("openingTotalCash"));
        assertFalse(view.has("totalCash"));
        assertFalse(view.get("channels").get(0).has("accountId"));
        assertEquals("30.00", view.get("channels").get(0).get("priceFloor").asText());
        assertEquals("10.00", view.get("rawUnitCost").asText());
        assertEquals(2, view.get("agingPeriods").asInt());
        assertTrue(view.get("actionFields").has("SHIP"));
        assertFalse(view.get("actionFields").has("SELL_THROUGH"));
        assertFalse(view.get("actionFields").has("CLOSE_PERIOD"));
        assertTrue(view.get("rules").asText().contains("整数分"));
        assertEquals("20000.00", view.get("cash").asText());
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }
}
