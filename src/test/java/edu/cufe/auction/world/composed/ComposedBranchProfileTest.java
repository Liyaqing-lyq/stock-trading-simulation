package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRejectedException;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static edu.cufe.auction.world.composed.ComposedWorldScenario.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合档分支画像（v3）验收：真实 bank + 额外章程父档 → 派生子档 → 独立 JVM 重开，
 * 父子资金隔离；改 bankOpening/章程、旧 v2 画像冒用新清单、未知版本一律拒绝且不写子文件。
 */
class ComposedBranchProfileTest {

    private static final long DAY = 86_400_000L;
    /** 组合档银行键（north 银行）与章程公司（cufe）。 */
    private static final String BANK = ComposedWorldScenario.BANK;
    private static final String CUFE = "cufe";

    @Test
    void composedParentProfileIsV3AndBranchReopensInAnotherJvm() throws Exception {
        Path dir = Files.createTempDirectory("composed-branch-v3");
        Path world = dir.resolve("world.log");
        Path childFile;
        Path sealedPrefix;
        Path runsDir = dir.resolve("runs");
        String childSha;
        Map<String, Object> childAccounts;

        try (WorldRuntime parent = ComposedWorldScenario.open(world)) {
            BranchProfile profile = parent.profile();
            assertEquals(BranchProfile.V3_VERSION, profile.version(), "组合档画像必须是 v3");
            assertNotNull(profile.bankOpening(), "v3 必须冻结银行开局申请");
            assertTrue(!profile.companyCharters().isEmpty(), "v3 必须冻结额外公司章程");
            assertEquals(profile.digest(), parent.profile().digest(), "同源画像摘要必须稳定");

            long cut = parent.snapshot().position();
            WorldRuntime.BranchReceipt receipt = parent.createBranch("child-v3", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "组合档 v3 分支");
            childFile = receipt.childFile();
            sealedPrefix = receipt.sealedPrefix();
            assertTrue(Files.isRegularFile(childFile), "子档必须已发布");
            assertTrue(Files.isRegularFile(sealedPrefix), "封存父前缀必须已发布");

            // 同进程重开：位置/账户/银行/章程齐全，且分支元数据记 v3
            try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                    profile)) {
                assertEquals(cut + 1, child.snapshot().position());
                assertNotEquals(world, receipt.childFile());
                BranchOrigin origin = child.branchOrigin().orElseThrow();
                assertEquals(BranchProfile.V3_VERSION, origin.profileVersion(),
                        "分支元数据必须记 v3");
                assertEquals(profile.digest(), origin.profileDigest(), "元数据摘要必须是 v3 摘要");
                assertTrue(child.bankOf().isPresent(), "子档必须带银行三态");
                assertTrue(child.companyGovernance().isPresent(), "子档必须带额外公司治理");
                childAccounts = Map.copyOf(child.snapshot().accounts());
            }
            childSha = ComposedBranchReopenProbe.sha256(childFile);

            // 父档继续推进：子档字节与账户必须完全不受影响（资金隔离）
            parent.advanceTo(parent.snapshot().timeMillis() + DAY);
            assertEquals(childSha, ComposedBranchReopenProbe.sha256(childFile),
                    "父档推进不得改写子档任何字节");
        }

        // 独立 JVM 重开：父档写者已关闭
        String output = runProbe("edu.cufe.auction.world.composed.ComposedBranchReopenProbe",
                childFile, world, sealedPrefix);
        assertTrue(output.contains("POSITION="), "独立 JVM 重开必须成功：" + output);
        assertTrue(output.contains("BANK=true"), "独立 JVM 里的子档必须带银行：" + output);
        assertTrue(output.contains("GOVERNANCE=true"), "独立 JVM 里的子档必须带章程：" + output);
        assertTrue(output.contains("PROFILE=" + BranchProfile.V3_VERSION), output);

        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                parentProfile(world))) {
            assertEquals(childAccounts, Map.copyOf(child.snapshot().accounts()),
                    "重开后账户必须与创建时逐项相同");
        }
        assertTrue(Files.isDirectory(runsDir), "运行目录必须存在");
    }

    @Test
    void tamperedBankOpeningAndCharterAreRejectedWithoutNewChildFile() throws Exception {
        Path dir = Files.createTempDirectory("composed-branch-tamper");
        Path world = dir.resolve("world.log");
        Path runsDir = dir.resolve("runs");
        Path childFile;
        Path sealedPrefix;
        BranchProfile profile;
        try (WorldRuntime parent = ComposedWorldScenario.open(world)) {
            profile = parent.profile();
            long cut = parent.snapshot().position();
            WorldRuntime.BranchReceipt receipt = parent.createBranch("child-t1", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "篡改基线");
            childFile = receipt.childFile();
            sealedPrefix = receipt.sealedPrefix();
        }
        List<String> before = ComposedBranchReopenProbe.entries(runsDir);

        BankOpeningRequest bank = profile.bankOpening();
        BankOpeningRequest tamperedBank = new BankOpeningRequest(bank.configJson(),
                bank.endowmentJson() + " ", bank.custodyDeclared(), bank.custodyOrigins());
        BranchProfile withBank = new BranchProfile(profile.manifest(), profile.openings(),
                profile.registry(), profile.manufacturing(), profile.charter(), profile.roles(),
                profile.information(), profile.decisions(), profile.media(), profile.interview(),
                profile.dialogue(), profile.evidence(), profile.social(),
                profile.socialBlockMillis(), profile.contacts(), profile.engineering(),
                profile.groups(), profile.platform(), profile.feedLimit(), profile.readMillis(),
                tamperedBank, profile.companyCharters());

        BranchRejectedException bankReject = assertThrows(BranchRejectedException.class,
                () -> WorldRuntime.openBranch(childFile, id -> sealedPrefix, withBank));
        assertEquals(WorldRuntime.BRANCH_PROFILE_MISMATCH, bankReject.code());
        assertFalse(withBank.digest().equals(profile.digest()), "改银行申请必须改摘要");

        String company = profile.companyCharters().keySet().iterator().next();
        var charter = profile.companyCharters().get(company);
        var charters = new java.util.TreeMap<>(profile.companyCharters());
        charters.put(company, new edu.cufe.auction.world.BoardModel.Charter(company,
                charter.seats(), charter.seats().get(1).seatId(), charter.quorum()));
        BranchProfile withCharter = new BranchProfile(profile.manifest(), profile.openings(),
                profile.registry(), profile.manufacturing(), profile.charter(), profile.roles(),
                profile.information(), profile.decisions(), profile.media(), profile.interview(),
                profile.dialogue(), profile.evidence(), profile.social(),
                profile.socialBlockMillis(), profile.contacts(), profile.engineering(),
                profile.groups(), profile.platform(), profile.feedLimit(), profile.readMillis(),
                profile.bankOpening(), charters);
        BranchRejectedException charterReject = assertThrows(BranchRejectedException.class,
                () -> WorldRuntime.openBranch(childFile, id -> sealedPrefix, withCharter));
        assertEquals(WorldRuntime.BRANCH_PROFILE_MISMATCH, charterReject.code());
        assertFalse(withCharter.digest().equals(profile.digest()), "改章程必须改摘要");

        assertEquals(before, ComposedBranchReopenProbe.entries(runsDir),
                "被拒绝的打开不得新增或删除任何运行目录条目");
    }

    @Test
    void oldV2ProfileCannotImpersonateComposedManifest() throws Exception {
        Path dir = Files.createTempDirectory("composed-branch-v2");
        try (WorldRuntime parent = ComposedWorldScenario.open(dir.resolve("world.log"))) {
            BranchProfile profile = parent.profile();
            // 旧 20 参口径（v2 兼容入口）配组合档清单：能力与配置不成对，必须当场拒绝
            IllegalArgumentException reject = assertThrows(IllegalArgumentException.class,
                    () -> new BranchProfile(profile.manifest(), profile.openings(),
                            profile.registry(), profile.manufacturing(), profile.charter(),
                            profile.roles(), profile.information(), profile.decisions(),
                            profile.media(), profile.interview(), profile.dialogue(),
                            profile.evidence(), profile.social(), profile.socialBlockMillis(),
                            profile.contacts(), profile.engineering(), profile.groups(),
                            profile.platform(), profile.feedLimit(), profile.readMillis()));
            assertTrue(reject.getMessage().contains("成对"), reject.getMessage());
        }
    }

    @Test
    void unknownProfileVersionIsRejected() {
        IllegalArgumentException reject = assertThrows(IllegalArgumentException.class,
                () -> new BranchOrigin("parent", 3, "0".repeat(64), "1".repeat(64), "child",
                        BranchOrigin.Kind.OPERATOR_BRANCH, "note", 1L,
                        BranchOrigin.Policy.inherited(7L), 100L, "branch-profile-v9",
                        "2".repeat(64)));
        assertTrue(reject.getMessage().contains("不支持的分支画像摘要口径"), reject.getMessage());
        assertTrue(BranchProfile.knownVersion(BranchProfile.VERSION));
        assertTrue(BranchProfile.knownVersion(BranchProfile.V3_VERSION));
        assertFalse(BranchProfile.knownVersion("branch-profile-v9"));
    }

    /**
     * 真实非零银行父子业务：父档先做真实 S1 任免与存贷，再按历史切点建子档；父子随后各做不同的
     * 真实资金动作，独立 JVM 重开后仍各自完整（BankRun/现金/额外公司治理/分支元数据）且互不串档。
     *
     * <p>数值全部由真实银行动作产生（公司经理存入/取回、银行经理按同条款批贷、借款人提款），
     * 不直接改 state/账/角色；切点前的存贷款由父档真实发生，子档继承后各自独立演进。</p>
     */
    @Test
    void nonzeroBankBusinessIsIsolatedBetweenParentAndChild() throws Exception {
        Path dir = Files.createTempDirectory("composed-branch-business");
        Path world = dir.resolve("world.log");
        Path childFile;
        Path sealedPrefix;
        String childShaAtCut;
        String parentDigest;
        String childDigest;
        java.math.BigDecimal reserveAtCut;
        long cut;
        BranchProfile profile;

        try (WorldRuntime parent = ComposedWorldScenario.open(world)) {
            profile = parent.profile();
            long now = parent.snapshot().timeMillis();
            // 真实 S1 任免：north 银行经理（批贷）、cufe 公司经理（存贷申请人）
            now = ComposedWorldSelfTest.appoint(parent, now, BANK, "erin", "north", "north-mgr");
            // dave 开局已持 cufe 执行交易员 A-4：同人物同机构不能兼任，先走真实撤权帧再任免。
            parent.scheduleRevokeAppointment("rv-A-4", "rvt-A-4", now + 1, "A-4");
            parent.advanceTo(now + 2);
            now = ComposedWorldSelfTest.appoint(parent, now + 3, CUFE, "dave", "cufe", "cufe-mgr");
            WorldRoleSession dave = parent.openRoleSession("dave");
            WorldRoleSession erin = parent.openRoleSession("erin");

            bankAction(parent, dave, "dp-parent", BankActions.Kind.DEPOSIT, "300.00");
            bankAction(parent, dave, "wd-parent", BankActions.Kind.WITHDRAW, "50.00");
            loan(parent, dave, erin);
            BankState bank = bankOf(parent);
            assertEquals(0, bank.depositOf(BANK, CUFE).compareTo(money("450.00")),
                    "父档切点前必须已有真实存款：存入 300 − 取回 50 + 放款 200，实际 "
                            + bank.depositOf(BANK, CUFE));
            assertEquals(0, bank.loan(BANK, CUFE).contract().compareTo(money("200.00")),
                    "父档切点前必须已有真实贷款合同，实际 " + bank.loan(BANK, CUFE).contract());
            assertTrue(parent.snapshot().governance().orElseThrow().board().meetings().values()
                            .stream().anyMatch(meeting -> meeting.status()
                                    == BoardModel.MeetingStatus.IMPLEMENTED),
                    "父档切点前必须已发生真实章程治理（任免会议已实施）");

            // 切点真实准备金：不假设理论值，从当前三态读取，此后按真实动作做 ± 推导
            reserveAtCut = bank.gl(BANK, "RESERVE");
            cut = parent.snapshot().position();
            WorldRuntime.BranchReceipt receipt = parent.createBranch("child-biz", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "非零银行父子业务分支");
            childFile = receipt.childFile();
            sealedPrefix = receipt.sealedPrefix();
            assertTrue(Files.isRegularFile(childFile), "子档必须已发布");
            childShaAtCut = ComposedBranchReopenProbe.sha256(childFile);

            // 父档做与子档不同的资金动作：再存入 100（存款 450→550、现金 650）
            bankAction(parent, dave, "dp-parent2", BankActions.Kind.DEPOSIT, "100.00");
            BankState parentBank = bankOf(parent);
            assertEquals(0, parentBank.depositOf(BANK, CUFE).compareTo(money("550.00")),
                    "父档自己的存款动作必须真实生效，实际 " + parentBank.depositOf(BANK, CUFE));
            assertEquals(0, parent.snapshot().accounts().get("CO").cash()
                    .compareTo(money("650.00")), "父档现金必须按真实动作变化（1000−300+50−100），实际 "
                    + parent.snapshot().accounts().get("CO").cash());
            assertEquals(childShaAtCut, ComposedBranchReopenProbe.sha256(childFile),
                    "父档推进不得改写子档任何字节");
            assertEquals(0, parentBank.gl(BANK, "RESERVE").compareTo(
                            reserveAtCut.add(money("100.00"))),
                    "父档准备金必须等于切点值 + 真实存入 100：切点 " + reserveAtCut
                            + " 实际 " + parentBank.gl(BANK, "RESERVE"));
            parentDigest = ComposedBranchBusinessProbe.digest(parent);
        }
        // 父档写者已关闭（Windows 下父日志在写者持有期间被锁），此后才能取父档字节摘要
        String parentSha = ComposedBranchReopenProbe.sha256(world);

        // 子档做另一种真实银行动作：取回 25（继承的 450→425）；父档不得被改写。
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                profile)) {
            assertEquals(0, bankOf(child).depositOf(BANK, CUFE).compareTo(money("450.00")),
                    "子档必须继承切点时的真实存款，实际 " + bankOf(child).depositOf(BANK, CUFE));
            WorldRoleSession childDave = child.openRoleSession("dave");
            bankAction(child, childDave, "wd-child", BankActions.Kind.WITHDRAW, "25.00");
            assertEquals(0, bankOf(child).depositOf(BANK, CUFE).compareTo(money("425.00")),
                    "子档自己的取回动作必须真实生效，实际 " + bankOf(child).depositOf(BANK, CUFE));
            assertEquals(0, child.snapshot().accounts().get("CO").cash()
                    .compareTo(money("775.00")), "子档自己的取回必须真实进现金，实际 "
                    + child.snapshot().accounts().get("CO").cash());
            assertEquals(0, bankOf(child).gl(BANK, "RESERVE").compareTo(
                            reserveAtCut.subtract(money("25.00"))),
                    "子档准备金必须等于切点值 − 真实取回 25：切点 " + reserveAtCut
                            + " 实际 " + bankOf(child).gl(BANK, "RESERVE"));
            childDigest = ComposedBranchBusinessProbe.digest(child);
        }
        assertNotEquals(childShaAtCut, ComposedBranchReopenProbe.sha256(childFile),
                "子档自己的动作必须改写子档日志");
        assertEquals(parentSha, ComposedBranchReopenProbe.sha256(world),
                "子档动作不得改写父档任何字节");
        String childShaAfter = ComposedBranchReopenProbe.sha256(childFile);

        // 独立 JVM：父档写者已关闭，两个进程都只读重开，父子各读数互不串档
        String output = runProbe(
                "edu.cufe.auction.world.composed.ComposedBranchBusinessProbe",
                childFile, world, sealedPrefix);
        String childLine = lineOf(output, "CHILD");
        String parentLine = lineOf(output, "PARENT");
        assertTrue(childLine.contains("DEPOSIT=425.00"), "独立 JVM 子档存款必须是 425：" + output);
        assertTrue(childLine.contains("LOAN=200.00"), "子档必须继承真实贷款合同：" + output);
        assertTrue(childLine.contains("CASH=775.00"), "子档取回后 CO 现金必须是 775：" + output);
        assertTrue(childLine.contains("INV=0.00"), output);
        assertTrue(childLine.contains("RESERVE=" + reserveAtCut.subtract(money("25.00"))
                .toPlainString()), "子档准备金必须是切点 − 25：" + output);
        assertTrue(childLine.contains("BANK_RUN=" + ComposedWorldScenario.RUN_ID),
                "子档银行历史账身份仍绑父运行：" + output);
        assertEquals(childDigest, valueOf(childLine, "STATE_SHA"), "子档完整状态必须相同");
        assertTrue(childLine.contains("BANK=true") && childLine.contains("GOVERNANCE=true"),
                "子档必须带银行三态与额外公司治理：" + output);
        assertTrue(childLine.contains("CUFE_CHARTER=5"), "子档必须带章程公司：" + output);
        assertTrue(childLine.contains("PROFILE=" + BranchProfile.V3_VERSION), output);
        assertTrue(childLine.contains("CHILD_RUN=child-biz"), output);
        assertTrue(childLine.contains("PARENT_POSITION=" + cut), output);
        assertTrue(parentLine.contains("DEPOSIT=550.00"),
                "独立 JVM 父档存款必须仍是 550（未被带偏）：" + output);
        assertTrue(parentLine.contains("LOAN=200.00"), output);
        assertTrue(parentLine.contains("CASH=650.00"),
                "独立 JVM 父档现金必须仍是 650（未被带偏）：" + output);
        assertTrue(parentLine.contains("INV=0.00"),
                "独立 JVM 父档 INV 必须仍是 0（未被带偏）：" + output);
        assertTrue(parentLine.contains("RESERVE=" + reserveAtCut.add(money("100.00"))
                .toPlainString()), "父档准备金必须是切点 + 100：" + output);
        assertEquals(parentDigest, valueOf(parentLine, "STATE_SHA"), "父档完整状态必须相同");
        assertEquals(childShaAfter, ComposedBranchReopenProbe.sha256(childFile),
                "独立 JVM 只读重开不得改写子档");
        assertEquals(parentSha, ComposedBranchReopenProbe.sha256(world),
                "独立 JVM 只读重开不得改写父档");
        System.out.println("[BRANCH-BUSINESS] child=" + childLine);
        System.out.println("[BRANCH-BUSINESS] parent=" + parentLine);
        assertTrue(Files.isDirectory(dir.resolve("runs")), "运行目录必须存在");
    }

    /** @return 行内 {@code KEY=value} 的值；没有则返回空串 */
    private static String valueOf(String line, String key) {
        for (String token : line.split(" ")) {
            if (token.startsWith(key + "=")) {
                return token.substring(key.length() + 1);
            }
        }
        return "";
    }

    /** @return 输出中以前缀（CHILD/PARENT）开头的那一行 */
    private static String lineOf(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix + " ")) {
                return line;
            }
        }
        throw new AssertionError("探针输出缺少 " + prefix + " 行：" + output);
    }

    /** 当前银行三态（只读）。 */
    private static BankState bankOf(WorldRuntime runtime) throws Exception {
        return runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
    }

    /** 一笔真实公司资金动作：正工时 + 到期完成帧；不直接改账。 */
    private static void bankAction(WorldRuntime runtime, WorldRoleSession session, String id,
                                   BankActions.Kind kind, String amount) throws Exception {
        long due = runtime.snapshot().timeMillis() + 2;
        session.bankAction(id, id + "-t", due, 1L,
                new WorldRoleSession.BankActionDraft(id, kind, BANK, CUFE, money(amount),
                        0L, 0L, "", "", "", ""));
        runtime.advanceTo(due);
    }

    /** 完整真实贷款链：借款人提条款 → 银行经理按同条款批准 → 借款人接受 → 提款（放款进存款）。 */
    private static void loan(WorldRuntime runtime, WorldRoleSession borrower,
                             WorldRoleSession bankManager) throws Exception {
        long tenor = 30L * DAY;
        String purpose = "父档营运资金";
        ComposedWorldSelfTest.loanAction(runtime, borrower, "ln-req", BankActions.Kind.LOAN_REQUEST,
                CUFE, "200.00", 20_000L, tenor, purpose);
        ComposedWorldSelfTest.loanAction(runtime, borrower, "ln-acc",
                BankActions.Kind.LOAN_ACCEPT, CUFE, "200.00", 20_000L, tenor, purpose);
        ComposedWorldSelfTest.loanAction(runtime, bankManager, "ln-app",
                BankActions.Kind.LOAN_APPROVE, CUFE, "200.00", 20_000L, tenor, purpose);
        ComposedWorldSelfTest.loanAction(runtime, borrower, "ln-draw",
                BankActions.Kind.LOAN_DRAW, CUFE, "200.00", 20_000L, tenor, purpose);
    }

    private static BranchProfile parentProfile(Path world) throws Exception {
        try (WorldRuntime parent = ComposedWorldScenario.open(world)) {
            return parent.profile();
        }
    }

    /**
     * 独立 JVM 探针：输出重定向到临时日志文件再 {@code waitFor(超时)}。
     *
     * <p>不能先 {@code readAllBytes()} 再 {@code waitFor(120, …)}：前者会一直阻塞到子进程关闭
     * stdout，超时参数形同虚设。这里把输出写文件、只在超时后强制终止自有子进程，并在超时/失败时
     * 把已产生的输出带回断言消息。</p>
     */
    private static String runProbe(String probeClass, Path childFile, Path world,
                                   Path sealedPrefix) throws Exception {
        Path log = Files.createTempFile("composed-branch-probe-", ".log");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBin, "-cp",
                System.getProperty("java.class.path"), probeClass,
                childFile.toString(), world.toString(), sealedPrefix.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(finished, "独立 JVM 探针超时（已终止自有子进程）：" + output);
        assertEquals(0, process.exitValue(), "独立 JVM 探针失败：" + output);
        return output.trim();
    }
}
