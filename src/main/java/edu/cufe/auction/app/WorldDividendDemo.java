package edu.cufe.auction.app;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DividendModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 现金分红世界桥的受信管理脚本演示，提供 prepare / full / replay 三种模式。
 *
 * <p>用法：{@code java ... WorldDividendDemo <工作目录> <prepare|full|replay>}。</p>
 */
public final class WorldDividendDemo {

    private static final String RUN = "dividend-demo";
    private static final long SEED = 7L;
    private static final String SYM = "CUFE1";
    private static final String COMPANY_PARTY = "cufe";
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACC = "CO";
    private static final String INV = "investor";
    private static final String ACC_INV = "INV";

    private static final long T0 = 1_000L;
    private static final long OPENS = T0 + 10_000L;
    private static final long CLOSES = T0 + 20_000L;
    private static final long AUTH = CLOSES + 1L;
    private static final long RECORD = AUTH + 2L;
    private static final long EX = RECORD + 1_000L;
    private static final long PAY = EX + 1_000L;

    private WorldDividendDemo() {
    }

    /**
     * 入口。
     *
     * @param args 工作目录与模式
     * @throws Exception 日志失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String mode = args[1];
        Path file = dir.resolve(RUN + ".journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry(),
                new ManufacturingRun.Config(new ManufacturingModel.Opening(COMPANY_OWNER,
                        COMPANY_ACC, "SUP", "CUS", "OPS"), SYM), charter(),
                new RoleRun.Config(List.of(), List.of()),
                new InformationRun.Config(List.of("alice", "bob", "carol", "dave", "erin")),
                decisions())) {
            if (mode.equals("replay")) {
                long before = runtime.snapshot().position();
                String memo = runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1",
                        "m1");
                System.out.println("replay memo=" + memo);
                report(runtime, "replay");
                System.out.println("positionUnchanged=" + (before == runtime.snapshot().position()));
                return;
            }
            boolean prepared = runtime.snapshot().dividends()
                    .map(state -> state.schemes().containsKey("S1")).orElse(false);
            if (!prepared) {
                prepare(runtime);
                runtime.advanceTo(EX);
            }
            if (mode.equals("prepare")) {
                report(runtime, "prepare");
                return;
            }
            runtime.advanceTo(PAY);
            long position = runtime.snapshot().position();
            String memo = runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
            report(runtime, "full");
            System.out.println("replay memo=" + memo + " positionUnchanged="
                    + (position == runtime.snapshot().position()));
        }
    }

    private static void prepare(WorldRuntime runtime) throws Exception {
        runtime.scheduleBoardMeeting("c-meeting", "t-meeting", T0, "s1", "m1", T0 + 5_000,
                OPENS, CLOSES, List.of("s1", "s2", "s3"), List.of(),
                BoardModel.Topic.DIVIDEND_AUTHORIZATION);
        runtime.scheduleDividendProposal("c-proposal", "t-proposal", T0 + 1, "s1", "m1",
                List.of(), new DividendModel.SchemeRequest("S1", SYM, COMPANY_OWNER, COMPANY_ACC,
                        new BigDecimal("1.00"), RECORD, EX, PAY));
        for (String seat : List.of("s1", "s2", "s3")) {
            runtime.scheduleBoardVote("c-vote-" + seat, "t-vote-" + seat, OPENS + 100, seat,
                    "m1", BoardModel.Vote.YES);
        }
        runtime.advanceTo(CLOSES);
        runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
        runtime.advanceTo(RECORD);
    }

    private static void report(WorldRuntime runtime, String tag) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        DividendModel.State d0 = snapshot.dividends().orElseThrow();
        System.out.println("== " + tag + " ==");
        System.out.println("运行方式为受信管理脚本（scripted）");
        System.out.println("position=" + snapshot.position() + " time=" + snapshot.timeMillis());
        System.out.println("phase=" + DividendModel.scheme(d0, "S1").phase());
        System.out.println("companyCash=" + cash(snapshot, COMPANY_ACC)
                + " investorCash=" + cash(snapshot, ACC_INV));
        System.out.println("companyPayable=" + DividendModel.companyPayable(d0, "S1"));
        if (snapshot.manufacturing().isPresent()) {
            ManufacturingModel.Report report = ManufacturingModel.report(
                    snapshot.manufacturing().orElseThrow().ledger(), snapshot.accounts(),
                    dividendPosition(d0));
            System.out.println("assets=" + report.totalAssets()
                    + " liabilities=" + report.totalLiabilities()
                    + " equity=" + report.equity()
                    + " profit=" + report.cumulativeProfit()
                    + " balanced=" + report.balanced());
        }
        BigDecimal worldCash = BigDecimal.ZERO.setScale(2);
        for (var account : snapshot.accounts().values()) {
            worldCash = worldCash.add(account.cash());
        }
        long shares = 0L;
        if (snapshot.securities().isPresent()) {
            for (SecuritiesLedger.Lot lot : snapshot.securities().orElseThrow().lots()) {
                shares = Math.addExact(shares, lot.remaining());
            }
        }
        System.out.println("worldCash=" + worldCash + " shares=" + shares);
        for (WorldRuntime.DividendReport report : runtime.dividendReports()) {
            System.out.println("report " + report.kind() + " accepted=" + report.accepted()
                    + " reason=" + report.reason());
        }
    }

    private static ManufacturingModel.DividendPosition dividendPosition(DividendModel.State d0) {
        BigDecimal declared = BigDecimal.ZERO.setScale(2);
        BigDecimal unpaid = BigDecimal.ZERO.setScale(2);
        for (DividendModel.Scheme scheme : d0.schemes().values()) {
            if (scheme.phase() != DividendModel.Phase.EX_BOOKED
                    && scheme.phase() != DividendModel.Phase.SETTLED) {
                continue;
            }
            for (DividendModel.Claim claim : DividendModel.claims(d0, scheme.schemeId())) {
                declared = declared.add(claim.face());
                unpaid = unpaid.add(DividendModel.faceUnpaid(claim));
            }
        }
        return new ManufacturingModel.DividendPosition(declared, unpaid);
    }

    private static BigDecimal cash(WorldRuntime.Snapshot snapshot, String accountId) {
        return snapshot.accounts().get(accountId).cash();
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        modules.put("dividend", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1, modules,
                Map.of("fixture", "dividend-demo-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static List<CashLedger.OpeningAccount> openings() {
        return List.of(
                new CashLedger.OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "演示开局"),
                new CashLedger.OpeningAccount(COMPANY_ACC, COMPANY_OWNER, new BigDecimal("1000.00"),
                        "演示开局"),
                new CashLedger.OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "演示开局"),
                new CashLedger.OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "演示开局"),
                new CashLedger.OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "演示开局"));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY_PARTY, List.of(
                new BoardModel.Seat("s1", "alice"), new BoardModel.Seat("s2", "bob"),
                new BoardModel.Seat("s3", "carol"), new BoardModel.Seat("s4", "dave"),
                new BoardModel.Seat("s5", "erin")), "s1", BoardModel.QUORUM);
    }

    private static DecisionModel.Config decisions() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, new BigDecimal("0.00"));
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of("alice", "bob", "carol", "dave", "erin")) {
            caps.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:demo-v1",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 2_000, 2_000, 4, 2, 60_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party(COMPANY_PARTY,
                                OpeningRegistry.PartyKind.ORGANIZATION, "中央财经制造公司"),
                        new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "甲"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "乙"),
                        new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "丙"),
                        new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "丁"),
                        new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "戊"),
                        new OpeningRegistry.Party(INV, OpeningRegistry.PartyKind.PERSON, "外部投资者")),
                List.of(new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("carol",
                                OpeningRegistry.OwnerKind.PERSON, "carol"),
                        new OpeningRegistry.PropertyOwner("dave",
                                OpeningRegistry.OwnerKind.PERSON, "dave"),
                        new OpeningRegistry.PropertyOwner("erin",
                                OpeningRegistry.OwnerKind.PERSON, "erin"),
                        new OpeningRegistry.PropertyOwner(INV,
                                OpeningRegistry.OwnerKind.PERSON, INV),
                        new OpeningRegistry.PropertyOwner(COMPANY_OWNER,
                                OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY_PARTY),
                        new OpeningRegistry.PropertyOwner("supplier",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("customer",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("ops",
                                OpeningRegistry.OwnerKind.AGGREGATE, null)),
                List.of(new OpeningRegistry.Security(SYM, COMPANY_PARTY, 200L,
                        new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT)));
    }
}
