package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema18 真实世界接入第一阶段：同一 WorldRuntime/WorldJournal 上的工程开局、快照、拒绝与重开。
 *
 * <p>测试只断言真实构造与真实日志，不另建运行器；受控拒绝必须在创建日志之前发生，因此断言
 * journal 文件不存在。旧 ≤17 兼容由独立的冻结 17 客户端探针验证，不在本类冒充。</p>
 */
class EngineeringWorldRuntimeTest {

    private static Path journal(String name) throws IOException {
        Path dir = Path.of("target", "engineering-world-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.CompanySpec withSpec(EngineeringRun.CompanySpec base,
            String companyId, String ownerId, String companyAccountId,
            BoardModel.Charter charter) {
        return new EngineeringRun.CompanySpec(companyId, ownerId, companyAccountId,
                base.customerAccountId(), base.supplierAccountId(), base.opsAccountId(),
                base.unitMillis(), base.laborBp(), charter, base.contracts());
    }

    @Test
    void openingHoldsManufacturingAndTwoEngineeringCompanies() throws Exception {
        Path file = journal("opening");
        WorldWebHost.Opened opened = WorldWebScenario.openEngineering(file);
        WorldRuntime.Snapshot snapshot = opened.runtime().snapshot();

        // 开局提交：1 次开户/开局帧 + 3 份冻结工程机会发表 + 1 份公开治理说明发表（Web4 起）。
        assertEquals(5, snapshot.position(), "开局冻结机会与治理说明各占一次提交");
        assertTrue(snapshot.engineering().isPresent(), "schema18 必须持有工程子集");
        EngineeringRun engineering = snapshot.engineering().orElseThrow();
        assertEquals(Set.of(WorldWebScenario.ENGINE_A, WorldWebScenario.ENGINE_B),
                engineering.companyIds());

        // 制造业仍是旧 governance 单例，工程键域严格不交
        assertTrue(snapshot.governance().isPresent());
        assertEquals(WorldWebScenario.COMPANY,
                snapshot.governance().orElseThrow().board().charter().companyPartyId());
        assertFalse(engineering.companyIds().contains(WorldWebScenario.COMPANY));

        // 每公司唯一账户、唯一初始权益，且都取自同一 accounts
        Set<String> accounts = snapshot.accounts().keySet();
        for (EngineeringRun.CompanySpec spec : engineering.config().companies()) {
            for (String account : spec.accountIds()) {
                assertTrue(accounts.contains(account), "缺少工程账户：" + account);
            }
            EngineeringModel.State state = engineering.stateOf(spec.companyPartyId());
            assertNotNull(state);
            assertEquals(spec.engineeringConfig(), state.config());
            assertEquals(snapshot.accounts().get(spec.companyAccountId()).cash(),
                    state.initialEquity());
            assertEquals(spec.charter(), engineering.boardOf(spec.companyPartyId())
                    .board().charter());
        }
        assertEquals(new BigDecimal("5000.00"),
                engineering.stateOf(WorldWebScenario.ENGINE_A).initialEquity());
        assertEquals(new BigDecimal("4000.00"),
                engineering.stateOf(WorldWebScenario.ENGINE_B).initialEquity());

        // A 至少两张冻结合同共享 A 现金，B 一张；开局只冻结条款，不代签、不动现金
        assertEquals(2, engineering.config().specOf(WorldWebScenario.ENGINE_A)
                .contracts().size());
        assertEquals(1, engineering.config().specOf(WorldWebScenario.ENGINE_B)
                .contracts().size());
        assertEquals(0, engineering.stateOf(WorldWebScenario.ENGINE_A).contracts().size());
        assertEquals(new BigDecimal("5000.00"),
                snapshot.accounts().get("ENG-A").cash());

        // 社交全链保留：媒体/采访/证据/社交子集都在同一快照里
        assertTrue(snapshot.media().isPresent());
        assertNotNull(opened.runtime().interviews());
        assertTrue(snapshot.evidence().isPresent());
        assertTrue(snapshot.social().isPresent());
        // 统一公司视图：制造业走旧单例，工程公司走工程子集，未知公司为空
        assertTrue(opened.runtime().boardOfCompany(WorldWebScenario.COMPANY).isPresent());
        assertEquals(engineering.boardOf(WorldWebScenario.ENGINE_A),
                opened.runtime().boardOfCompany(WorldWebScenario.ENGINE_A).orElseThrow());
        assertFalse(opened.runtime().boardOfCompany("no-such-company").isPresent());
        assertTrue(opened.runtime().engineeringStateOf(WorldWebScenario.ENGINE_B).isPresent());
        assertEquals(engineering.stateOf(WorldWebScenario.ENGINE_A),
                opened.runtime().engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow());
        opened.runtime().close();
    }

    @Test
    void sameJournalReopensAndKeepsAllSubsetsAfterRealCashAction() throws Exception {
        Path file = journal("reopen");
        WorldWebHost.Opened first = WorldWebScenario.openEngineering(file);
        WorldRuntime.Snapshot before = first.runtime().snapshot();
        first.runtime().scheduleCapitalFlow("cf-1", "cf-task-1", 1_000L, "fund-a",
                "ACC-FUND", "INV", new BigDecimal("10.00"), 0L, "实验注资：兼容探针");
        first.runtime().advanceTo(1_000L);
        first.runtime().advanceTo(2_000L);
        WorldRuntime.Snapshot afterAction = first.runtime().snapshot();
        assertEquals(new BigDecimal("990.00"), afterAction.accounts().get("ACC-FUND").cash(),
                "delivered=" + afterAction.delivered() + " pending=" + afterAction.pending());
        first.runtime().close();

        WorldWebHost.Opened second = WorldWebScenario.openEngineering(file);
        WorldRuntime.Snapshot reopened = second.runtime().snapshot();
        assertEquals(afterAction, reopened, "同一 journal 重开必须逐子集保持");
        assertEquals(before.engineering(), reopened.engineering());
        assertTrue(reopened.media().isPresent());
        assertNotNull(second.runtime().interviews());
        assertTrue(reopened.evidence().isPresent());
        assertTrue(reopened.social().isPresent());
        assertEquals(WorldWebScenario.COMPANY,
                reopened.governance().orElseThrow().board().charter().companyPartyId());
        second.runtime().close();
    }

    @Test
    void duplicateCompanyAndMultipleTemplatesRejectedBeforeJournal() throws Exception {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);

        Path duplicate = journal("reject-duplicate");
        assertThrows(IllegalArgumentException.class,
                () -> new EngineeringRun.Config("重复公司", List.of(a, b, a)));
        assertFalse(Files.exists(duplicate));

        Path multi = journal("reject-multi-template");
        EngineeringRun.CompanySpec secondTemplate = withSpec(b, WorldWebScenario.ENGINE_A,
                "eng-a2", "ENG-A2", a.charter());
        assertThrows(IllegalArgumentException.class,
                () -> new EngineeringRun.Config("同公司多模板", List.of(a, secondTemplate, b)));
        assertFalse(Files.exists(multi));
    }

    @Test
    void wrongOwnerRejectedBeforeJournal() throws Exception {
        Path file = journal("reject-owner");
        List<CashLedger.OpeningAccount> openings =
                new ArrayList<>(WorldWebScenario.engineeringOpenings());
        openings.replaceAll(opening -> opening.accountId().equals("ENG-A")
                ? new CashLedger.OpeningAccount("ENG-A", "eng-b",
                        opening.cash(), opening.source())
                : opening);
        assertThrows(IllegalArgumentException.class,
                () -> WorldWebScenario.engineeringRuntime(file,
                        WorldWebScenario.engineeringConfig(), openings));
        assertFalse(Files.exists(file), "错误 owner 不得留下半成品日志");
    }

    @Test
    void missingCharterRejectedStructurally() {
        EngineeringRun.CompanySpec a = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_A);
        assertThrows(IllegalArgumentException.class,
                () -> new EngineeringRun.CompanySpec(a.companyPartyId(), a.companyOwnerId(),
                        a.companyAccountId(), a.customerAccountId(), a.supplierAccountId(),
                        a.opsAccountId(), a.unitMillis(), a.laborBp(), null, a.contracts()));
    }

    @Test
    void zeroInitialAmountAllowedAndReopens() throws Exception {
        Path file = journal("zero-amount");
        List<CashLedger.OpeningAccount> openings =
                new ArrayList<>(WorldWebScenario.engineeringOpenings());
        openings.replaceAll(opening -> opening.accountId().equals("ENG-A")
                ? new CashLedger.OpeningAccount("ENG-A", "eng-a", new BigDecimal("0.00"),
                        opening.source())
                : opening);
        WorldRuntime first = WorldWebScenario.engineeringRuntime(file,
                WorldWebScenario.engineeringConfig(), openings);
        assertEquals(new BigDecimal("0.00"),
                first.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow()
                        .initialEquity());
        long position = first.snapshot().position();
        first.close();

        WorldRuntime second = WorldWebScenario.engineeringRuntime(file,
                WorldWebScenario.engineeringConfig(), openings);
        assertEquals(position, second.snapshot().position());
        assertEquals(new BigDecimal("0.00"),
                second.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow()
                        .initialEquity());
        second.close();
    }

    @Test
    void engineeringKeyReusingManufacturingCompanyRejectedBeforeJournal() throws Exception {
        Path file = journal("reject-key-domain");
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);
        BoardModel.Charter manufacturingCharter = new BoardModel.Charter(
                WorldWebScenario.COMPANY,
                List.of(new BoardModel.Seat("ms1", "alice"),
                        new BoardModel.Seat("ms2", "bob"),
                        new BoardModel.Seat("ms3", "carol"),
                        new BoardModel.Seat("ms4", "dave"),
                        new BoardModel.Seat("ms5", "erin")),
                "ms1", BoardModel.QUORUM);
        EngineeringRun.CompanySpec duplicated = new EngineeringRun.CompanySpec(
                WorldWebScenario.COMPANY, "company", "CO", "CUS", "SUP", "OPS",
                b.unitMillis(), b.laborBp(), manufacturingCharter,
                List.of(new EngineeringRun.ContractPlan("M1", new BigDecimal("10.00"),
                        new BigDecimal("5.0000"), 0, 0, 30, 30, List.of(10L))));
        EngineeringRun.Config invalid = new EngineeringRun.Config("键域重叠",
                List.of(duplicated, b));
        assertThrows(IllegalArgumentException.class,
                () -> WorldWebScenario.engineeringRuntime(file, invalid));
        assertFalse(Files.exists(file));
    }

    @Test
    void sharedManufacturingPoolsOpenWithSingleCashAndIsolatedBoards() throws Exception {
        Path file = journal("shared-pools");
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);
        EngineeringRun.CompanySpec shared = new EngineeringRun.CompanySpec(
                a.companyPartyId(), a.companyOwnerId(), a.companyAccountId(), "CUS", "SUP", "OPS",
                a.unitMillis(), a.laborBp(), a.charter(), a.contracts());
        EngineeringRun.Config config = new EngineeringRun.Config("共享制造业外部池",
                List.of(shared, b));
        WorldRuntime runtime = WorldWebScenario.engineeringRuntime(file, config);
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        // 共享池只有一份权成现金，不复制余额；公司账户/owner/章程仍各自唯一
        assertEquals(new BigDecimal("2000.00"), snapshot.accounts().get("CUS").cash());
        assertEquals(new BigDecimal("5000.00"), snapshot.accounts().get("ENG-A").cash());
        assertEquals(new BigDecimal("4000.00"), snapshot.accounts().get("ENG-B").cash());
        assertNotEquals(runtime.boardOfCompany(WorldWebScenario.ENGINE_A).orElseThrow(),
                runtime.boardOfCompany(WorldWebScenario.ENGINE_B).orElseThrow());
        assertNotEquals(runtime.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow(),
                runtime.engineeringStateOf(WorldWebScenario.ENGINE_B).orElseThrow());
        runtime.close();

        WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, config);
        assertEquals(new BigDecimal("2000.00"), reopened.snapshot().accounts().get("CUS").cash());
        reopened.close();
    }

    @Test
    void sharedExternalPoolAllowedButCompanyAccountReuseRejected() {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);
        // 共享制造业外部聚合池是合法的：同一个有限客户账户只有一份现金
        EngineeringRun.CompanySpec shared = new EngineeringRun.CompanySpec(
                a.companyPartyId(), a.companyOwnerId(), a.companyAccountId(), "CUS", "SUP", "OPS",
                a.unitMillis(), a.laborBp(), a.charter(), a.contracts());
        EngineeringRun.Config accepted = new EngineeringRun.Config("共享外部池",
                List.of(shared, b));
        assertEquals("CUS", accepted.specOf(WorldWebScenario.ENGINE_A).customerAccountId());

        // 公司经营账户不得复用，也不得充当外部池
        EngineeringRun.CompanySpec reuseCompany = new EngineeringRun.CompanySpec(
                b.companyPartyId(), b.companyOwnerId(), a.companyAccountId(),
                b.customerAccountId(), b.supplierAccountId(), b.opsAccountId(),
                b.unitMillis(), b.laborBp(), b.charter(), b.contracts());
        assertThrows(IllegalArgumentException.class,
                () -> new EngineeringRun.Config("经营账户复用", List.of(a, reuseCompany)));
        EngineeringRun.CompanySpec poolIsAccount = new EngineeringRun.CompanySpec(
                a.companyPartyId(), a.companyOwnerId(), a.companyAccountId(), "ENG-B",
                a.supplierAccountId(), a.opsAccountId(), a.unitMillis(), a.laborBp(),
                a.charter(), a.contracts());
        assertThrows(IllegalArgumentException.class,
                () -> new EngineeringRun.Config("外部池是经营账户", List.of(poolIsAccount, b)));
    }
}
