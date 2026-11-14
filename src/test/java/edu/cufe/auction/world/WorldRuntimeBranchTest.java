package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1：独立分支后端。
 *
 * <p>夹具用真实的 {@code dividend-world-experiment-v1} 运行：真实资本注入、真实成交成本与
 * 已结算分红都在父档里，切点取"分红已支付"之后的确认位置。验收关注的是语义而不是形状：
 * 子档初始报表必须与父界点一致（不丢历史资本流、不把注资算成收益）、父档字节不变、
 * 子档自洽篡改被拒、父前缀篡改被拒、旧运行回包被拒、未决切点被拒。</p>
 */
@DisplayName("WorldRuntime：独立分支 B1 后端")
class WorldRuntimeBranchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RUN = "branch-parent";
    private static final long SEED = 17L;
    private static final String SYM = "CUFE1";
    private static final String COMPANY_PARTY = "cufe";
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACC = "CO";
    private static final String INV_PARTY = "investor";
    private static final String ACC_INV = "INV";
    private static final String ACC_INV2 = "INV2";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";
    private static final String RULES_DIVIDEND = RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1;

    private static final long T0 = 1_000L;
    private static final long CLOSES = T0 + 20_000;
    private static final long AUTH = CLOSES + 1;
    private static final long RECORD = AUTH + 2;
    private static final long EX = RECORD + 1_000;
    private static final long PAY = EX + 1_000;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 1. 父界点报表与子初始报表一致
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有资本流与分红成交成本的父界点：子档继承同一财务链，不把注资算成收益")
    void childInheritsParentFinancialChain() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.Snapshot parentSnapshot;
        List<WorldRuntime.CapitalFlowReport> parentFlows;
        List<WorldRuntime.DividendReport> parentDividends;
        List<PerformanceModel.Segment> parentSegments;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            parentSnapshot = parent.snapshot();
            parentFlows = parent.capitalFlows(INV_PARTY);
            parentDividends = parent.dividendReports();
            parentSegments = parent.performance(INV_PARTY, 1, cut).segments();
            assertFalse(parentFlows.isEmpty(), "父档必须真的有资本流");
            assertFalse(parentDividends.isEmpty(), "父档必须真的有分红报告");
            receipt = parent.createBranch("branch-fin-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "财务链继承");
            assertEquals(cut + 1L, receipt.firstPosition());
        }
        // 子档在父档关闭后打开：Windows 上父写入者对此文件持有排他锁，同进程第二次读取会被拒绝
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            assertEquals(cut + 1L, child.snapshot().position(), "子首帧位置必须是父位置加一");
            BranchOrigin origin = child.branchOrigin().orElseThrow();
            assertEquals(RUN, origin.parentRunId());
            assertEquals(cut, origin.parentPosition());
            assertEquals("branch-fin-a", origin.childRunId());

            assertEquals(parentFlows, child.capitalFlows(INV_PARTY),
                    "继承前缀的资本流必须同一批、同一位置，不丢也不双计");
            assertEquals(parentDividends, child.dividendReports(),
                    "继承前缀的分红报告必须同一批");
            assertEquals(parentSegments, child.performance(INV_PARTY, 1, cut).segments(),
                    "父前缀区间收益链必须与父档逐点相同（含资本流前后净值）");

            // 派生只改身份：资产、义务、去重与待办必须原样
            assertEquals(parentSnapshot.accounts().keySet(), child.snapshot().accounts().keySet());
            for (String account : parentSnapshot.accounts().keySet()) {
                assertEquals(0, parentSnapshot.accounts().get(account).cash()
                                .compareTo(child.snapshot().accounts().get(account).cash()),
                        "现金必须逐账户相同：" + account);
            }
            assertEquals(parentSnapshot.pending(), child.snapshot().pending());
            assertEquals(parentSnapshot.usedTaskIds(), child.snapshot().usedTaskIds());
            assertEquals(parentSnapshot.delivered(), child.snapshot().delivered());
            assertEquals(parentSnapshot.timeMillis(), child.snapshot().timeMillis());
            assertEquals(parentSnapshot.lastPhase(), child.snapshot().lastPhase());
        }
    }

    @Test
    @DisplayName("同一父界点重开：派生状态稳定；两次创建的首帧因元数据不同")
    void deriveIsDeterministicButOriginCarriesMetadata() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt first;
        WorldRuntime.BranchReceipt second;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            first = parent.createBranch("branch-det-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "第一次");
            second = parent.createBranch("branch-det-b", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "第二次");
            assertEquals(first.parentPrefixSha256(), second.parentPrefixSha256(),
                    "同一父界点的前缀字节摘要必须相同");
        }
        String firstDigest;
        String repeatDigest;
        try (WorldRuntime child = openChild(first, parentFile)) {
            firstDigest = child.checkpoint(cut + 1L).stateDigest();
        }
        try (WorldRuntime child = openChild(first, parentFile)) {
            repeatDigest = child.checkpoint(cut + 1L).stateDigest();
        }
        assertEquals(firstDigest, repeatDigest,
                "同一父界点、同一子清单重开两次必须得到逐字节相同的派生状态");
        String firstContent = WorldJournalScanner.scan(first.childFile()).commits().get(0)
                .commandContent();
        String secondContent = WorldJournalScanner.scan(second.childFile()).commits().get(0)
                .commandContent();
        assertNotEquals(firstContent, secondContent,
                "首帧整体含创建时刻与注记，不应声称逐字节相同");
    }

    // ------------------------------------------------------------------
    // 2. 继承的未完成业务在子完成，父不变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("继承的待办在子档完成，父档字节与位置都不变")
    void childCompletesInheritedPendingWhileParentUnchanged() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        Path beforeCopy = tempDir.resolve("parent-before.journal");
        long cut;
        long parentPosition;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            parent.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            cut = parent.snapshot().position();
            assertEquals(2L, cut, "切点应停在排程批（业务仍待办）");
            assertTrue(parent.snapshot().pending().stream()
                    .anyMatch(task -> "t1".equals(task.taskId())), "切点必须真的还有未完成业务");
            parentPosition = parent.snapshot().position();
            receipt = parent.createBranch("branch-pending-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "继承未完成业务");
        }
        // 父档关闭后留一份字节快照，再用来证明后续动作没有改动父档
        Files.copy(parentFile, beforeCopy);

        try (WorldRuntime child = openChild(receipt, parentFile)) {
            BigDecimal before = cash(child, ACC_INV);
            child.advanceTo(T0);
            assertEquals(0, cash(child, ACC_INV)
                            .compareTo(before.add(new BigDecimal("500.00"))),
                    "子档必须能完成继承的资本注入");
            assertTrue(child.snapshot().position() > cut + 1L, "子档必须真的前进了");
            assertTrue(child.snapshot().pending().stream()
                    .noneMatch(task -> "t1".equals(task.taskId())), "继承待办必须在子档结束");
        }

        try (WorldRuntime reopened = open(parentFile)) {
            assertEquals(parentPosition, reopened.snapshot().position(), "父档位置不得改变");
        }
        assertArrayEqualsBytes(Files.readAllBytes(beforeCopy), Files.readAllBytes(parentFile),
                "父档字节必须逐字节不变");
    }

    // ------------------------------------------------------------------
    // 3. 子档自洽篡改（重算帧摘要）仍被拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("子首帧把现金加一后重签帧：帧摘要自洽仍必须拒绝")
    void forgedChildFirstFrameCashIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-forge-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "篡改基准");
        }

        Path forged = tempDir.resolve("forged-child.journal");
        rewriteChild(receipt.childFile(), forged, cut, snapshot -> {
            ObjectNode account = (ObjectNode) ((ObjectNode) snapshot.get("accounts"))
                    .get(ACC_INV);
            account.put("cash", account.get("cash").decimalValue()
                    .add(new BigDecimal("1.00")));
        }, null);

        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> openChild(forged, receipt.sealedPrefix()).close());
        assertEquals(WorldRuntime.BRANCH_DERIVATION_MISMATCH, rejection.code());
    }

    // ------------------------------------------------------------------
    // 4. 父前缀语义篡改（连前缀摘要一起改）仍被拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("封存父前缀被重签（字节摘要与子档元数据一起改）仍按语义拒绝")
    void forgedSealWithMatchingHashIsRejectedSemantically() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        Path forgedSeal = tempDir.resolve("seal-forged.prefix.journal");
        Path forgedChild = tempDir.resolve("child-with-forged-seal.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-seal-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "封存件篡改基准");
        }
        // 封存件重签：末帧现金 +1.00，帧校验和一起重算（文件自身自洽）
        rewriteLastFrame(receipt.sealedPrefix(), forgedSeal, RUN, snapshot -> {
            ObjectNode account = (ObjectNode) ((ObjectNode) snapshot.get("accounts")).get("CUS");
            account.put("cash", account.get("cash").decimalValue()
                    .add(new BigDecimal("1.00")));
        });
        long forgedBytes = Files.size(forgedSeal);
        String forgedSha = WorldJournalScanner.sha256Prefix(forgedSeal, forgedBytes);
        assertNotEquals(receipt.parentPrefixSha256(), forgedSha,
                "重签后的封存件摘要必须变化");

        // 子档首帧：把来源里的摘要与字节数改成"篡改后封存件"的真实值并重签
        rewriteChild(receipt.childFile(), forgedChild, cut, null, content -> {
            ObjectNode origin = (ObjectNode) content.get("branch");
            origin.put("parentPrefixSha256", forgedSha);
            origin.put("parentPrefixBytes", forgedBytes);
        });

        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> openChild(forgedChild, forgedSeal).close());
        assertEquals(WorldRuntime.BRANCH_PARENT_PREFIX_MISMATCH, rejection.code(),
                "摘要对上也不得接受语义不合法的封存父前缀");
    }

    // ------------------------------------------------------------------
    // 5. 合法后缀更新不破坏子档；同一父 hash 缺失明确拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("父档在切点之后继续追加不影响已确认前缀上的子档")
    void parentSuffixUpdatesDoNotBreakChild() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-suffix-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "后缀更新");
            long beforeSuffix = parent.snapshot().position();

            parent.scheduleTransfer("c-after", "t-after", PAY + 5_000, "customer", "CUS",
                    "SUP", new BigDecimal("10.00"), 1L);
            parent.advanceTo(PAY + 5_000);
            assertTrue(parent.snapshot().position() > beforeSuffix, "父档必须真的追加了新帧");
        }
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            assertEquals(cut + 1L, child.snapshot().position(),
                    "子档只恢复到父切点加一，不吞父档后缀");
        }
    }

    @Test
    @DisplayName("子档来源缺少父前缀摘要时明确拒绝，不做无摘要降级")
    void missingParentPrefixHashIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-nohash-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "缺摘要基准");
        }

        Path broken = tempDir.resolve("child-without-hash.journal");
        rewriteChild(receipt.childFile(), broken, cut, null,
                content -> ((ObjectNode) content.get("branch")).remove("parentPrefixSha256"));

        assertThrows(IOException.class, () -> openChild(broken, parentFile).close(),
                "缺少父前缀摘要必须拒绝");
    }

    @Test
    @DisplayName("封存父前缀未登记时明确拒绝，不回退去打开活跃父档")
    void missingSealIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-noseal-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "封存件缺失");
        }
        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> WorldRuntime.openBranch(receipt.childFile(), id -> null, profile()));
        assertEquals(WorldRuntime.BRANCH_SEAL_MISSING, rejection.code());
    }

    // ------------------------------------------------------------------
    // 4b. 封存父前缀本身被破坏（长度/摘要/换父）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("封存父前缀被改一个字节：帧校验和与摘要都不符，明确拒绝")
    void tamperedSealByteIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-seal-byte", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "封存件改字节");
        }
        Path tampered = tempDir.resolve("seal-tampered.prefix.journal");
        byte[] bytes = Files.readAllBytes(receipt.sealedPrefix());
        bytes[bytes.length - 40] ^= 0x01;
        Files.write(tampered, bytes);

        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> openChild(receipt.childFile(), tampered).close());
        assertEquals(WorldRuntime.BRANCH_SEAL_MISMATCH, rejection.code());
    }

    @Test
    @DisplayName("封存父前缀被截断或追加尾部：末位与字节数不符，明确拒绝")
    void truncatedOrExtendedSealIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-seal-len", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "封存件长度");
        }
        byte[] bytes = Files.readAllBytes(receipt.sealedPrefix());

        Path truncated = tempDir.resolve("seal-truncated.prefix.journal");
        Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length - 10));
        assertEquals(WorldRuntime.BRANCH_SEAL_MISMATCH, assertThrows(
                BranchRejectedException.class,
                () -> openChild(receipt.childFile(), truncated).close()).code());

        Path extended = tempDir.resolve("seal-extended.prefix.journal");
        byte[] longer = java.util.Arrays.copyOf(bytes, bytes.length + 4);
        Files.write(extended, longer);
        assertEquals(WorldRuntime.BRANCH_SEAL_MISMATCH, assertThrows(
                BranchRejectedException.class,
                () -> openChild(receipt.childFile(), extended).close()).code());
    }

    @Test
    @DisplayName("用另一条运行的封存件冒充：运行标识不符，明确拒绝")
    void sealFromAnotherRunIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        WorldRuntime.BranchReceipt other;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-seal-own", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "自己的封存件");
            other = parent.createBranch("branch-seal-other", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "另一份封存件");
        }
        // 同一父运行的封存件内容相同；这里再证明"换父"会被运行标识挡下：
        // 用另一条运行（不同 runId）的封存件冒充
        Path alien = tempDir.resolve("seal-alien.prefix.journal");
        try (WorldJournal foreign = new WorldJournal(alien, "some-other-run")) {
            foreign.commit("__open__", "{}", "OPENED", java.util.List.of(
                    new WorldEvent("state:1", "STATE_SNAPSHOT_V1", "{}")));
        }
        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> openChild(receipt.childFile(), alien).close());
        assertEquals(WorldRuntime.BRANCH_SEAL_MISMATCH, rejection.code());
        assertTrue(other.childFile().toFile().exists());
    }

    // ------------------------------------------------------------------
    // 4c. 可信画像身份与可信仓储入口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("宿主画像与分支元数据不一致：明确拒绝（媒体/群组等未入首帧配置只能由画像保证）")
    void profileMismatchIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-profile-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "画像身份");
        }
        // 画像里改一个不进父档首帧的字段（分红决议门槛），首帧仍能对上，但摘要必须不符
        BranchProfile wrong = new BranchProfile(manifest(), openings(), registry(), MFG, charter(),
                roles(), information(), decisions(), null, null, null, null, null, 0L, List.of(),
                null, null, null, 0, 0L);
        BranchProfile altered = new BranchProfile(wrong.manifest(), wrong.openings(),
                wrong.registry(), wrong.manufacturing(), wrong.charter(), wrong.roles(),
                new InformationRun.Config(List.of(ALICE, BOB)), wrong.decisions(), null, null,
                null, null, null, 0L, List.of(), null, null, null, 0, 0L);
        assertNotEquals(profile().digest(), altered.digest(), "画像摘要必须能区分配置差异");

        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> WorldRuntime.openBranch(receipt.childFile(),
                        id -> id.equals(receipt.parentRunId()) ? receipt.sealedPrefix() : null,
                        altered).close());
        assertEquals(WorldRuntime.BRANCH_PROFILE_MISMATCH, rejection.code());
    }

    @Test
    @DisplayName("可信仓储按索引打开子档；未登记标识与越界路径都拒绝")
    void repositoryOpensOnlyRegisteredRuns() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        long cut;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-repo-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "仓储入口");
        }
        BranchRepository repository = new BranchRepository(tempDir.resolve("runs"));
        assertEquals(1, repository.runs().size());
        assertEquals("branch-repo-a", repository.runs().get(0).runId());
        assertEquals(receipt.sealedPrefix().toAbsolutePath().toString(),
                repository.runs().get(0).sealedPrefix());

        try (WorldRuntime child = repository.open("branch-repo-a", profile())) {
            assertEquals(cut + 1L, child.snapshot().position());
            assertEquals("branch-repo-a",
                    child.branchOrigin().orElseThrow().childRunId());
        }
        assertEquals(WorldRuntime.BRANCH_SEAL_MISSING, assertThrows(
                BranchRejectedException.class,
                () -> repository.open("not-registered", profile())).code());
    }

    @Test
    @DisplayName("普通运行入口打开分支子档：按格式明确拒绝，不靠 runId 失配冒充版本门")
    void ordinaryEntryRejectsBranchFileExplicitly() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-entry-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "格式门");
        }
        IOException rejection = assertThrows(IOException.class,
                () -> new WorldRuntime(receipt.childFile(), manifest(), openings(), registry(),
                        MFG, charter(), roles(), information(), decisions()).close());
        assertTrue(rejection.getMessage().contains("分支子档必须经 openBranch"),
                "必须是显式格式拒绝，而不是运行标识不符：" + rejection.getMessage());
    }

    // ------------------------------------------------------------------
    // 6. 分支深度、未决切点、旧回包、身份
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分支不能再分支：父本身是分支时明确拒绝")
    void branchOfBranchIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-depth-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "深度基准");
        }
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                    () -> child.createBranch("branch-depth-b", child.snapshot().position(),
                            BranchOrigin.Kind.OPERATOR_BRANCH, "二次分支"));
            assertEquals(WorldRuntime.BRANCH_PARENT_IS_BRANCH, rejection.code());
        }
    }

    @Test
    @DisplayName("空会话（已开闸但无决定）可切：sessionOpen 不再是阻断，子档仍 sessionOpen=false")
    void openSessionWithoutDecisionsIsSplittable() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            parent.beginDecisionSession("open-1");
            assertTrue(parent.snapshot().decisions().orElseThrow().state().sessionOpen());
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-open-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "空会话切点");
        }
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            assertFalse(child.snapshot().decisions().orElseThrow().state().sessionOpen(),
                    "子档不得自动开闸");
            assertEquals(cut + 1L, child.snapshot().position());
        }
    }

    @Test
    @DisplayName("父运行的回执不能写进子档：先按运行身份拒绝")
    void oldRunReplyIsRejectedByChild() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-reply-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "旧回执基准");
        }
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            child.beginDecisionSession("child-open");
            DecisionModel.Reply stale = new DecisionModel.Reply(RUN, "b-target", "dec-attempt:1",
                    1, 1, "{}", null, "");
            assertEquals("REJECTED:" + DecisionModel.REASON_RUN_MISMATCH,
                    child.acceptDecisionReply("child-reply", stale),
                    "父运行的回执必须按运行身份被拒");
        }
    }

    @Test
    @DisplayName("运行标识白名单：大写、保留名、穿越与已存在都必须拒绝")
    void identityRulesRejectUnsafeIds() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            assertRejected(parent, "Branch-A", cut, WorldRuntime.BRANCH_IDENTITY_INVALID);
            assertRejected(parent, "con", cut, WorldRuntime.BRANCH_IDENTITY_RESERVED);
            assertRejected(parent, "a", cut, WorldRuntime.BRANCH_IDENTITY_INVALID);
            assertRejected(parent, "bad/id", cut, WorldRuntime.BRANCH_IDENTITY_INVALID);
            assertRejected(parent, "bad.id", cut, WorldRuntime.BRANCH_IDENTITY_INVALID);
            assertRejected(parent, RUN, cut, WorldRuntime.BRANCH_IDENTITY_INVALID);

            parent.createBranch("branch-dup-a", cut, BranchOrigin.Kind.OPERATOR_BRANCH, "重复基准");
            assertRejected(parent, "branch-dup-a", cut, WorldRuntime.BRANCH_EXISTS);
        }
    }

    @Test
    @DisplayName("切点越界与已存在业务位置：越界明确拒绝")
    void unconfirmedCutIsRejected() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            long cut = parent.snapshot().position();
            assertRejected(parent, "branch-out-a", cut + 1L, WorldRuntime.BRANCH_POSITION_UNCONFIRMED);
            assertRejected(parent, "branch-out-b", 0L, WorldRuntime.BRANCH_POSITION_UNCONFIRMED);
        }
    }

    // ------------------------------------------------------------------
    // 7. 历史来源标注与检查点拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("子档历史按来源标注：父前缀标父运行，自有帧标本运行；父前缀位置不导出检查点")
    void branchHistoryLabelsSources() throws IOException {
        Path parentFile = tempDir.resolve("parent.journal");
        long cut;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = open(parentFile)) {
            runCapitalAndDividend(parent);
            cut = parent.snapshot().position();
            receipt = parent.createBranch("branch-hist-a", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "历史来源");
        }
        try (WorldRuntime child = openChild(receipt, parentFile)) {
            List<WorldRuntime.BranchHistoryEntry> entries =
                    child.branchHistoryIndex(0, WorldRuntime.MAX_HISTORY_PAGE);
            assertEquals(cut + 1L, entries.size(), "历史索引必须覆盖父前缀加子首帧，无缺口");
            for (WorldRuntime.BranchHistoryEntry entry : entries) {
                if (entry.position() <= cut) {
                    assertEquals(WorldRuntime.HistorySource.PARENT_PREFIX, entry.source());
                    assertEquals(RUN, entry.sourceRunId());
                } else {
                    assertEquals(WorldRuntime.HistorySource.CHILD, entry.source());
                    assertEquals("branch-hist-a", entry.sourceRunId());
                }
            }
            WorldRuntime.BranchHistoryFrame inherited = child.branchHistoryFrame(1);
            assertEquals(WorldRuntime.HistorySource.PARENT_PREFIX, inherited.source());
            assertEquals(RUN, inherited.sourceRunId(),
                    "继承帧必须仍标父运行，不能伪称子运行原帧");
            assertEquals(1L, inherited.frame().snapshot().position());

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> child.checkpoint(1L));
            assertTrue(refused.getMessage().contains(
                    WorldRuntime.BRANCH_INHERITED_POSITION_NOT_EXPORTABLE));
            assertThrows(IllegalArgumentException.class, () -> child.historyFrame(1L),
                    "A 的 historyFrame 只服务本运行自有帧");
            // 本运行自有帧照常可导出
            assertEquals(cut + 1L, child.checkpoint(cut + 1L).position());
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void runCapitalAndDividend(WorldRuntime runtime) throws IOException {
        runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, "customer", "CUS", ACC_INV,
                new BigDecimal("500.00"), 1L, "入资");
        runtime.advanceTo(T0);
        // 真实成交成本：外部投资者卖出、客户买入
        runtime.scheduleOrder("s-sell", "s-sell", T0 + 100, INV_PARTY, ACC_INV, SYM,
                edu.cufe.auction.model.Side.SELL, edu.cufe.auction.model.OrderType.LIMIT,
                new BigDecimal("10.00"), 100L);
        runtime.scheduleOrder("s-buy", "s-buy", T0 + 101, "customer", "CUS", SYM,
                edu.cufe.auction.model.Side.BUY, edu.cufe.auction.model.OrderType.LIMIT,
                new BigDecimal("10.00"), 100L);
        runtime.advanceTo(T0 + 101);
        // 真实分红到支付
        runtime.scheduleBoardMeeting("c-meeting", "t-meeting", T0 + 200, "s1", "m1",
                T0 + 5_000, T0 + 10_000, CLOSES, List.of("s1", "s2", "s3"), List.of(),
                BoardModel.Topic.DIVIDEND_AUTHORIZATION);
        runtime.scheduleDividendProposal("c-proposal", "t-proposal", T0 + 201, "s1", "m1",
                List.of(), new DividendModel.SchemeRequest("S1", SYM, COMPANY_OWNER, COMPANY_ACC,
                        new BigDecimal("1.00"), RECORD, EX, PAY));
        for (String seat : List.of("s1", "s2", "s3")) {
            runtime.scheduleBoardVote("c-vote-" + seat, "t-vote-" + seat, T0 + 10_100, seat,
                    "m1", BoardModel.Vote.YES);
        }
        runtime.advanceTo(CLOSES);
        runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
        runtime.advanceTo(PAY);
    }

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), openings(), registry(), MFG, charter(), roles(),
                information(), decisions());
    }

    private WorldRuntime openChild(WorldRuntime.BranchReceipt receipt, Path ignored) 
            throws IOException {
        return WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? receipt.sealedPrefix() : null,
                profile());
    }

    private WorldRuntime openChild(Path childFile, Path seal) throws IOException {
        return WorldRuntime.openBranch(childFile, id -> id.equals(RUN) ? seal : null, profile());
    }

    private BranchProfile profile() {
        return new BranchProfile(manifest(), openings(), registry(), MFG, charter(), roles(),
                information(), decisions(), null, null, null, null, null, 0L, List.of(), null,
                null, null, 0, 0L);
    }

    private static void assertRejected(WorldRuntime parent, String childId, long position,
                                       String code) {
        BranchRejectedException rejection = assertThrows(BranchRejectedException.class,
                () -> parent.createBranch(childId, position, BranchOrigin.Kind.OPERATOR_BRANCH,
                        "拒绝基准"));
        assertEquals(code, rejection.code(), rejection.getMessage());
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0L;
        }
        try (var entries = Files.list(dir)) {
            return entries.count();
        }
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual, String message) {
        assertEquals(HexFormat.of().formatHex(sha256(expected)),
                HexFormat.of().formatHex(sha256(actual)), message);
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 只改 JSON 节点，不抛受检异常；序列化由夹具统一处理。 */
    @FunctionalInterface
    private interface NodeMutation {
        void change(ObjectNode node);
    }

    /**
     * 重签子档首帧：帧校验和随之重算，因此"自洽"仍必须被拒。
     *
     * @param snapshotMutation 对首帧状态快照的改动；null 表示不改
     * @param contentMutation  对首帧命令内容（含来源）的改动；null 表示不改
     */
    private static void rewriteChild(Path source, Path target, long cut,
                                     NodeMutation snapshotMutation, NodeMutation contentMutation)
            throws IOException {
        String childRunId = WorldJournalScanner.scan(source).runId();
        try (WorldJournal original = new WorldJournal(source, childRunId, cut);
             WorldJournal rewritten = new WorldJournal(target, childRunId, cut)) {
            boolean applied = false;
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>(commit.events());
                String content = commit.commandContent();
                if (!applied) {
                    for (int index = 0; index < events.size(); index++) {
                        WorldEvent event = events.get(index);
                        if (snapshotMutation != null && "STATE_SNAPSHOT_V1".equals(event.type())) {
                            ObjectNode node = readNode(event.payload());
                            snapshotMutation.change(node);
                            events.set(index, new WorldEvent(event.eventId(), event.type(),
                                    MAPPER.writeValueAsString(node)));
                        }
                    }
                    if (contentMutation != null) {
                        ObjectNode node = readNode(content);
                        contentMutation.change(node);
                        content = MAPPER.writeValueAsString(node);
                    }
                    applied = true;
                }
                rewritten.commit(commit.commandId(), content, commit.result(), events);
            }
            assertTrue(applied, "必须改到首帧");
        }
    }

    /** 重签普通父档的末帧状态快照：事件不动，帧校验和重算。 */
    private static void rewriteLastFrame(Path source, Path target, String runId,
                                         NodeMutation mutation) throws IOException {
        try (WorldJournal original = new WorldJournal(source, runId);
             WorldJournal rewritten = new WorldJournal(target, runId)) {
            List<WorldCommit> commits = original.commits();
            boolean applied = false;
            for (int index = 0; index < commits.size(); index++) {
                WorldCommit commit = commits.get(index);
                List<WorldEvent> events = new ArrayList<>(commit.events());
                if (index == commits.size() - 1) {
                    for (int position = 0; position < events.size(); position++) {
                        WorldEvent event = events.get(position);
                        if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                            ObjectNode node = readNode(event.payload());
                            mutation.change(node);
                            events.set(position, new WorldEvent(event.eventId(), event.type(),
                                    MAPPER.writeValueAsString(node)));
                        }
                    }
                    applied = true;
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
            assertTrue(applied, "必须改到末帧");
        }
    }

    private static ObjectNode readNode(String json) throws IOException {
        return (ObjectNode) MAPPER.readTree(json);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend")) {
            modules.put(name, 1);
        }
        return new RunManifest(RUN, SEED, RULES_DIVIDEND, modules,
                Map.of("fixture", "branch-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(COMPANY_OWNER, COMPANY_ACC, "SUP", "CUS", "OPS"), SYM);

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount(ACC_INV, INV_PARTY, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount(ACC_INV2, INV_PARTY, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("OTHER", "other-org", new BigDecimal("0.00"), "独立组织"),
                new OpeningAccount(COMPANY_ACC, COMPANY_OWNER, new BigDecimal("1000.00"),
                        "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
                new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
                new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY_PARTY, List.of(
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
                new OpeningRegistry.Party(COMPANY_PARTY, PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("other-org", PartyKind.ORGANIZATION, "其它组织"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV_PARTY, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner("other-org",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "other-org"),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV_PARTY, OpeningRegistry.OwnerKind.PERSON,
                        INV_PARTY),
                new OpeningRegistry.PropertyOwner(COMPANY_OWNER,
                        OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY_PARTY),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE, null));
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, COMPANY_PARTY, 200L,
                        new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }
}
