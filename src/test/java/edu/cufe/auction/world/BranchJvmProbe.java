package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 跨 JVM 分支续写探针（不是单元测试）：三个独立 java 进程依次创建、续写、复验同一条分支子档。
 *
 * <pre>
 * java ... BranchJvmProbe create   &lt;dir&gt;   # 建父运行 + 创建分支
 * java ... BranchJvmProbe advance  &lt;dir&gt;   # 另一个进程打开子档并推进
 * java ... BranchJvmProbe verify   &lt;dir&gt;   # 第三个进程只读复验并再次推进
 * </pre>
 *
 * <p>每个子命令都是独立进程，因此"跨 JVM 续写"不是同进程复用对象。</p>
 */
public final class BranchJvmProbe {

    private static final String PARENT = "jvm-parent";
    private static final String CHILD = "jvm-child";
    private static final long SEED = 17L;
    private static final String SYM = "CUFE1";
    private static final String COMPANY = "cufe";
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACC = "CO";
    private static final String INV = "investor";
    private static final String ACC_INV = "INV";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";
    private static final long T0 = 1_000L;

    private BranchJvmProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <create|advance|verify> <目录>}
     * @throws Exception 打开或写入失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：BranchJvmProbe <create|advance|verify> <dir>");
        }
        Path dir = Path.of(args[1]).toAbsolutePath();
        switch (args[0]) {
            case "create" -> create(dir);
            case "advance" -> advance(dir);
            case "verify" -> verify(dir);
            default -> throw new IllegalArgumentException("未知子命令：" + args[0]);
        }
    }

    private static void create(Path dir) throws IOException {
        Path parentFile = dir.resolve("parent.journal");
        try (WorldRuntime parent = new WorldRuntime(parentFile, manifest(), openings(), registry(),
                MFG, charter(), roles(), information(), decisions())) {
            parent.scheduleCapitalFlow("c-flow", "t-flow", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            long cut = parent.snapshot().position();
            WorldRuntime.BranchReceipt receipt = parent.createBranch(CHILD, cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "跨 JVM 探针");
            System.out.println("CREATE parentRun=" + PARENT + " cut=" + cut
                    + " child=" + receipt.childRunId() + " firstPosition=" + receipt.firstPosition()
                    + " prefixSha=" + receipt.parentPrefixSha256());
            System.out.println("CREATE parentFlows=" + parent.capitalFlows(INV).size()
                    + " parentPosition=" + parent.snapshot().position()
                    + " childFile=" + receipt.childFile());
        }
    }

    private static void advance(Path dir) throws IOException {
        Path parentFile = dir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt = receiptOf(parentFile);
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            BigDecimal before = child.snapshot().accounts().get(ACC_INV).cash();
            child.advanceTo(T0);
            System.out.println("ADVANCE childRun="
                    + child.branchOrigin().map(BranchOrigin::childRunId).orElse("-")
                    + " position=" + child.snapshot().position()
                    + " cashBefore=" + before + " cashAfter="
                    + child.snapshot().accounts().get(ACC_INV).cash()
                    + " flows=" + child.capitalFlows(INV).size());
        }
    }

    private static void verify(Path dir) throws IOException {
        Path parentFile = dir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt = receiptOf(parentFile);
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            long position = child.snapshot().position();
            System.out.println("VERIFY position=" + position
                    + " runId=" + child.branchOrigin().orElseThrow().childRunId()
                    + " parent=" + child.branchOrigin().orElseThrow().parentRunId()
                    + " flows=" + child.capitalFlows(INV).size()
                    + " pending=" + child.snapshot().pending().size());
            List<WorldRuntime.BranchHistoryEntry> entries =
                    child.branchHistoryIndex(0, WorldRuntime.MAX_HISTORY_PAGE);
            System.out.println("VERIFY historyEntries=" + entries.size()
                    + " firstSource=" + entries.get(0).source()
                    + " lastSource=" + entries.get(entries.size() - 1).source());
            child.scheduleCapitalFlow("c-probe2", "t-probe2", T0 + 500, "customer", "CUS",
                    ACC_INV, new BigDecimal("25.00"), 1L, "第三进程续写");
            child.advanceTo(T0 + 500);
            System.out.println("VERIFY advancedTo=" + child.snapshot().position()
                    + " cashInv=" + child.snapshot().accounts().get(ACC_INV).cash());
        }
    }

    static WorldRuntime.BranchReceipt receipt(Path parentFile, String childId) throws IOException {
        Path runsDir = parentFile.getParent().resolve("runs");
        Path childFile = runsDir.resolve(childId + ".journal");
        Path sealFile = runsDir.resolve(childId + ".prefix.journal");
        WorldJournalScanner.Scan scan = WorldJournalScanner.scan(childFile);
        BranchOrigin origin = childOrigin(scan);
        return new WorldRuntime.BranchReceipt(origin.childRunId(), origin.parentRunId(),
                origin.parentPosition(), childFile, origin.parentPosition() + 1L,
                origin.parentPrefixSha256(), "", sealFile, origin.profileDigest());
    }

    private static WorldRuntime.BranchReceipt receiptOf(Path parentFile) throws IOException {
        return receipt(parentFile, CHILD);
    }

    private static BranchOrigin childOrigin(WorldJournalScanner.Scan scan) throws IOException {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.convertValue(
                    mapper.readTree(scan.commits().get(0).commandContent()).get("branch"),
                    BranchOrigin.class);
        } catch (RuntimeException invalid) {
            throw new IOException("子档首帧缺少来源", invalid);
        }
    }

    private static WorldRuntime openChild(WorldRuntime.BranchReceipt receipt, Path parentFile)
            throws IOException {
        return WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? parentFile : null, profile());
    }

    static WorldRuntime openParent(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), openings(), registry(), MFG, charter(), roles(),
                information(), decisions());
    }

    static BranchProfile profile() {
        return new BranchProfile(manifest(), openings(), registry(), MFG, charter(), roles(),
                information(), decisions(), null, null, null, null, null, 0L, List.of(), null,
                null, null, 0, 0L);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend")) {
            modules.put(name, 1);
        }
        return new RunManifest(PARENT, SEED, RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1,
                modules, Map.of("fixture", "branch-jvm-probe-v1"),
                RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(COMPANY_OWNER, COMPANY_ACC, "SUP", "CUS", "OPS"), SYM);

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("INV2", INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("OTHER", "other-org", new BigDecimal("0.00"), "独立组织"),
                new OpeningAccount(COMPANY_ACC, COMPANY_OWNER, new BigDecimal("1000.00"),
                        "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
                new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
                new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY, List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
    }

    private static RoleRun.Config roles() {
        return new RoleRun.Config(List.of(), List.of());
    }

    private static InformationRun.Config information() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static DecisionModel.Config decisions() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, new BigDecimal("0.00"));
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            caps.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:capital-v1",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 2_000, 2_000, 4, 2, 60_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(COMPANY, PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("other-org", PartyKind.ORGANIZATION, "其它组织"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner("other-org",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "other-org"),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OpeningRegistry.OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner(COMPANY_OWNER,
                        OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE, null));
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, COMPANY, 200L,
                        new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }
}
