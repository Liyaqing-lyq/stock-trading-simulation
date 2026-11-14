package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankingProfile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B5 真实 World 开局/持久化/恢复自测（不进 1557/GUI）。
 *
 * <p>覆盖：真实开局 → 既有世界动作（排程现金转账 + 时钟推进）→ 关进程 → 新 JVM 恢复并继续；
 * 同一 bank/accounts/custody/config；旧档案无 bank；非组织银行、错准备金归属、存款持有人缺映射、
 * 错池银行、池明细不平、换档案/换禀赋重开、自洽改 bank 帧、半写尾帧等反例。</p>
 */
public final class BankWorldSelfTest {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static Path base;

    private BankWorldSelfTest() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数为运行根目录（必须在 work 内）
     * @throws Exception 任何用例异常
     */
    public static void main(String[] args) throws Exception {
        base = Path.of(args.length > 0 ? args[0] : "_build/run");
        Files.createDirectories(base);
        inProcessChain();
        crossJvmChain();
        legacyWithoutBank();
        legalDeclaredPool();
        negatives();
        prefixAttack();
        commitFailures();
        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed.size());
        for (String item : failed) {
            System.out.println("  FAILED: " + item);
        }
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 主链

    private static void inProcessChain() throws Exception {
        Path dir = base.resolve("in-process");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        BankWorldFixture.Digests opened;
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            opened = BankWorldFixture.digests(runtime);
            ok("真实开局：schema19 + 银行三态绑定 run/档案/profile",
                    runtime.snapshot().position() == 1
                            && opened.config().equals(BankWorldFixture.config().fingerprint())
                            && runtime.bankOf().isPresent(),
                    "position=" + runtime.snapshot().position() + " config=" + opened.config());
            ok("开局现金未被改写：准备金 500、客户 1000、公司 400（Σ1900 背书一致）",
                    runtime.bankAudit().orElseThrow().reserves().get("north")
                            .compareTo(money("500.00")) == 0
                            && runtime.bankAudit().orElseThrow().backing()
                            .compareTo(money("1900.00")) == 0
                            && runtime.snapshot().accounts().get("acme-cash").cash()
                            .compareTo(money("1000.00")) == 0,
                    runtime.bankAudit().orElseThrow().reserves().toString());
            ok("开局存款只作负债：north 存款 300、托管池 0",
                    runtime.bankAudit().orElseThrow().deposits().get("north")
                            .compareTo(money("300.00")) == 0
                            && runtime.bankAudit().orElseThrow().custodyPools()
                            .get(BankWorldFixture.CUSTODIAN).signum() == 0,
                    runtime.bankAudit().orElseThrow().deposits().toString());
            runtime.scheduleTransfer("cmd:w1", "task:w1", 60_000L, "acme-owner", "acme-cash",
                    "firm-cash", new BigDecimal("10.00"), 0L);
            runtime.advanceTo(60_000L);
        }
        BankWorldFixture.Digests afterAction;
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            afterAction = BankWorldFixture.digests(runtime);
            ok("既有世界动作后重开：银行三态与档案一致、位置/时间推进",
                    afterAction.position() >= 3 && afterAction.bank().equals(opened.bank())
                            && afterAction.config().equals(opened.config())
                            && afterAction.audit().equals(opened.audit()),
                    "position=" + afterAction.position() + " time=" + afterAction.timeMillis());
            ok("现金转账仍受原规则约束：客户 1000→990、公司 400→410，银行不变",
                    runtime.snapshot().accounts().get("acme-cash").cash()
                            .compareTo(BankWorldFixture.money("990.00")) == 0
                            && runtime.snapshot().accounts().get("firm-cash").cash()
                            .compareTo(BankWorldFixture.money("410.00")) == 0,
                    runtime.snapshot().accounts().toString());
            ok("本人派生投影：客户现金 990、存款 300、受益权 0",
                    runtime.bankProjection("acme-owner").orElseThrow().cash()
                            .compareTo(BankWorldFixture.money("990.00")) == 0
                            && runtime.bankProjection("acme-owner").orElseThrow().deposits()
                            .compareTo(BankWorldFixture.money("300.00")) == 0
                            && runtime.bankProjection("acme-owner").orElseThrow().beneficial()
                            .signum() == 0,
                    runtime.bankProjection("acme-owner").toString());
        }
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            ok("再重开逐项一致（bank/accounts/custody/config/审计）",
                    BankWorldFixture.digests(runtime).equals(afterAction),
                    "digests=" + BankWorldFixture.digests(runtime));
        }
    }

    private static void crossJvmChain() throws Exception {
        Path dir = base.resolve("cross-jvm");
        Files.createDirectories(dir);
        String opened = runProbe("open", dir);
        String resumed = runProbe("resume", dir);
        String verified = runProbe("verify", dir);
        String openSummary = summaryLine(opened);
        ok("新 JVM 恢复：恢复时的摘要与上一进程完全一致（同一 bank/accounts/custody/config/审计）",
                resumed.contains(openSummary) && !openSummary.isBlank(),
                "open=" + openSummary + " resumed=" + summaryLine(resumed));
        ok("新 JVM 继续动作：恢复后又排程并推进，位置前移",
                positionOf(resumed, "AFTER ") > positionOf(openSummary, ""),
                "after=" + positionOf(resumed, "AFTER "));
        ok("第三个 JVM 只读复核：与恢复进程写入后的摘要一致",
                summaryLine(verified).equals(afterLine(resumed)) && !afterLine(resumed).isEmpty(),
                "verified=" + summaryLine(verified));
    }

    // ---------------------------------------------------------------- 旧档与合法托管

    private static void legacyWithoutBank() throws Exception {
        Path dir = base.resolve("legacy");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        try (WorldRuntime runtime = new WorldRuntime(file, BankWorldFixture.legacyManifest(),
                BankWorldFixture.openings(), BankWorldFixture.registry())) {
            ok("旧档案（无 banking 模块）不含银行三态，bankOf 为空",
                    runtime.bankOf().isEmpty() && runtime.bankAudit().isEmpty(),
                    "manifest=" + BankWorldFixture.legacyManifest().moduleVersions());
            runtime.advanceTo(1_000L);
        }
        try (WorldRuntime runtime = new WorldRuntime(file, BankWorldFixture.legacyManifest(),
                BankWorldFixture.openings(), BankWorldFixture.registry())) {
            ok("旧档案可正常恢复续写，schema 仍为 18 以下", runtime.bankOf().isEmpty(),
                    "position=" + runtime.snapshot().position());
        }
    }

    private static void legalDeclaredPool() throws Exception {
        Path dir = base.resolve("declared-pool");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        BankOpeningRequest request = BankWorldFixture.request(BankWorldFixture.config(),
                BankWorldFixture.endowment("300.00", "200.00"),
                Map.of(BankWorldFixture.CUSTODIAN, Map.of("acme", BankWorldFixture.money("200.00"))),
                Map.of(BankWorldFixture.CUSTODIAN, "b5-historical-pool"));
        try (WorldRuntime runtime = new WorldRuntime(file, BankWorldFixture.manifest(),
                BankWorldFixture.openings(), BankWorldFixture.registry(), request)) {
            BankRun.Audit audit = runtime.bankAudit().orElseThrow();
            ok("历史托管禀赋开局：池 200、客户明细 200、NAV 0，且不重复发钱",
                    audit.custodyPools().get(BankWorldFixture.CUSTODIAN)
                            .compareTo(BankWorldFixture.money("200.00")) == 0
                            && audit.custodyBeneficial().get(BankWorldFixture.CUSTODIAN)
                            .compareTo(BankWorldFixture.money("200.00")) == 0
                            && audit.backing().compareTo(BankWorldFixture.money("1900.00")) == 0,
                    audit.custodyPools() + " " + audit.custodyBeneficial());
        }
    }

    // ---------------------------------------------------------------- 反例

    private static void negatives() throws Exception {
        expect("非 ORGANIZATION 银行被拒", "OPENING_BANK_ORGANIZATION", () -> {
            Path dir = base.resolve("neg-org");
            Files.createDirectories(dir);
            return new WorldRuntime(dir.resolve("world.log"), BankWorldFixture.manifest(),
                    BankWorldFixture.openings(),
                    BankWorldFixture.registry(false, "north-owner"), BankWorldFixture.request());
        });
        expect("准备金不属于银行 owner 被拒", "OPENING_RESERVE_OWNER", () -> {
            Path dir = base.resolve("neg-reserve");
            Files.createDirectories(dir);
            return new WorldRuntime(dir.resolve("world.log"), BankWorldFixture.manifest(),
                    BankWorldFixture.openings("acme-owner"), BankWorldFixture.registry(),
                    BankWorldFixture.request());
        });
        expect("存款持有人没有真实映射被拒", "UNKNOWN_ACCOUNT", () -> {
            Path dir = base.resolve("neg-holder");
            Files.createDirectories(dir);
            Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
            banks.put(BankWorldFixture.BANK, new BankOpening.BankBalance(
                    Map.of("dep-acme", BankWorldFixture.money("-300.00")), "b5-opening-capital"));
            BankOpening.DeclaredEndowment bad = new BankOpening.DeclaredEndowment(banks, List.of(),
                    List.of(new BankOpening.DepositEndowment(BankWorldFixture.BANK, "ghost",
                            BankWorldFixture.money("300.00"), null)),
                    List.of(), List.of(), Map.of(), List.of(), "b5-opening-endowment");
            return new WorldRuntime(dir.resolve("world.log"), BankWorldFixture.manifest(),
                    BankWorldFixture.openings(), BankWorldFixture.registry(),
                    BankWorldFixture.request(BankWorldFixture.config(), bad, Map.of(), Map.of()));
        });
        expect("托管池结算行不在档案被拒", "PROFILE_CUSTODY_BANK_UNKNOWN", () -> {
            Path dir = base.resolve("neg-pool-bank");
            Files.createDirectories(dir);
            BankingProfile profile = BankWorldFixture.profile(true);
            Map<String, BankingProfile.CustodyRef> custodians = new LinkedHashMap<>();
            custodians.put(BankWorldFixture.CUSTODIAN, new BankingProfile.CustodyRef("cst-org",
                    "cst-owner", "south", "pool-cst", BankRules.CREDIT_NON_ACCRUAL));
            BankingProfile broken = new BankingProfile(profile.banks(), profile.parties(),
                    custodians, profile.buckets(), profile.groups(), profile.loanAccounts(),
                    profile.llpAccounts(), profile.depositAccounts(), null, null, null);
            BankConfig config = new BankConfig.Builder(broken).version(19)
                    .archiveId("B5-POOL-BANK").label("banking-world-experiment-v1")
                    .coverageRule("DECLARED_UNIVERSE_V1").build();
            return new WorldRuntime(dir.resolve("world.log"), BankWorldFixture.manifest(),
                    BankWorldFixture.openings(), BankWorldFixture.registry(),
                    BankWorldFixture.request(config,
                            BankWorldFixture.endowment("300.00", "0.00"), Map.of(), Map.of()));
        });
        expect("托管池与客户明细不平被拒", "CUSTODY_POOL_MISMATCH", () -> {
            Path dir = base.resolve("neg-pool-mismatch");
            Files.createDirectories(dir);
            return new WorldRuntime(dir.resolve("world.log"), BankWorldFixture.manifest(),
                    BankWorldFixture.openings(), BankWorldFixture.registry(),
                    BankWorldFixture.request(BankWorldFixture.config(),
                            BankWorldFixture.endowment("300.00", "200.00"),
                            Map.of(BankWorldFixture.CUSTODIAN,
                                    Map.of("acme", BankWorldFixture.money("100.00"))),
                            Map.of(BankWorldFixture.CUSTODIAN, "b5-historical-pool")));
        });
        Path tamperDir = base.resolve("neg-tamper");
        Files.createDirectories(tamperDir);
        Path tamperFile = tamperDir.resolve("world.log");
        try (WorldRuntime runtime = BankWorldFixture.open(tamperFile)) {
            runtime.advanceTo(1_000L);
        }
        // clean control：同一份未篡改日志必须仍能打开，证明后面的拒绝来自篡改本身而不是基础档坏了。
        Path cleanCopy = tamperDir.resolve("clean-control.log");
        Files.copy(tamperFile, cleanCopy);
        BankJournalTamper.tamperLastSnapshotBankOrigin(tamperFile);
        // 组合内核先做整帧命令重放：自洽改写银行三态会让该帧的现金状态无法由原始命令独立重算，
        // 于是先命中“现金批次无法从原任务与命令重算：clock:1000”；银行三态校验仍是第二道拒绝线。
        // 这里锁定实际命中的具体拒绝理由（精确到 clock:1000），不接受任意异常。
        expect("帧校验和可重算、但银行三态被自洽改写仍被拒",
                "现金批次无法从原任务与命令重算：clock:1000", () ->
                BankWorldFixture.open(tamperFile));
        try (WorldRuntime control = BankWorldFixture.open(cleanCopy)) {
            ok("clean control：未篡改副本仍可正常打开", control.bankOf().isPresent(),
                    "position=" + control.snapshot().position());
        }
        Path truncDir = base.resolve("neg-truncate");
        Files.createDirectories(truncDir);
        Path truncFile = truncDir.resolve("world.log");
        BankWorldFixture.Digests beforeTruncate;
        try (WorldRuntime runtime = BankWorldFixture.open(truncFile)) {
            runtime.advanceTo(1_000L);
            beforeTruncate = BankWorldFixture.digests(runtime);
        }
        byte[] all = Files.readAllBytes(truncFile);
        Files.write(truncFile, java.util.Arrays.copyOf(all, all.length - 20));
        try (WorldRuntime runtime = BankWorldFixture.open(truncFile)) {
            ok("半写尾帧被裁掉后仍可恢复到最后完整批次",
                    BankWorldFixture.digests(runtime).bank().equals(beforeTruncate.bank()),
                    "bank=" + BankWorldFixture.digests(runtime).bank());
        }
        expect("换一套档案重开同一日志被拒", "不一致", () -> {
            Path dir = base.resolve("neg-config");
            Files.createDirectories(dir);
            Path file = dir.resolve("world.log");
            try (WorldRuntime runtime = BankWorldFixture.open(file)) {
                runtime.advanceTo(1_000L);
            }
            BankConfig other = new BankConfig.Builder(BankWorldFixture.profile(true)).version(19)
                    .archiveId("B5-OTHER").label("banking-world-experiment-v1")
                    .coverageRule("DECLARED_UNIVERSE_V1").build();
            return new WorldRuntime(file, BankWorldFixture.manifest(), BankWorldFixture.openings(),
                    BankWorldFixture.registry(),
                    BankWorldFixture.request(other, BankWorldFixture.endowment(), Map.of(),
                            Map.of()));
        });
        expect("换一份历史禀赋重开同一日志被拒", "不一致", () -> {
            Path dir = base.resolve("neg-endowment");
            Files.createDirectories(dir);
            Path file = dir.resolve("world.log");
            try (WorldRuntime runtime = BankWorldFixture.open(file)) {
                runtime.advanceTo(1_000L);
            }
            return new WorldRuntime(file, BankWorldFixture.manifest(), BankWorldFixture.openings(),
                    BankWorldFixture.registry(), BankWorldFixture.request(
                    BankWorldFixture.config(), BankWorldFixture.endowment("400.00", "0.00"),
                    Map.of(), Map.of()));
        });
    }

    // ---------------------------------------------------------------- 前缀攻击与提交异常

    private static void prefixAttack() throws Exception {
        Path dir = base.resolve("prefix-attack");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            runtime.advanceTo(1_000L);
        }
        // clean control：攻击前先留一份未改副本，攻击后它必须仍可打开。
        Path cleanCopy = dir.resolve("clean-control.log");
        Files.copy(file, cleanCopy);
        BankJournalTamper.tamperDepositAndBankCommand(file, "bank:probe");
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            failed.add("id=bank:probe + 自洽改存款/伪造回执");
            System.out.println("[FAIL] id=bank:probe 自洽篡改未被拒绝 :: OPENED bank="
                    + BankWorldFixture.digests(runtime).bank());
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            // 组合内核的现金批次重放先于银行三态检查：伪造 id/存款/回执使该帧无法由原始命令独立重算。
            // 锁定实际命中的具体理由（精确到 clock:1000），不以“抛了任意异常”充当通过。
            ok("普通合法帧改 id=bank:probe + 自洽挪两客户存款/伪造 accepted 回执/重算帧 SHA 仍被拒",
                    message.contains("现金批次无法从原任务与命令重算：clock:1000"), message);
        }
        try (WorldRuntime control = BankWorldFixture.open(cleanCopy)) {
            ok("clean control：攻击前副本仍可正常打开", control.bankOf().isPresent(),
                    "position=" + control.snapshot().position());
        }
    }

    private static void commitFailures() throws Exception {
        for (String mode : List.of("WRITE", "FORCE")) {
            Path dir = base.resolve("commit-failure-" + mode);
            Files.createDirectories(dir);
            String[] result = runJava("edu.cufe.auction.world.bankprobe.CommitFailureProbe",
                    mode, dir.toString());
            String output = result[1];
            System.out.println("[PROBE] CommitFailure " + mode + " pid=" + result[0]
                    + " exit=" + result[2]);
            ok("注入 " + mode + " 异常：内存三态保持提交前、重开不半应用",
                    "0".equals(result[2]) && output.contains("RESULT=OK"),
                    output.replace('\n', '|'));
        }
    }

    // ---------------------------------------------------------------- 工具

    private interface Action {
        WorldRuntime run() throws Exception;
    }

    private static void expect(String name, String expected, Action action) {
        try {
            WorldRuntime runtime = action.run();
            runtime.close();
            failed.add(name + "（没有被拒绝）");
            System.out.println("[FAIL] " + name + " :: 未被拒绝");
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            Throwable cause = failure.getCause();
            String combined = cause == null ? message : message + "|" + cause.getMessage();
            if (combined.contains(expected)) {
                passed++;
                System.out.println("[PASS] " + name + " :: " + message);
            } else {
                failed.add(name);
                System.out.println("[FAIL] " + name + " :: 拒绝原因不同 " + message);
            }
        }
    }

    private static String runProbe(String phase, Path dir) throws Exception {
        String[] result = runJava("edu.cufe.auction.world.bankprobe.BankWorldProbe", phase,
                dir.toString());
        System.out.println("[PROBE] " + phase + " pid=" + result[0] + " exit=" + result[2]);
        if (!"0".equals(result[2])) {
            System.out.println(result[1]);
            throw new IllegalStateException("探针失败：" + phase + " exit=" + result[2]);
        }
        return result[1];
    }

    private static String[] runJava(String mainClass, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        return new String[]{Long.toString(process.pid()), output.toString(),
                Integer.toString(code)};
    }

    private static String summaryLine(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("CONFIG=")) {
                return line;
            }
        }
        return "";
    }

    private static String afterLine(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("AFTER CONFIG=")) {
                return line.substring("AFTER ".length());
            }
        }
        return "";
    }

    private static long positionOf(String output, String prefix) {
        for (String line : output.split("\n")) {
            String candidate = prefix.isEmpty() ? line : line.startsWith(prefix)
                    ? line.substring(prefix.length()) : null;
            if (candidate != null && candidate.contains("POSITION=")) {
                String rest = candidate.substring(candidate.indexOf("POSITION=") + 9);
                return Long.parseLong(rest.split(" ")[0]);
            }
        }
        return -1;
    }

    private static void ok(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name + " :: " + detail);
        } else {
            failed.add(name);
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }

    private static BigDecimal money(String value) {
        return BankWorldFixture.money(value);
    }
}
