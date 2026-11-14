package edu.cufe.auction.world.composed;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.CustodyBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code composed-v3} 托管成交真实开局回归：真实买卖成交、撤单释放冻结、跨 JVM 恢复，
 * 以及分支子世界的托管动作与父子账隔离；同时验证 {@code composed-v1/v2} 仍拒绝 CUSTODY。
 *
 * <p>金额全部来自真实三方账（银行/托管明细/现金）与唯一证券台账；没有影子股账或纯模型成交。</p>
 */
class ComposedCustodyV3Test {

    private static final String CST = ComposedCustodyScenario.CST;
    private static final BigDecimal PRICE = new BigDecimal("10.00");

    @Test
    void custodyBuySellCancelRecoverAndBranchIsolation() throws Exception {
        Path dir = Files.createTempDirectory("composed-v3-custody");
        Path world = dir.resolve("world.log");
        String parentShaBefore;
        String childFile;
        String sealedPrefix;
        BranchProfile profile;
        long cut;

        try (WorldRuntime runtime = ComposedCustodyScenario.open(world)) {
            long now = runtime.snapshot().timeMillis();
            assertCustody(runtime, "investor", "400.00");
            assertCustody(runtime, "eng-a", "600.00");
            edu.cufe.auction.world.WorldRuntime.ComposedFinancialPosition opening =
                    runtime.composedFinancialPosition(ComposedCustodyScenario.BUYER_ACCOUNT);
            assertEquals(0, opening.custodyBeneficial().compareTo(new BigDecimal("600.00")),
                    "开局必须把自有托管受益权计为资产：实际 " + opening.custodyBeneficial());
            assertEquals(0, opening.openingCustodyBeneficial().compareTo(
                    new BigDecimal("600.00")), "开局权益必须含冻结的托管历史禀赋");
            assertEquals(0, opening.assets().compareTo(new BigDecimal("4600.00")),
                    "开局金融资产 = 证券 4000 + 托管受益权 600，实际 " + opening.assets());
            assertEquals(0, opening.equity().compareTo(new BigDecimal("4600.00")),
                    "组合派生权益 = 证券 4000 + 托管 600，实际 " + opening.equity());
            assertEquals(0, runtime.engineeringStatement("eng-a").orElseThrow().equity()
                    .compareTo(new BigDecimal("9600.00")),
                    "工程总权益 = 开局现金 5000 + 金融权益 4600");
            assertTrue(runtime.engineeringStatement("eng-a").orElseThrow().balanced(),
                    "开局工程报表必须 balanced");
            assertTrue(runtime.manufacturingStatement().orElseThrow().balanced(),
                    "开局制造报表必须 balanced");
            // 托管机构自身：池存款 1000 与客户负债 1000 等额抵销，不得把池算成净财富。
            edu.cufe.auction.world.WorldRuntime.ComposedFinancialPosition custodian =
                    runtime.composedFinancialPosition("CST-CASH");
            assertEquals(0, custodian.custodyBeneficial().compareTo(
                    BigDecimal.ZERO.setScale(2)), "托管机构不是自己的客户");
            assertEquals(0, custodian.custodyPoolAssets().compareTo(new BigDecimal("1000.00")),
                    "托管机构必须列示真实池存款：实际 " + custodian.custodyPoolAssets());
            assertEquals(0, custodian.custodyClientLiabilities().compareTo(
                    new BigDecimal("1000.00")), "必须列示等额客户负债");
            assertEquals(0, custodian.assets().compareTo(custodian.liabilities()),
                    "池资产与客户负债必须等额抵销，净财富不得增加");
            assertEquals(0, custodian.equity().compareTo(BigDecimal.ZERO.setScale(2)),
                    "托管机构开局权益不得来自客户池");

            // 真实成交：investor 卖出 50 股 CUFE-A，eng-a 以托管桶买入 50 股，被动价 10。
            runtime.scheduleOrder("c-sell", "t-sell", now + 60_000L,
                    ComposedCustodyScenario.SELLER_OWNER, ComposedCustodyScenario.SELLER_ACCOUNT,
                    ComposedCustodyScenario.SYMBOL, Side.SELL, OrderType.LIMIT, PRICE, 50L,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_SELLER);
            runtime.scheduleOrder("c-buy", "t-buy", now + 120_000L,
                    ComposedCustodyScenario.BUYER_OWNER, ComposedCustodyScenario.BUYER_ACCOUNT,
                    ComposedCustodyScenario.SYMBOL, Side.BUY, OrderType.LIMIT, PRICE, 50L,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER);
            runtime.advanceTo(now + 120_000L);

            WorldExchange.State exchange = runtime.snapshot().exchange().orElseThrow();
            assertEquals(1, exchange.trades().size(), "必须发生一笔真实成交");
            assertEquals(0, exchange.trades().get(0).price().compareTo(PRICE));
            // 资金归属：买方托管明细 −500，卖方托管明细 +500；双方现金账户不动。
            assertCustody(runtime, "investor", "900.00");
            assertCustody(runtime, "eng-a", "100.00");
            assertEquals(0, runtime.snapshot().accounts().get("INV").cash().compareTo(
                    BigDecimal.ZERO.setScale(2)), "卖方现金不得被影子记账");
            assertEquals(0, runtime.snapshot().accounts().get("ENG-A").cash().compareTo(
                    new BigDecimal("5000.00")), "买方现金不得被影子记账");
            assertEquals(550L, lotRemaining(runtime, ComposedCustodyScenario.SELLER_ACCOUNT),
                    "卖方持仓必须真实减少 50（开局 600 → 550）");
            edu.cufe.auction.world.WorldRuntime.ComposedFinancialPosition traded =
                    runtime.composedFinancialPosition(ComposedCustodyScenario.BUYER_ACCOUNT);
            assertEquals(0, traded.custodyBeneficial().compareTo(new BigDecimal("100.00")),
                    "成交后自有托管受益权必须是 100");
            assertEquals(0, traded.securitiesCost().compareTo(new BigDecimal("4500.00")),
                    "买入后证券成本 = 4000 + 500");
            assertEquals(0, traded.assets().compareTo(opening.assets()),
                    "无市价变化、无费用时净资产不得因托管买入而变化：期初 "
                            + opening.assets() + "，成交后 " + traded.assets());
            assertEquals(0, traded.equity().compareTo(opening.equity()),
                    "成交不得被计成注资或损益");
            assertTrue(runtime.engineeringStatement("eng-a").orElseThrow().balanced(),
                    "成交后工程报表必须 balanced 且包含托管资产");
            assertTrue(runtime.manufacturingStatement().orElseThrow().balanced(),
                    "成交后制造报表必须 balanced");

            // 撤单：托管桶挂单冻结 30（3 × 10），撤单后可用额释放、明细不变。
            long next = now + 180_000L;
            runtime.scheduleOrder("c-rest", "t-rest", next,
                    ComposedCustodyScenario.BUYER_OWNER, ComposedCustodyScenario.BUYER_ACCOUNT,
                    ComposedCustodyScenario.SYMBOL, Side.BUY, OrderType.LIMIT,
                    new BigDecimal("9.00"), 3L,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER);
            runtime.advanceTo(next);
            BigDecimal freeWithRest = available(runtime,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER);
            assertEquals(0, freeWithRest.compareTo(new BigDecimal("73.00")),
                    "挂单冻结后可用额必须是 100 − 27 = 73，实际 " + freeWithRest);
            WorldExchange.Order resting = runtime.snapshot().exchange().orElseThrow().orders()
                    .stream().filter(order -> order.accountId()
                            .equals(ComposedCustodyScenario.BUYER_ACCOUNT))
                    .filter(order -> order.status() == WorldExchange.OrderStatus.ACTIVE)
                    .findFirst().orElseThrow();
            runtime.scheduleCancelOrder("c-cancel", "t-cancel", next + 60_000L,
                    ComposedCustodyScenario.BUYER_OWNER, resting.orderId());
            runtime.advanceTo(next + 60_000L);
            assertEquals(WorldExchange.OrderStatus.CANCELLED,
                    runtime.snapshot().exchange().orElseThrow().orders().stream()
                            .filter(order -> order.orderId() == resting.orderId())
                            .findFirst().orElseThrow().status(), "挂单必须被真实撤销");
            assertEquals(0, available(runtime,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER)
                    .compareTo(new BigDecimal("100.00")), "撤单必须释放全部冻结");
            assertCustody(runtime, "eng-a", "100.00");
            assertCustody(runtime, "investor", "900.00");

            cut = runtime.snapshot().position();
            WorldRuntime.BranchReceipt receipt = runtime.createBranch("child-custody", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "托管成交父子资金隔离");
            childFile = receipt.childFile().toString();
            sealedPrefix = receipt.sealedPrefix().toString();
            assertTrue(Files.isRegularFile(receipt.childFile()), "子档必须已发布");
            profile = runtime.profile();
            parentShaBefore = receipt.parentPrefixSha256();
        }
        String worldShaAtCut = sha(world);

        // 子世界：继承切点托管明细后，再用托管桶真实成交 5 股（双方各 ±50），父档不得被改写。
        try (WorldRuntime child = WorldRuntime.openBranch(Path.of(childFile),
                id -> Path.of(sealedPrefix), profile)) {
            assertCustody(child, "investor", "900.00");
            assertCustody(child, "eng-a", "100.00");
            long now = child.snapshot().timeMillis();
            child.scheduleOrder("cc-sell", "ct-sell", now + 60_000L,
                    ComposedCustodyScenario.SELLER_OWNER, ComposedCustodyScenario.SELLER_ACCOUNT,
                    ComposedCustodyScenario.SYMBOL, Side.SELL, OrderType.LIMIT, PRICE, 5L,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_SELLER);
            child.scheduleOrder("cc-buy", "ct-buy", now + 120_000L,
                    ComposedCustodyScenario.BUYER_OWNER, ComposedCustodyScenario.BUYER_ACCOUNT,
                    ComposedCustodyScenario.SYMBOL, Side.BUY, OrderType.LIMIT, PRICE, 5L,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER);
            child.advanceTo(now + 120_000L);
            assertEquals(2, child.snapshot().exchange().orElseThrow().trades().size(),
                    "子世界继承切点成交后必须再发生一笔自己的真实托管成交");
            assertCustody(child, "investor", "950.00");
            assertCustody(child, "eng-a", "50.00");
            assertTrue(child.engineeringStatement("eng-a").orElseThrow().balanced(),
                    "子世界成交后工程报表必须 balanced");
            assertEquals(0, child.composedFinancialPosition(
                    ComposedCustodyScenario.BUYER_ACCOUNT).assets()
                    .compareTo(new BigDecimal("4600.00")),
                    "子世界成交后金融资产仍为 4600（托管 50 + 证券 4550）");
        }
        assertNotEquals(worldShaAtCut, sha(Path.of(childFile)), "子档动作必须只改子档字节");
        assertEquals(worldShaAtCut, sha(world), "子档任何动作都不得改写父档字节");
        assertTrue(!parentShaBefore.isBlank());

        // 父档只读重开：切点托管明细与摘要不被子档污染。
        try (WorldRuntime parent = ComposedCustodyScenario.open(world)) {
            assertCustody(parent, "investor", "900.00");
            assertCustody(parent, "eng-a", "100.00");
            assertEquals(cut, parent.snapshot().position(), "父档位置不得被分支推进");
            assertTrue(parent.engineeringStatement("eng-a").orElseThrow().balanced(),
                    "重开父档工程报表必须 balanced");
            assertEquals(0, parent.composedFinancialPosition(
                    ComposedCustodyScenario.BUYER_ACCOUNT).equity()
                    .compareTo(new BigDecimal("4600.00")),
                    "重开后组合派生权益不得重算或残差补差");
        }
    }

    @Test
    void composedV1AndV2KeepTheirExactFrozenModuleSets() {
        Map<String, Integer> base = new TreeMap<>(
                edu.cufe.auction.web.WorldWebScenario.groupsManifest().moduleVersions());
        Map<String, Integer> v1Modules = new TreeMap<>(base);
        v1Modules.put("banking", 1);
        v1Modules.put("custody", 1);
        v1Modules.put("funding", 1);
        v1Modules.put("company-governance", 1);
        Map<String, Integer> v2Modules = new TreeMap<>(v1Modules);
        v2Modules.put("command-intent", 1);

        RunManifest v1 = new RunManifest("composed-v1-check", 11L,
                RunManifest.RULES_COMPOSED_BANK_WORLD_V1, v1Modules,
                Map.of("profile", "composed-bank-v1"), RunManifest.LIVE_DECISIONS_V1);
        RunManifest v2 = new RunManifest("composed-v2-check", 11L,
                RunManifest.RULES_COMPOSED_BANK_WORLD_V2, v2Modules,
                Map.of("profile", "composed-bank-v2"), RunManifest.LIVE_DECISIONS_V1);
        assertEquals(v1Modules, v1.moduleVersions(), "composed-v1 26 模块集合必须逐字不变");
        assertEquals(v2Modules, v2.moduleVersions(), "composed-v2 27 模块集合必须逐字不变");
        assertFalse(v1.hasCustodyFill());
        assertFalse(v2.hasCustodyFill());
        assertFalse(v1.hasDefaultPeriodicGovernance(), "v1 不补默认周期治理");
        assertTrue(v2.hasDefaultPeriodicGovernance(), "v2 保持原意");
    }

    @Test
    void composedV1AndV2StillRejectCustodyButV3Accepts() throws Exception {
        // 精确模块集合：v2 = v3 − custody-fill；v1/v2 的 hasCustody() 为真也不等于成交能力。
        RunManifest v2 = ComposedCustodyScenario.manifest(RunManifest.RULES_COMPOSED_BANK_WORLD_V2);
        RunManifest v3 = ComposedCustodyScenario.manifest(RunManifest.RULES_COMPOSED_BANK_WORLD_V3);
        Map<String, Integer> expectedV3 = new TreeMap<>(v2.moduleVersions());
        expectedV3.put("custody-fill", 1);
        assertEquals(expectedV3, v3.moduleVersions(), "v3 必须恰好是 v2 集合 + custody-fill:1");
        assertTrue(v2.hasCustody(), "v1/v2 仍有托管明细模块");
        assertFalse(v2.hasCustodyFill(), "v1/v2 没有 custody-fill 能力");
        assertTrue(v3.hasCustodyFill(), "v3 必须显式启用 custody-fill");

        Path dir = Files.createTempDirectory("composed-v2-reject");
        try (WorldRuntime v2world = ComposedCustodyScenario.open(dir.resolve("world.log"),
                RunManifest.RULES_COMPOSED_BANK_WORLD_V2)) {
            long now = v2world.snapshot().timeMillis();
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> v2world.scheduleOrder("c-v2", "t-v2", now + 60_000L,
                            ComposedCustodyScenario.BUYER_OWNER,
                            ComposedCustodyScenario.BUYER_ACCOUNT,
                            ComposedCustodyScenario.SYMBOL, Side.BUY, OrderType.LIMIT, PRICE, 1L,
                            "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER));
            assertTrue(rejected.getMessage().contains("B7b"), "v2 必须按旧契约拒绝托管桶："
                    + rejected.getMessage());
        }
        Path dir3 = Files.createTempDirectory("composed-v3-accept");
        try (WorldRuntime v3world = ComposedCustodyScenario.open(dir3.resolve("world.log"))) {
            long now = v3world.snapshot().timeMillis();
            v3world.scheduleOrder("c-v3", "t-v3", now + 60_000L,
                    ComposedCustodyScenario.BUYER_OWNER, ComposedCustodyScenario.BUYER_ACCOUNT,
                    ComposedCustodyScenario.SYMBOL, Side.BUY, OrderType.LIMIT, PRICE, 1L,
                    "CUSTODY|" + CST + "|" + ComposedCustodyScenario.CLIENT_BUYER);
        }
    }

    private static BigDecimal available(WorldRuntime runtime, String token) throws Exception {
        BankRun.Decoded decoded = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts());
        return WorldExchange.freeIn(runtime.snapshot().exchange().orElseThrow(),
                runtime.snapshot().accounts(), decoded.state(),
                edu.cufe.auction.world.bank.Fund.parse(token), decoded.book());
    }

    private static void assertCustody(WorldRuntime runtime, String client, String expected)
            throws Exception {
        BankRun bank = runtime.bankOf().orElseThrow();
        CustodyBook book = bank.decode(runtime.snapshot().accounts()).book();
        BigDecimal actual = book.detailOf(CST, client);
        assertEquals(0, actual.compareTo(new BigDecimal(expected).setScale(2,
                RoundingMode.UNNECESSARY)), "托管明细 cst/" + client + " 期望 "
                + expected + "，实际 " + actual);
    }

    private static long lotRemaining(WorldRuntime runtime, String accountId) throws Exception {
        long total = 0L;
        for (edu.cufe.auction.world.SecuritiesLedger.Lot lot
                : runtime.snapshot().securities().orElseThrow().lots()) {
            if (lot.accountId().equals(accountId)
                    && lot.symbol().equals(ComposedCustodyScenario.SYMBOL)) {
                total += lot.remaining();
            }
        }
        return total;
    }

    private static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        return java.util.HexFormat.of().formatHex(digest.digest(bytes));
    }
}
