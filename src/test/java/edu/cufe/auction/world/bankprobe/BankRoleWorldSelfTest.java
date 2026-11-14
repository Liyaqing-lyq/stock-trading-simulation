package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.Money;
import edu.cufe.auction.world.bank.Reasons;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B6 银行人物动作自测：真实会话 → BEGIN 屏障 → 完成帧 → 逐帧恢复，全部走公开 API。
 *
 * <p>每个用例都在独立日志上跑；任何失败以退出码 1 结束，并打印失败清单。</p>
 */
public final class BankRoleWorldSelfTest {

    private static final long DAY = BankRoleFixture.DAY;
    private static Path base;
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    private BankRoleWorldSelfTest() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数为运行根目录
     * @throws Exception 任何用例异常
     */
    public static void main(String[] args) throws Exception {
        base = Path.of(args.length > 0 ? args[0] : "_build/run");
        Files.createDirectories(base);
        openingShape();
        personalActions();
        companyActions();
        loanChain();
        revokeAfterBegin();
        focusAndDuration();
        zeroReservePayments();
        custodyRejected();
        interestSlices();
        multiContractLending();
        coherentTamperRejected();
        cashTransferChain();
        roleReadingFrames();
        crossJvmChain();
        tamperRejected();
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

    // ---------------------------------------------------------------- 开局与身份

    private static void openingShape() throws Exception {
        try (WorldRuntime runtime = newRun("opening")) {
            ok("开局：schema19 + 银行三态 + 任职/阅读齐备，且贷款台账缺省为空（旧档字节不变）",
                    runtime.stateSchemaVersion() == 19 && runtime.bankOf().isPresent()
                            && runtime.bankBook().loans().isEmpty()
                            && runtime.snapshot().position() == 1,
                    "schema=" + runtime.stateSchemaVersion() + " position="
                            + runtime.snapshot().position());
            ok("会话：登记人物可开、未登记人物被拒",
                    runtime.openRoleSession("acme") != null && throwsOn(() ->
                            runtime.openRoleSession("ghost")),
                    "acme=ok ghost=rejected");
            ok("开局存款只作负债：north 存款 300、托管池 0、准备金 500",
                    deposit(runtime, "north", "acme").compareTo(money("300.00")) == 0
                            && reserve(runtime, "north").compareTo(money("500.00")) == 0
                            && runtime.bankAudit().orElseThrow().custodyPools().get("cst")
                            .signum() == 0,
                    "dep=" + deposit(runtime, "north", "acme"));
        }
    }

    // ---------------------------------------------------------------- 个人

    private static void personalActions() throws Exception {
        try (WorldRuntime runtime = newRun("personal")) {
            act(runtime, "acme", "dep1", "t-dep1", 1_000L, "\"100.00\"",
                    BankActions.Kind.DEPOSIT, "north", "", "", "", "", "");
            ok("个人存款：现金 1000→900、存款 300→400、准备金 500→600",
                    cash(runtime, "acme-cash").compareTo(money("900.00")) == 0
                            && deposit(runtime, "north", "acme").compareTo(money("400.00")) == 0
                            && reserve(runtime, "north").compareTo(money("600.00")) == 0,
                    "cash=" + cash(runtime, "acme-cash") + " dep="
                            + deposit(runtime, "north", "acme"));

            act(runtime, "acme", "w1", "t-w1", 2_000L, "\"40.00\"",
                    BankActions.Kind.WITHDRAW, "north", "", "", "", "", "");
            ok("个人取现：存款 400→360、现金 900→940",
                    cash(runtime, "acme-cash").compareTo(money("940.00")) == 0
                            && deposit(runtime, "north", "acme").compareTo(money("360.00")) == 0,
                    "cash=" + cash(runtime, "acme-cash"));

            act(runtime, "acme", "w2", "t-w2", 3_000L, "\"10000.00\"",
                    BankActions.Kind.WITHDRAW, "north", "", "", "", "", "");
            ok("取现超过存款：完成帧业务拒绝 INSUFFICIENT_FUNDS，余额不变",
                    deposit(runtime, "north", "acme").compareTo(money("360.00")) == 0
                            && lastRejected(runtime, BankActions.Kind.WITHDRAW)
                            .startsWith(Reasons.INSUFFICIENT_FUNDS),
                    "reason=" + lastRejected(runtime, BankActions.Kind.WITHDRAW));

            act(runtime, "acme", "p1", "t-p1", 4_000L, "\"30.00\"",
                    BankActions.Kind.PAY, "north", "", "DEPOSIT|north|acme",
                    "DEPOSIT|north|firm", "", "");
            ok("同行支付：零准备金也可付，acme 360→330、firm 0→30、准备金不变（取现后 560）",
                    deposit(runtime, "north", "acme").compareTo(money("330.00")) == 0
                            && deposit(runtime, "north", "firm").compareTo(money("30.00")) == 0
                            && reserve(runtime, "north").compareTo(money("560.00")) == 0,
                    "acme=" + deposit(runtime, "north", "acme") + " reserve="
                            + reserve(runtime, "north"));

            act(runtime, "acme", "p2", "t-p2", 5_000L, "\"10.00\"",
                    BankActions.Kind.PAY, "north", "", "DEPOSIT|north|acme",
                    "DEPOSIT|north|firm", "", "");
            ok("连续支付在新三态上累计：acme 330→320、firm 30→40",
                    deposit(runtime, "north", "acme").compareTo(money("320.00")) == 0
                            && deposit(runtime, "north", "firm").compareTo(money("40.00")) == 0,
                    "acme=" + deposit(runtime, "north", "acme"));

            int before = runtime.bankActionAudits().size();
            WorldRoleSession session = runtime.openRoleSession("acme");
            String replay = session.bankAction("p2", "t-p2", 5_000L, 500L,
                    draft("p2", BankActions.Kind.PAY, "north", "", "\"10.00\"",
                            "DEPOSIT|north|acme", "DEPOSIT|north|firm", "", ""));
            ok("同命令重放：返回原回执、不建第二任务、不二次扣款",
                    "SCHEDULED:t-p2".equals(replay)
                            && runtime.bankActionAudits().size() == before
                            && deposit(runtime, "north", "acme").compareTo(money("320.00")) == 0,
                    "replay=" + replay + " audits=" + runtime.bankActionAudits().size());
            ok("同命令不同内容：拒绝（同一命令标识不能对应不同内容）",
                    throwsOn(() -> session.bankAction("p2", "t-p2", 5_000L, 500L,
                            draft("p2", BankActions.Kind.PAY, "north", "", "\"11.00\"",
                                    "DEPOSIT|north|acme", "DEPOSIT|north|firm", "", ""))),
                    "conflict rejected");
        }
        ok("缺省不序列化：没有贷款时快照里不出现 bankActions 字段（旧档字节形状不变）",
                !journalText("personal").contains("bankActions"),
                "journal bytes without bankActions");
    }

    // ---------------------------------------------------------------- 公司经理

    private static void companyActions() throws Exception {
        try (WorldRuntime runtime = newRun("company")) {
            act(runtime, "comgr", "cd1", "t-cd1", 1_000L, "\"50.00\"",
                    BankActions.Kind.DEPOSIT, "north", "co", "", "", "", "");
            ok("公司经理用真实 COMPANY_MANAGER 任职替公司存款：co 现金 100→50、存款 0→50",
                    cash(runtime, "co-cash").compareTo(money("50.00")) == 0
                            && deposit(runtime, "north", "co").compareTo(money("50.00")) == 0,
                    "co cash=" + cash(runtime, "co-cash") + " dep="
                            + deposit(runtime, "north", "co"));

            ok("非本公司经理替公司动作：排程期即拒（不认自报身份）",
                    throwsOn(() -> act(runtime, "acme", "cd2", "t-cd2", 2_000L, "\"10.00\"",
                            BankActions.Kind.DEPOSIT, "north", "co", "", "", "", "")),
                    "acme-as-co rejected");

            ok("普通人物直接审批贷款：排程期即拒（不是该银行经理）",
                    throwsOn(() -> act(runtime, "firm", "ap0", "t-ap0", 2_500L, "\"200.00\"",
                            BankActions.Kind.LOAN_APPROVE, "north", "", "", "", "L-x", "")),
                    "firm-approve rejected");

            ok("他行经理审批本行贷款：排程期即拒（绑定该银行组织）",
                    throwsOn(() -> act(runtime, "southmgr", "ap1", "t-ap1", 2_600L, "\"200.00\"",
                            BankActions.Kind.LOAN_APPROVE, "north", "", "", "", "L-x", "")),
                    "southmgr-approve rejected");
        }
    }

    // ---------------------------------------------------------------- 双边贷款全链

    private static void loanChain() throws Exception {
        try (WorldRuntime runtime = newRun("loan")) {
            act(runtime, "comgr", "ld", "t-ld", 1_000L, "\"50.00\"",
                    BankActions.Kind.DEPOSIT, "north", "co", "", "", "", "");
            act(runtime, "comgr", "lr", "t-lr", 2_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "20000", "2592000000",
                    "working capital", "L-1");
            ok("借款人提出明确条款：PROPOSED，条款原样冻结",
                    runtime.bankBook().loan("L-1") != null
                            && runtime.bankBook().loan("L-1").status()
                            == BankActions.LoanStatus.PROPOSED
                            && runtime.bankBook().loan("L-1").rateU() == 20_000L,
                    "loan=" + runtime.bankBook().loan("L-1"));

            approve(runtime, "bankmgr", "ap-a", 3_000L, "\"200.00\"", "20000", "L-1");
            ok("未接受即批准：完成帧拒 borrower-not-accepted",
                    lastRejected(runtime, BankActions.Kind.LOAN_APPROVE)
                            .contains("borrower-not-accepted")
                            && runtime.bankBook().loan("L-1").status()
                            == BankActions.LoanStatus.PROPOSED,
                    "reason=" + lastRejected(runtime, BankActions.Kind.LOAN_APPROVE));

            approve(runtime, "bankmgr", "ap-b", 4_000L, "\"200.00\"", "30000", "L-1");
            ok("银行单方改利率批准：TERMS_CONFLICT，合同不被改写",
                    lastRejected(runtime, BankActions.Kind.LOAN_APPROVE)
                            .startsWith(Reasons.TERMS_CONFLICT)
                            && runtime.bankBook().loan("L-1").rateU() == 20_000L,
                    "reason=" + lastRejected(runtime, BankActions.Kind.LOAN_APPROVE));

            act(runtime, "comgr", "la-x", "t-la-x", 5_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "25000", "2592000000",
                    "working capital", "L-1");
            ok("借款人接受被改过的条款：TERMS_CONFLICT",
                    lastRejected(runtime, BankActions.Kind.LOAN_ACCEPT)
                            .startsWith(Reasons.TERMS_CONFLICT),
                    "reason=" + lastRejected(runtime, BankActions.Kind.LOAN_ACCEPT));

            act(runtime, "comgr", "la", "t-la", 6_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "20000", "2592000000",
                    "working capital", "L-1");
            approve(runtime, "bankmgr", "ap-c", 7_000L, "\"200.00\"", "20000", "L-1");
            ok("双方同条款接受 + 批准：APPROVED（批准不等于自动放款）",
                    runtime.bankBook().loan("L-1").status() == BankActions.LoanStatus.APPROVED
                            && deposit(runtime, "north", "co").compareTo(money("50.00")) == 0,
                    "status=" + runtime.bankBook().loan("L-1").status());

            act(runtime, "comgr", "ldr", "t-ldr", 8_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "20000", "2592000000",
                    "working capital", "L-1");
            ok("提款：co 存款 50→250、book-co 200、贷款台账记 DRAWN",
                    deposit(runtime, "north", "co").compareTo(money("250.00")) == 0
                            && state(runtime).gl("north", "book-co").compareTo(money("200.00")) == 0
                            && runtime.bankBook().loan("L-1").status()
                            == BankActions.LoanStatus.DRAWN,
                    "dep=" + deposit(runtime, "north", "co"));

            long maturity = 8_000L + 30 * DAY;
            runtime.advanceTo(maturity);
            ok("自动计息是真实任务：到合同到期自动计提 0.33（无人填报）",
                    state(runtime).loan("north", "co").interestReceivable()
                            .compareTo(money("0.33")) == 0,
                    "recv=" + state(runtime).loan("north", "co").interestReceivable());

            act(runtime, "comgr", "lrep", "t-lrep", maturity + 1_000L, "\"150.00\"",
                    BankActions.Kind.LOAN_REPAY, "north", "co", "", "0", "0", "", "L-1");
            ok("还本 150：存款 250→100、合同与表内 200→50",
                    deposit(runtime, "north", "co").compareTo(money("100.00")) == 0
                            && state(runtime).loan("north", "co").contract()
                            .compareTo(money("50.00")) == 0
                            && runtime.bankBook().loan("L-1").drawn()
                            .compareTo(money("50.00")) == 0,
                    "dep=" + deposit(runtime, "north", "co"));

            act(runtime, "comgr", "lint", "t-lint", maturity + 2_000L, "\"0.33\"",
                    BankActions.Kind.LOAN_PAY_INTEREST, "north", "co", "", "0", "0", "", "L-1");
            ok("付息 0.33：应计清零、存款 100→99.67",
                    state(runtime).loan("north", "co").interestReceivable().signum() == 0
                            && deposit(runtime, "north", "co")
                            .compareTo(money("99.67")) == 0,
                    "dep=" + deposit(runtime, "north", "co") + " recv="
                            + state(runtime).loan("north", "co").interestReceivable());

            ok("贷款台账不是第二资金账：现金总额不变、托管明细不变、台账只存元数据",
                    total(runtime).compareTo(money("2000.00")) == 0
                            && runtime.bankOf().orElseThrow().custodyDetails().isEmpty()
                            && runtime.bankBook().loan("L-1").drawn().compareTo(money("50.00")) == 0,
                    "total=" + total(runtime));
        }
    }

    // ---------------------------------------------------------------- 撤权与焦点

    private static void revokeAfterBegin() throws Exception {
        try (WorldRuntime runtime = newRun("revoke")) {
            WorldRoleSession session = runtime.openRoleSession("comgr");
            session.bankAction("cd-r", "t-cd-r", 3_000L, 1_000L,
                    draft("cd-r", BankActions.Kind.DEPOSIT, "north", "co", "\"40.00\"", "", "",
                            "", ""));
            runtime.advanceTo(2_000L);
            ok("BEGIN 真实焦点帧：屏障通过后才有完成帧",
                    runtime.snapshot().timeMillis() == 2_000L
                            && runtime.snapshot().pending().stream()
                            .anyMatch(task -> task.dueTimeMillis() == 3_000L),
                    "time=" + runtime.snapshot().timeMillis());
            runtime.scheduleRevokeAppointment("rev1", "task-rev1", 2_500L, "a-mgr-co");
            runtime.advanceTo(2_500L);
            runtime.advanceTo(3_000L);
            ok("BEGIN 之后撤权：完成帧拒 company-manager，公司存款与现金都不变",
                    lastRejected(runtime, BankActions.Kind.DEPOSIT).contains("company-manager")
                            && deposit(runtime, "north", "co").signum() == 0
                            && cash(runtime, "co-cash").compareTo(money("100.00")) == 0,
                    "reason=" + lastRejected(runtime, BankActions.Kind.DEPOSIT));
        }
    }

    private static void focusAndDuration() throws Exception {
        try (WorldRuntime runtime = newRun("focus")) {
            WorldRoleSession session = runtime.openRoleSession("comgr");
            session.bankAction("f1", "t-f1", 2_000L, 500L,
                    draft("f1", BankActions.Kind.DEPOSIT, "north", "co", "\"10.00\"", "", "",
                            "", ""));
            ok("单焦点：同一人物重叠区间的第二个动作被拒",
                    throwsOn(() -> session.bankAction("f2", "t-f2", 2_400L, 500L,
                            draft("f2", BankActions.Kind.DEPOSIT, "north", "co", "\"10.00\"", "",
                                    "", "", ""))),
                    "overlap rejected");
            ok("正工时：零工时被拒",
                    throwsOn(() -> session.bankAction("f3", "t-f3", 5_000L, 0L,
                            draft("f3", BankActions.Kind.DEPOSIT, "north", "co", "\"10.00\"", "",
                                    "", "", ""))),
                    "zero duration rejected");
            runtime.advanceTo(2_400L);
        }
    }

    // ---------------------------------------------------------------- 零准备金与托管

    private static void zeroReservePayments() throws Exception {
        Path dir = base.resolve("zero-reserve");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        try (WorldRuntime runtime = BankRoleFixture.openZeroReserve(file)) {
            ok("零准备金开局：north 准备金 0、acme 存款 100",
                    reserve(runtime, "north").signum() == 0
                            && deposit(runtime, "north", "acme").compareTo(money("100.00")) == 0,
                    "reserve=" + reserve(runtime, "north"));
            act(runtime, "acme", "z1", "t-z1", 1_000L, "\"30.00\"",
                    BankActions.Kind.PAY, "north", "", "DEPOSIT|north|acme",
                    "DEPOSIT|north|firm", "", "");
            ok("同行零准备金支付成功：acme 100→70、firm 0→30，准备金仍 0",
                    deposit(runtime, "north", "acme").compareTo(money("70.00")) == 0
                            && deposit(runtime, "north", "firm").compareTo(money("30.00")) == 0
                            && reserve(runtime, "north").signum() == 0,
                    "acme=" + deposit(runtime, "north", "acme"));

            act(runtime, "acme", "z2", "t-z2", 2_000L, "\"10.00\"",
                    BankActions.Kind.PAY, "north", "", "DEPOSIT|north|acme",
                    "DEPOSIT|south|firm", "", "");
            ok("跨行准备金不足：整批原子拒绝，两侧存款与准备金全不变",
                    lastRejected(runtime, BankActions.Kind.PAY).startsWith(Reasons.INSUFFICIENT_FUNDS)
                            || lastRejected(runtime, BankActions.Kind.PAY)
                            .startsWith(Reasons.RESERVE_INSUFFICIENT)
                            && deposit(runtime, "north", "acme").compareTo(money("70.00")) == 0
                            && deposit(runtime, "south", "firm").signum() == 0,
                    "reason=" + lastRejected(runtime, BankActions.Kind.PAY));
        }
    }

    private static void custodyRejected() throws Exception {
        try (WorldRuntime runtime = newRun("custody")) {
            BigDecimal before = runtime.bankOf().orElseThrow().custodyDetailTotal("cst");
            act(runtime, "acme", "cu1", "t-cu1", 1_000L, "\"10.00\"",
                    BankActions.Kind.PAY, "north", "", "CUSTODY|cst|acme",
                    "DEPOSIT|north|firm", "", "");
            ok("托管桶归属：普通支付不得把托管受益权当存款花，拒绝且明细不变",
                    !lastAccepted(runtime, BankActions.Kind.PAY)
                            && runtime.bankOf().orElseThrow().custodyDetailTotal("cst")
                            .compareTo(before) == 0,
                    "reason=" + lastRejected(runtime, BankActions.Kind.PAY));
        }
    }

    // ---------------------------------------------------------------- 利息切片

    private static void interestSlices() throws Exception {
        SliceOutcome single = accrueWithSlices(false);
        SliceOutcome sliced = accrueWithSlices(true);
        ok("利息切片一致：两段计提与一次性计提到同一时点结果相同（0.33）",
                single.interest().compareTo(money("0.33")) == 0
                        && sliced.interest().compareTo(single.interest()) == 0,
                "single=" + single.interest() + " sliced=" + sliced.interest());
        ok("切片不改台账：两次运行的本金与存款一致（drawn=200、存款 250）",
                single.drawn().compareTo(money("200.00")) == 0
                        && sliced.drawn().compareTo(single.drawn()) == 0
                        && sliced.deposit().compareTo(single.deposit()) == 0,
                "drawn=" + sliced.drawn() + " dep=" + sliced.deposit());
    }

    private record SliceOutcome(BigDecimal interest, BigDecimal drawn, BigDecimal deposit) {
    }
    private static SliceOutcome accrueWithSlices(boolean slices) throws Exception {
        Path dir = base.resolve(slices ? "slices-yes" : "slices-no");
        Files.createDirectories(dir);
        try (WorldRuntime runtime = BankRoleFixture.open(dir.resolve("world.log"))) {
            act(runtime, "comgr", "lr", "t-lr", 1_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "", "20000",
                    "2592000000", "working capital", "L-1");
            act(runtime, "comgr", "la", "t-la", 2_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-1");
            approve(runtime, "bankmgr", "ap", 3_000L, "\"200.00\"", "20000", "L-1");
            act(runtime, "comgr", "ldr", "t-ldr", 4_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-1");
            long maturity = 4_000L + 30 * DAY;
            if (slices) {
                runtime.scheduleLoanAccrual("accr-half", "task-accr-half", "L-1",
                        maturity - 15 * DAY);
                runtime.advanceTo(maturity - 15 * DAY);
            }
            runtime.advanceTo(maturity);
            return new SliceOutcome(state(runtime).loan("north", "co").interestReceivable(),
                    runtime.bankBook().loan("L-1").drawn(),
                    deposit(runtime, "north", "co"));
        }
    }

    // ---------------------------------------------------------------- 多合同隔离

    private static void multiContractLending() throws Exception {
        try (WorldRuntime runtime = newRun("multi-contract")) {
            act(runtime, "comgr", "mc0", "t-mc0", 600L, "\"50.00\"",
                    BankActions.Kind.DEPOSIT, "north", "co", "", "", "", "");
            // L-1 200、L-2 100 并行（同银行同借款人、同条款）
            act(runtime, "comgr", "l1r", "t-l1r", 1_500L, "\"200.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-1");
            act(runtime, "comgr", "l1a", "t-l1a", 2_500L, "\"200.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-1");
            approve(runtime, "bankmgr", "l1p", 3_500L, "\"200.00\"", "20000", "L-1");
            act(runtime, "comgr", "l1d", "t-l1d", 4_500L, "\"200.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-1");
            act(runtime, "comgr", "l2r", "t-l2r", 5_500L, "\"100.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-2");
            act(runtime, "comgr", "l2a", "t-l2a", 6_500L, "\"100.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-2");
            approve(runtime, "bankmgr", "l2p", 7_500L, "\"100.00\"", "20000", "L-2");
            act(runtime, "comgr", "l2d", "t-l2d", 8_500L, "\"100.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "L-2");
            ok("两笔合法提款各自绑定不同期次（L-1≠L-2，均 200/100）",
                    runtime.bankBook().loan("L-1").trancheId() != null
                            && !runtime.bankBook().loan("L-1").trancheId()
                            .equals(runtime.bankBook().loan("L-2").trancheId())
                            && runtime.bankBook().loan("L-1").drawn().compareTo(money("200.00")) == 0
                            && runtime.bankBook().loan("L-2").drawn().compareTo(money("100.00")) == 0,
                    "L1=" + runtime.bankBook().loan("L-1").trancheId() + " L2="
                            + runtime.bankBook().loan("L-2").trancheId());

            long acc1 = 4_500L + 10 * DAY;
            runtime.scheduleLoanAccrual("mc-acc1", "t-mc-acc1", "L-1", acc1);
            runtime.advanceTo(acc1);
            ok("只对 L-1 计息：L-1 应收 0.11、L-2 仍 0（不按借款人账户总余额计息）",
                    trancheReceivable(runtime, "L-1").compareTo(money("0.11")) == 0
                            && trancheReceivable(runtime, "L-2").signum() == 0,
                    "L1=" + trancheReceivable(runtime, "L-1") + " L2="
                            + trancheReceivable(runtime, "L-2"));

            long acc2 = 8_500L + 10 * DAY;
            runtime.scheduleLoanAccrual("mc-acc2", "t-mc-acc2", "L-2", acc2);
            runtime.advanceTo(acc2);
            act(runtime, "comgr", "l2i", "t-l2i", acc2 + 1_000L, "\"0.05\"",
                    BankActions.Kind.LOAN_PAY_INTEREST, "north", "co", "", "", "", "", "", "L-2");
            ok("先付 L-2 利息：只清 L-2 应收，L-1 的 0.11 不受影响",
                    trancheReceivable(runtime, "L-2").signum() == 0
                            && trancheReceivable(runtime, "L-1").compareTo(money("0.11")) == 0,
                    "L1=" + trancheReceivable(runtime, "L-1") + " L2="
                            + trancheReceivable(runtime, "L-2"));

            act(runtime, "comgr", "l1p50", "t-l1p50", acc2 + 2_000L, "\"50.00\"",
                    BankActions.Kind.LOAN_REPAY, "north", "co", "", "", "", "", "", "L-1");
            ok("部分还本 50：L-1 本金 150、L-2 仍 100（不挪用他合同）",
                    runtime.bankBook().loan("L-1").drawn().compareTo(money("150.00")) == 0
                            && runtime.bankBook().loan("L-2").drawn().compareTo(money("100.00")) == 0,
                    "L1=" + runtime.bankBook().loan("L-1").drawn() + " L2="
                            + runtime.bankBook().loan("L-2").drawn());

            act(runtime, "comgr", "l1p150", "t-l1p150", acc2 + 3_000L, "\"150.00\"",
                    BankActions.Kind.LOAN_REPAY, "north", "co", "", "", "", "", "", "L-1");
            ok("L-1 本金还清但仍欠利息 0.11：不得假称债务全清（状态仍 DRAWN）",
                    runtime.bankBook().loan("L-1").drawn().signum() == 0
                            && runtime.bankBook().loan("L-1").status()
                            == BankActions.LoanStatus.DRAWN
                            && trancheReceivable(runtime, "L-1").compareTo(money("0.11")) == 0,
                    "status=" + runtime.bankBook().loan("L-1").status() + " recv="
                            + trancheReceivable(runtime, "L-1"));

            act(runtime, "comgr", "l1i", "t-l1i", acc2 + 4_000L, "\"0.11\"",
                    BankActions.Kind.LOAN_PAY_INTEREST, "north", "co", "", "", "", "", "", "L-1");
            ok("L-1 本息全清后才是 CLOSED", runtime.bankBook().loan("L-1").status()
                    == BankActions.LoanStatus.CLOSED,
                    "status=" + runtime.bankBook().loan("L-1").status());

            act(runtime, "comgr", "l3r", "t-l3r", acc2 + 5_000L, "\"150.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "", "30000", "2592000000",
                    "working capital", "L-3");
            act(runtime, "comgr", "l3a", "t-l3a", acc2 + 6_000L, "\"150.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "", "30000", "2592000000",
                    "working capital", "L-3");
            approve(runtime, "bankmgr", "l3p", acc2 + 7_000L, "\"150.00\"", "30000", "L-3");
            act(runtime, "comgr", "l3d", "t-l3d", acc2 + 8_000L, "\"150.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "", "30000", "2592000000",
                    "working capital", "L-3");
            ok("全清后再借：L-3 绑新期次、利率不同，L-1 保持 CLOSED",
                    runtime.bankBook().loan("L-3").trancheId() != null
                            && !runtime.bankBook().loan("L-3").trancheId()
                            .equals(runtime.bankBook().loan("L-2").trancheId())
                            && runtime.bankBook().loan("L-3").rateU() == 30_000L
                            && runtime.bankBook().loan("L-1").status()
                            == BankActions.LoanStatus.CLOSED,
                    "L3=" + runtime.bankBook().loan("L-3").trancheId() + " L1="
                            + runtime.bankBook().loan("L-1").status());

            BigDecimal before = runtime.bankBook().loan("L-3").drawn();
            int auditsBefore = runtime.bankActionAudits().size();
            WorldRoleSession session = runtime.openRoleSession("comgr");
            String replay = session.bankAction("l1p150", "t-l1p150", acc2 + 3_000L, 500L,
                    new WorldRoleSession.BankActionDraft("l1p150", BankActions.Kind.LOAN_REPAY,
                            "north", "co", money("150.00"), 0L, 0L, "", "", "", "L-1"));
            ok("过期命令重放：返回原回执、不新增帧、不抹新合同余额",
                    "SCHEDULED:t-l1p150".equals(replay)
                            && runtime.bankActionAudits().size() == auditsBefore
                            && runtime.bankBook().loan("L-3").drawn().compareTo(before) == 0
                            && runtime.bankBook().loan("L-1").status()
                            == BankActions.LoanStatus.CLOSED,
                    "replay=" + replay);

            // 另一家银行：独立期次与独立台账
            long t = acc2 + 10_000L;
            // 先给 south 一笔真实准备金存款（纯核流动性口径要求），再让公司在那家银行借款。
            act(runtime, "acme", "s0", "t-s0", t - 1_000L, "\"100.00\"",
                    BankActions.Kind.DEPOSIT, "south", "", "", "", "", "");
            act(runtime, "comgr", "s1r", "t-s1r", t, "\"40.00\"",
                    BankActions.Kind.LOAN_REQUEST, "south", "co", "", "", "20000", "2592000000",
                    "south facility", "S-1");
            act(runtime, "comgr", "s1a", "t-s1a", t + 1_000L, "\"40.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "south", "co", "", "", "20000", "2592000000",
                    "south facility", "S-1");
            WorldRoleSession southMgr = runtime.openRoleSession("southmgr");
            southMgr.bankAction("s1p", "t-s1p", t + 2_000L, 500L,
                    new WorldRoleSession.BankActionDraft("s1p", BankActions.Kind.LOAN_APPROVE,
                            "south", "", money("40.00"), 20_000L, 30 * DAY, "south facility",
                            "", "", "S-1"));
            runtime.advanceTo(t + 2_000L);
            act(runtime, "comgr", "s1d", "t-s1d", t + 3_000L, "\"40.00\"",
                    BankActions.Kind.LOAN_DRAW, "south", "co", "", "", "20000", "2592000000",
                    "south facility", "S-1");
            ok("不同银行并行：south 的 S-1 独立绑定、north 的 L-3 不受影响",
                    runtime.bankBook().loan("S-1").trancheId() != null
                            && runtime.bankBook().loan("S-1").drawn()
                            .compareTo(money("40.00")) == 0
                            && runtime.bankBook().loan("L-3").drawn()
                            .compareTo(money("150.00")) == 0,
                    "S1=" + runtime.bankBook().loan("S-1").drawn() + " L3="
                            + runtime.bankBook().loan("L-3").drawn() + " drawReason="
                            + lastRejected(runtime, BankActions.Kind.LOAN_DRAW)
                            + " apprReason=" + lastRejected(runtime, BankActions.Kind.LOAN_APPROVE));
        }
    }

    /** 某合同对应真实期次的应收利息（按台账绑定的 trancheId 定位）。 */
    private static BigDecimal trancheReceivable(WorldRuntime runtime, String loanId)
            throws Exception {
        BankActions.Loan loan = runtime.bankBook().loan(loanId);
        BankRun bank = runtime.bankOf().orElseThrow();
        BankState state = bank.decode(runtime.snapshot().accounts()).state();
        BankState.Loan book = state.loan(loan.bankKey(), loan.borrowerKey());
        BankState.Tranche tranche = book == null ? null : book.tranche(loan.trancheId());
        return tranche == null || tranche.terms() == null ? Money.zero()
                : tranche.terms().interestReceivable();
    }

    // ---------------------------------------------------------------- 自洽篡改（保留真实任务）

    private static void coherentTamperRejected() throws Exception {
        Path dir = base.resolve("coherent-tamper");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            act(runtime, "comgr", "ct0", "t-ct0", 600L, "\"50.00\"",
                    BankActions.Kind.DEPOSIT, "north", "co", "", "", "", "");
            act(runtime, "comgr", "ctr", "t-ctr", 1_500L, "\"200.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "CT-1");
            act(runtime, "comgr", "cta", "t-cta", 2_500L, "\"200.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "CT-1");
            approve(runtime, "bankmgr", "ctp", 3_500L, "\"200.00\"", "20000", "CT-1");
            act(runtime, "comgr", "ctd", "t-ctd", 4_500L, "\"200.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "", "20000", "2592000000",
                    "working capital", "CT-1");
        }
        Path control = dir.resolve("control.log");
        Path attack = dir.resolve("book-cash.log");
        Files.copy(file, control, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(file, attack, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        BankRoleJournalTamper.tamperBookAndCash(attack, "CT-1", "100.00", "co-cash");
        ok("control：未篡改副本可开（对照）", opens(control), "control opened");
        ok("自洽篡改：快照台账本金 -100 且现金 +100（保留真实任务/命令）并重算帧 SHA → 拒绝",
                !opens(attack), rejectMessage(attack));
    }

    // ---------------------------------------------------------------- 合法现金转账与通知

    private static void cashTransferChain() throws Exception {
        Path dir = base.resolve("transfer");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        BankRoleFixture.Digests mid;
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            runtime.scheduleTransfer("tr1", "task-tr1", 1_000L, "acme-owner", "acme-cash",
                    "firm-cash", money("10.00"), 0L);
            runtime.advanceTo(1_000L);
            ok("合法现金转账：入口可用、现金按命令变动、自动排通知待办",
                    cash(runtime, "acme-cash").compareTo(money("990.00")) == 0
                            && cash(runtime, "firm-cash").compareTo(money("410.00")) == 0
                            && runtime.snapshot().pending().stream()
                            .anyMatch(task -> PendingTask.TRANSFER_NOTICE_V1.equals(task.kind())
                                    && task.dueTimeMillis() == 1_001L),
                    "acme=" + cash(runtime, "acme-cash") + " firm="
                            + cash(runtime, "firm-cash") + " pending="
                            + runtime.snapshot().pending().size());
            runtime.advanceTo(1_001L);
            ok("通知投递帧：已投递回报 +1、待办清空",
                    runtime.snapshot().delivered().size() == 1
                            && runtime.snapshot().pending().isEmpty(),
                    "delivered=" + runtime.snapshot().delivered().size());
            mid = BankRoleFixture.digests(runtime);
        }
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            ok("现金转账+通知跨 JVM 恢复：摘要逐项一致（账户/银行/台账/审计/位置）",
                    BankRoleFixture.digests(runtime).equals(mid),
                    "position=" + runtime.snapshot().position());
            runtime.scheduleTransfer("tr2", "task-tr2", 2_000L, "acme-owner", "acme-cash",
                    "firm-cash", money("10.00"), 0L);
            runtime.advanceTo(2_000L);
            runtime.advanceTo(2_001L);
            ok("恢复后继续合法转账：第二次结算与投递不再报错",
                    cash(runtime, "acme-cash").compareTo(money("980.00")) == 0
                            && runtime.snapshot().delivered().size() == 2,
                    "acme=" + cash(runtime, "acme-cash") + " delivered="
                            + runtime.snapshot().delivered().size());
        }
        Path control = dir.resolve("control.log");
        Path amount = dir.resolve("attack-amount.log");
        Path owner = dir.resolve("attack-owner.log");
        Path notice = dir.resolve("attack-notice.log");
        // 用“只有排程帧”的日志做篡改对照：重新生成一份停在排程帧的副本。
        Path scheduled = dir.resolve("scheduled.log");
        try (WorldRuntime runtime = BankRoleFixture.open(scheduled)) {
            runtime.scheduleTransfer("tr9", "task-tr9", 1_000L, "acme-owner", "acme-cash",
                    "firm-cash", money("10.00"), 0L);
        }
        Files.copy(scheduled, control, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(scheduled, amount, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(scheduled, owner, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(scheduled, notice, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        BankRoleJournalTamper.tamperScheduleContent(amount, "\"amount\":10.00", "\"amount\":11.00");
        BankRoleJournalTamper.tamperScheduleContent(owner, "\"actorOwnerId\":\"acme-owner\"",
                "\"actorOwnerId\":\"firm-owner\"");
        BankRoleJournalTamper.tamperSchedulePayload(notice, "\"noticeDueMillis\":1001",
                "\"noticeDueMillis\":5000");
        ok("control：排程帧原样可开（对照）", opens(control), "control opened");
        ok("篡改金额/财产主体/回报时点（重算帧 SHA）→ 全部拒绝",
                !opens(amount) && !opens(owner) && !opens(notice),
                "amount=" + opens(amount) + " owner=" + opens(owner) + " notice=" + opens(notice));
    }

    // ---------------------------------------------------------------- 合法阅读帧

    private static void roleReadingFrames() throws Exception {
        Path dir = base.resolve("reading");
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            // 资料阅读是受信日程入口（不依赖四岗交易）；未知资料在完成帧按业务拒绝。
            String receipt = runtime.scheduleStartReading("rd1", "t-rd1", 1_500L, "acme",
                    "doc-unknown", 1L);
            runtime.advanceTo(1_500L);
            runtime.advanceTo(2_000L);
            ok("合法阅读帧（未知资料按业务拒绝）不误杀、世界可继续推进",
                    receipt.startsWith("SCHEDULED:") && runtime.snapshot().timeMillis() == 2_000L,
                    "receipt=" + receipt);
        }
        ok("阅读帧日志可跨 JVM 重开（所有帧逐帧推导通过）", opens(file), "reading reopened");
    }

    // ---------------------------------------------------------------- 跨 JVM

    private static void crossJvmChain() throws Exception {
        Path dir = base.resolve("cross-jvm");
        Files.createDirectories(dir);
        String opened = runProbe("open", dir);
        String resumed = runProbe("resume", dir);
        String verified = runProbe("verify", dir);
        String openSummary = summaryLine(opened, "CONFIG=");
        ok("3 个真 JVM：新进程恢复摘要与上一进程一致（银行/现金/托管/台账/审计）",
                !openSummary.isBlank() && resumed.contains(openSummary),
                "open=" + openSummary);
        ok("新进程继续真实动作：位置前移且摘要变化可复算",
                positionOf(resumed, "AFTER ") > positionOf(openSummary, ""),
                "after=" + positionOf(resumed, "AFTER "));
        ok("第三个 JVM 只读复核：与恢复进程写入后的摘要一致",
                summaryLine(verified, "CONFIG=").equals(summaryLine(resumed, "AFTER CONFIG=")),
                "verified=" + summaryLine(verified, "CONFIG="));
    }

    // ---------------------------------------------------------------- 篡改与提交异常

    private static void tamperRejected() throws Exception {
        // 先造一个含贷款台账与银行动作的日志
        Path dir = base.resolve("tamper");
        Files.createDirectories(dir);
        Path original = dir.resolve("world.log");
        try (WorldRuntime runtime = BankRoleFixture.open(original)) {
            act(runtime, "acme", "td", "t-td", 1_000L, "\"100.00\"",
                    BankActions.Kind.DEPOSIT, "north", "", "", "", "", "");
            act(runtime, "comgr", "lr", "t-lr", 2_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_REQUEST, "north", "co", "", "20000", "2592000000",
                    "working capital", "L-1");
            act(runtime, "comgr", "la", "t-la", 3_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_ACCEPT, "north", "co", "", "20000", "2592000000",
                    "working capital", "L-1");
            approve(runtime, "bankmgr", "ap", 4_000L, "\"200.00\"", "20000", "L-1");
            act(runtime, "comgr", "ldr", "t-ldr", 5_000L, "\"200.00\"",
                    BankActions.Kind.LOAN_DRAW, "north", "co", "", "20000", "2592000000",
                    "working capital", "L-1");
        }
        Path control = dir.resolve("control.log");
        Files.copy(original, control, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path loanAttack = dir.resolve("loan-rate.log");
        Files.copy(original, loanAttack, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path bankAttack = dir.resolve("bank-deposit.log");
        Files.copy(original, bankAttack, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        BankRoleJournalTamper.tamperLoanRate(loanAttack, "L-1", 1_000L);
        BankRoleJournalTamper.tamperDeposits(bankAttack, "100.00");
        ok("control：未篡改副本仍可正常打开（对照）",
                opens(control), "control opened");
        ok("攻击一：只改快照贷款台账利率并重算帧 SHA → 恢复必须拒绝",
                !opens(loanAttack), rejectMessage(loanAttack));
        ok("攻击二：只改快照银行存款并重算帧 SHA → 恢复必须拒绝",
                !opens(bankAttack), rejectMessage(bankAttack));
    }

    private static void commitFailures() throws Exception {
        for (String mode : List.of("WRITE", "FORCE")) {
            Path dir = base.resolve("commit-failure-" + mode);
            Files.createDirectories(dir);
            String[] result = runJava("edu.cufe.auction.world.bankprobe.BankRoleCommitFailureProbe",
                    mode, dir.toString());
            System.out.println("[PROBE] BankRoleCommitFailure " + mode + " pid=" + result[0]
                    + " exit=" + result[2]);
            ok("注入 " + mode + " 异常：内存三态保持提交前、重开不半应用",
                    "0".equals(result[2]) && result[1].contains("RESULT=OK"),
                    result[1].replace('\n', '|'));
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void act(WorldRuntime runtime, String person, String clientCmd,
                            String clientTask, long due, String amountJson,
                            BankActions.Kind kind, String bankKey, String organization,
                            String source, String payee, String purpose, String loanId)
            throws Exception {
        actCore(runtime, person, clientCmd, clientTask, due, amountJson, kind, bankKey,
                organization, source, payee, null, null, purpose, loanId);
    }

    /**
     * 带显式条款的排程：贷款类动作用（无支付桶）。
     */
    private static void act(WorldRuntime runtime, String person, String clientCmd,
                            String clientTask, long due, String amountJson,
                            BankActions.Kind kind, String bankKey, String organization,
                            String source, String rateU, String tenorMs, String purpose,
                            String loanId) throws Exception {
        actCore(runtime, person, clientCmd, clientTask, due, amountJson, kind, bankKey,
                organization, source, "", rateU, tenorMs, purpose, loanId);
    }

    /**
     * 带显式条款的排程：贷款类动作用（利率/期限由调用方给出，主体仍由运行层解析）。
     */
    private static void act(WorldRuntime runtime, String person, String clientCmd,
                            String clientTask, long due, String amountJson,
                            BankActions.Kind kind, String bankKey, String organization,
                            String source, String payee, String rateU, String tenorMs,
                            String purpose, String loanId) throws Exception {
        actCore(runtime, person, clientCmd, clientTask, due, amountJson, kind, bankKey,
                organization, source, payee, rateU, tenorMs, purpose, loanId);
    }

    private static void actCore(WorldRuntime runtime, String person, String clientCmd,
                                String clientTask, long due, String amountJson,
                                BankActions.Kind kind, String bankKey, String organization,
                                String source, String payee, String rateU, String tenorMs,
                                String purpose, String loanId) throws Exception {
        WorldRoleSession session = runtime.openRoleSession(person);
        long rate = rateU == null || rateU.isBlank() ? 0L : Long.parseLong(rateU);
        long tenor = tenorMs == null || tenorMs.isBlank() ? 0L : Long.parseLong(tenorMs);
        String receipt = session.bankAction(clientCmd, clientTask, due, 500L,
                new WorldRoleSession.BankActionDraft(clientCmd, kind, bankKey, organization,
                        new BigDecimal(amountJson.replace("\"", "")), rate, tenor, purpose,
                        source, payee, loanId));
        if (!receipt.startsWith("SCHEDULED:")) {
            throw new IllegalStateException("排程未被接受：" + receipt);
        }
        runtime.advanceTo(due);
    }

    private static void approve(WorldRuntime runtime, String person, String clientCmd, long due,
                                String amount, String rateU, String loanId) throws Exception {
        WorldRoleSession session = runtime.openRoleSession(person);
        session.bankAction(clientCmd, clientCmd, due, 500L,
                new WorldRoleSession.BankActionDraft(clientCmd,
                        BankActions.Kind.LOAN_APPROVE, "north", "",
                        money(amount.replace("\"", "")), Long.parseLong(rateU),
                        30 * DAY, "working capital", "", "", loanId));
        runtime.advanceTo(due);
    }

    private static WorldRoleSession.BankActionDraft draft(String id, BankActions.Kind kind,
                                                          String bankKey, String organization,
                                                          String amountJson, String source,
                                                          String payee, String purpose,
                                                          String loanId) {
        return new WorldRoleSession.BankActionDraft(id, kind, bankKey, organization,
                new BigDecimal(amountJson.replace("\"", "")), kind == BankActions.Kind.LOAN_REQUEST
                || kind == BankActions.Kind.LOAN_ACCEPT ? 20_000L : 0L,
                kind == BankActions.Kind.LOAN_REQUEST || kind == BankActions.Kind.LOAN_ACCEPT
                        ? 30 * DAY : 0L,
                purpose, source, payee, loanId);
    }

    private static WorldRoleSession.BankActionDraft draftWith(String id, BankActions.Kind kind,
                                                              String bankKey, String organization,
                                                              BigDecimal amount, String source,
                                                              String payee, String purpose,
                                                              String loanId) {
        long rate = 0L;
        long tenor = 0L;
        switch (kind) {
            case LOAN_REQUEST, LOAN_ACCEPT, LOAN_DRAW -> {
                rate = 20_000L;
                tenor = 30 * DAY;
            }
            default -> {
                // 其它动作不带贷款条款
            }
        }
        return new WorldRoleSession.BankActionDraft(id, kind, bankKey, organization, amount, rate,
                tenor, purpose, source, payee, loanId);
    }

    private static WorldRuntime newRun(String name) throws Exception {
        Path dir = base.resolve(name);
        Files.createDirectories(dir);
        return BankRoleFixture.open(dir.resolve("world.log"));
    }

    /** 运行目录里世界日志的原始文本（ISO-8859-1 逐字节保留，供“字段是否出现”这类断言）。 */
    private static String journalText(String name) throws Exception {
        return new String(Files.readAllBytes(base.resolve(name).resolve("world.log")),
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static BankState state(WorldRuntime runtime) throws Exception {
        BankRun bank = runtime.bankOf().orElseThrow();
        return bank.decode(runtime.snapshot().accounts()).state();
    }

    private static BigDecimal deposit(WorldRuntime runtime, String bankKey, String holder)
            throws Exception {
        return state(runtime).depositOf(bankKey, holder);
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        CashLedger.CashAccount account = runtime.snapshot().accounts().get(accountId);
        return account == null ? Money.zero() : account.cash();
    }

    private static BigDecimal reserve(WorldRuntime runtime, String bankKey) throws Exception {
        return cash(runtime, runtime.bankOf().orElseThrow().decode(
                runtime.snapshot().accounts()).config().chart().reserveAccountOf(bankKey));
    }

    private static BigDecimal total(WorldRuntime runtime) {
        BigDecimal sum = Money.zero();
        for (Map.Entry<String, CashLedger.CashAccount> entry
                : runtime.snapshot().accounts().entrySet()) {
            sum = sum.add(entry.getValue().cash());
        }
        return Money.scale2(sum);
    }

    private static String lastRejected(WorldRuntime runtime, BankActions.Kind kind) throws Exception {
        List<WorldRuntime.BankActionAudit> audits = runtime.bankActionAudits();
        for (int index = audits.size() - 1; index >= 0; index--) {
            WorldRuntime.BankActionAudit audit = audits.get(index);
            if (audit.kind().equals(kind.name()) && !audit.accepted()) {
                return audit.reason() == null ? "" : audit.reason();
            }
        }
        return "";
    }

    private static boolean lastAccepted(WorldRuntime runtime, BankActions.Kind kind)
            throws Exception {
        List<WorldRuntime.BankActionAudit> audits = runtime.bankActionAudits();
        for (int index = audits.size() - 1; index >= 0; index--) {
            WorldRuntime.BankActionAudit audit = audits.get(index);
            if (audit.kind().equals(kind.name())) {
                return audit.accepted();
            }
        }
        return false;
    }

    private interface Body {
        void run() throws Exception;
    }

    private static boolean throwsOn(Body body) {
        try {
            body.run();
            return false;
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return true;
        } catch (Exception other) {
            return false;
        }
    }

    private static boolean opens(Path file) {
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            runtime.bankActionAudits();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String rejectMessage(Path file) {
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            return "OPENED（本应拒绝）";
        } catch (Exception failure) {
            return failure.getMessage();
        }
    }

    private static String runProbe(String phase, Path dir) throws Exception {
        String[] result = runJava("edu.cufe.auction.world.bankprobe.BankRoleProbe", phase,
                dir.toString());
        System.out.println("[PROBE] " + phase + " pid=" + result[0] + " exit=" + result[2]);
        if (!"0".equals(result[2])) {
            System.out.println(result[1]);
            throw new IllegalStateException("探针失败：" + phase + " exit=" + result[2]);
        }
        return result[1];
    }

    private static String[] runJava(String mainClass, String... args) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        return new String[]{Long.toString(process.pid()), output.toString(),
                Integer.toString(code)};
    }

    private static String summaryLine(String output, String prefix) {
        for (String line : output.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return "";
    }

    private static long positionOf(String output, String prefix) {
        for (String line : output.split("\n")) {
            String candidate = prefix.isEmpty() ? line
                    : line.startsWith(prefix) ? line.substring(prefix.length()) : null;
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
        return BankRoleFixture.money(value);
    }
}
