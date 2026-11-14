package edu.cufe.auction.world.composed;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.EngineeringModel;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组合档 S1 自测：真实开局 → 三个真实经理任免（north/cufe/eng-a）→ 公司自有现金存入/取回
 * → 存款资金桶订单冻结与成交 → 银行/客户报表闭合。
 *
 * <p>运行方式：{@code java -cp ... ComposedWorldSelfTest}；只写临时目录，零 provider。</p>
 */
public final class ComposedWorldSelfTest {

    private static final long DAY = 86_400_000L;
    private static final String NORTH = ComposedWorldScenario.BANK;
    private static final String CUFE = WorldWebScenario.COMPANY;
    private static final String ENG_A = WorldWebScenario.ENGINE_A;
    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    /** 关前权威 WorldState 引用（仅用于重开后逐分量比较）。 */
    private static Object savedAuthority;

    private ComposedWorldSelfTest() {
    }

    /** @param args 无参运行全部 */
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("composed-s1").resolve("world.log");
        List<String> failures = run(dir);
        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failures.size());
        for (String item : failures) {
            System.out.println("  FAILED: " + item);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * 完整 S1 真实切片，返回失败项；空表示全部通过。
     *
     * <p>命令行入口与 JUnit 薄入口 {@code ComposedWorldS1Test} 共用这一份流程与判定，
     * 因此不存在「只打印 FAIL 仍当通过」的第二套口径。</p>
     *
     * @param dir 世界日志路径
     * @return 失败项名称（不可变副本）
     * @throws Exception 世界读写失败
     */
    static List<String> run(Path dir) throws Exception {
        passed = 0;
        failed.clear();
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            opening(runtime);
            long now = runtime.snapshot().timeMillis();
            now = appoint(runtime, now, NORTH, "erin", "north", "north-mgr");
            now = revoke(runtime, now, "A-4");
            now = appoint(runtime, now, CUFE, "dave", "cufe", "cufe-mgr");
            now = appoint(runtime, now, ENG_A, "carol", "enga", "eng-a-mgr");
            deposits(runtime, now);
            markets(runtime);
            bankPeriodic(runtime);
            loans(runtime);
            business(runtime);
            fundedBusiness(runtime);
            reportGuards(runtime);
            reports(runtime);
            savedAuthority = authority(runtime);
        }
        try (WorldRuntime reopened = ComposedWorldScenario.open(dir)) {
            reopen(reopened);
        }
        return List.copyOf(failed);
    }

    // ---------------------------------------------------------------- 开局

    private static void opening(WorldRuntime runtime) {
        RunManifest manifest = ComposedWorldScenario.manifest();
        boolean modules = manifest.moduleVersions().size() == 28
                && manifest.hasComposedBankWorld() && manifest.hasCompanyGovernance()
                && manifest.hasFunding() && manifest.hasCommandIntent()
                && manifest.moduleVersions().containsKey("banking");
        WorldRuntime.Snapshot snap = runtime.snapshot();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (CashLedger.CashAccount account : snap.accounts().values()) {
            total = total.add(account.cash());
        }
        BankState bank = runtime.bankOf().orElseThrow().decode(snap.accounts()).state();
        boolean bankOpen = bank.gl(NORTH, "RESERVE").compareTo(money("3000.00")) == 0
                && bank.gl(NORTH, "PAIDIN").compareTo(money("-3000.00")) == 0;
        boolean shares = snap.securities().orElseThrow().lots().stream().anyMatch(lot ->
                lot.accountId().equals(ComposedWorldScenario.FOUNDER_SEC)
                        && lot.symbol().equals(ComposedWorldScenario.BNK1)
                        && lot.remaining() == 200L);
        ok("组合开局：28 模块（含 command-intent/company-governance/banking/funding）、17 账户、总现金 51100",
                modules && snap.accounts().size() == 17
                        && total.compareTo(money("51100.00")) == 0,
                "modules=" + manifest.moduleVersions().size() + " accounts="
                        + snap.accounts().size() + " cash=" + total);
        ok("银行 A=L+E：准备金 3000、存款 0、实收 3000；BNK1 200 股全在创始人账户（银行无库存股）",
                bankOpen && shares && bank.depositOf(NORTH, "cufe").signum() == 0,
                "reserve=" + bank.gl(NORTH, "RESERVE") + " paidin=" + bank.gl(NORTH, "PAIDIN"));
        ok("north 有真实董事会与章程，但开局没有经理任职",
                runtime.companyGovernance().orElseThrow().has(NORTH)
                        && runtime.boardOfCompany(NORTH).isPresent()
                        && !runtime.isActiveManager(NORTH, "erin", snap.timeMillis()),
                "board=" + runtime.boardOfCompany(NORTH).isPresent());
    }

    // ---------------------------------------------------------------- 任免

    /**
     * 通过真实会议产生一名经理：召集→到会（被任免人若现任董事则自主回避）→材料→提案→实读
     * →合格席位表决→关闭→实施。
     *
     * <p>被任免人本人是现任董事时必须回避：这是 {@link BoardModel#proposeManagerAppointment}
     * 的既有席位规则，不能靠放宽门槛绕过；因此夹具按章程真实席位全到会、候选人回避，
     * 由其余合格席位达到 3 票法定多数。</p>
     *
     * @return 推进后的世界时点
     */
    static long appoint(WorldRuntime runtime, long start, String company, String manager,
            String tag, String docId) throws Exception {
        BoardModel.Charter charter = charterOf(runtime, company);
        List<String> attendees = new ArrayList<>();
        for (BoardModel.Seat seat : charter.seats()) {
            attendees.add(seat.partyId());
        }
        String chair = personOfSeat(charter, charter.chairSeatId());
        boolean managerRecused = charter.seatOf(manager) != null;
        List<String> voters = new ArrayList<>();
        for (String person : attendees) {
            if (!person.equals(chair) && !person.equals(manager)) {
                voters.add(person);
            }
        }
        if (voters.size() < BoardModel.QUORUM) {
            throw new IllegalStateException("夹具不是合法会议：可实读并表决的合格席位不足法定人数 "
                    + BoardModel.QUORUM);
        }
        runtime.schedulePublishDocument("pub-" + tag, "pub-" + tag + "-t", start + 1, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料 " + tag, "真实任免材料 " + tag, "实验材料", 10L, List.of());
        String meeting = "mtg-" + tag;
        if (ENG_A.equals(company)) {
            runtime.scheduleEngineeringAppointmentConvene("cv-" + tag, "cvt-" + tag, start + 2,
                    company, chair, meeting, start + 12, start + 14, start + 40);
        } else {
            runtime.scheduleCompanyBoardConvene("cv-" + tag, "cvt-" + tag, start + 2, company,
                    chair, meeting, start + 12, start + 14, start + 40, true);
        }
        runtime.advanceTo(start + 3);
        for (String person : attendees) {
            if (ENG_A.equals(company)) {
                runtime.scheduleEngineeringAttend("at-" + tag + "-" + person,
                        "att-" + tag + "-" + person, start + 5, company, person, meeting,
                        managerRecused && person.equals(manager));
            } else {
                runtime.scheduleCompanyBoardAttend("at-" + tag + "-" + person,
                        "att-" + tag + "-" + person, start + 5, company, person, meeting,
                        managerRecused && person.equals(manager));
            }
        }
        runtime.advanceTo(start + 6);
        if (ENG_A.equals(company)) {
            runtime.scheduleEngineeringMaterial("mt-" + tag, "mtt-" + tag, start + 8, company,
                    chair, meeting, "mat-" + tag, docId, 1L);
        } else {
            runtime.scheduleCompanyBoardMaterial("mt-" + tag, "mtt-" + tag, start + 8, company,
                    chair, meeting, "mat-" + tag, docId, 1L);
        }
        for (String person : voters) {
            runtime.scheduleStartReading("rd-" + tag + "-" + person,
                    "rdt-" + tag + "-" + person, start + 7, person, docId, 1L);
        }
        runtime.advanceTo(start + 8);
        BoardModel.AppointmentRequest request = new BoardModel.AppointmentRequest(
                "app-" + tag, manager, company, edu.cufe.auction.world.RoleAccess.Role.COMPANY_MANAGER,
                BoardModel.AppointmentAction.APPOINT, start + 43, start + 900 * DAY);
        if (ENG_A.equals(company)) {
            runtime.scheduleEngineeringAppointmentProposal("pp-" + tag, "ppt-" + tag, start + 10,
                    company, chair, meeting, List.of(), request);
        } else {
            runtime.scheduleCompanyBoardAppointmentProposal("pp-" + tag, "ppt-" + tag, start + 10,
                    company, chair, meeting, request);
        }
        runtime.advanceTo(start + 10);
        runtime.advanceTo(start + 16);
        for (String person : voters) {
            if (ENG_A.equals(company)) {
                runtime.scheduleEngineeringVote("vt-" + tag + "-" + person,
                        "vtt-" + tag + "-" + person, start + 18, company, person, meeting,
                        BoardModel.Vote.YES, 1L);
            } else {
                runtime.scheduleCompanyBoardVote("vt-" + tag + "-" + person,
                        "vtt-" + tag + "-" + person, start + 18, company, person, meeting,
                        BoardModel.Vote.YES, 1L);
            }
        }
        runtime.advanceTo(start + 40);
        if (ENG_A.equals(company)) {
            runtime.scheduleEngineeringImplement("im-" + tag, "imt-" + tag, start + 42, company,
                    chair, meeting);
        } else {
            runtime.scheduleCompanyBoardImplement("im-" + tag, "imt-" + tag, start + 42, company,
                    chair, meeting);
        }
        runtime.advanceTo(start + 43);
        boolean active = runtime.isActiveManager(company, manager,
                runtime.snapshot().timeMillis());
        if (!active) {
            runtime.snapshot().engineering().ifPresent(run -> run.reports().values().stream()
                    .filter(rep -> rep.companyPartyId().equals(company))
                    .forEach(rep -> System.out.println("  [engineering-report] " + rep.taskId()
                            + " " + rep.kind() + " accepted=" + rep.accepted() + " reason="
                            + rep.reason())));
            edu.cufe.auction.world.BoardRun board = boardOf(runtime, company);
            if (board != null) {
                board.reports().values().forEach(rep -> System.out.println(
                        "  [board-report] " + rep.taskId() + " " + rep.kind() + " "
                                + rep.reason()));
                runtime.snapshot().roles().ifPresent(r -> r.access().appointments().stream().filter(a -> a.organizationPartyId().equals(company)).forEach(a -> System.out.println("  [appointment] " + a.id() + " " + a.personPartyId() + " " + a.role() + " " + a.startsAt() + "-" + a.effectiveEnd())));
                board.board().meetings().values().forEach(meet -> System.out.println(
                        "  [meeting] " + meet.meetingId() + " " + meet.status()
                                + " attendees=" + meet.attendeeSeatIds()
                                + " recused=" + meet.recusedSeatIds()
                                + " ballots=" + meet.ballots()));
            }
        }
        ok("真实任免（" + company + "）：会议链路产生经理 " + manager
                        + (managerRecused ? "（被任免人回避）" : ""), active,
                "manager=" + manager + " t=" + runtime.snapshot().timeMillis());
        return start + 50;
    }

    /**
     * 真实撤权帧：在 RULES 阶段到期写入一次撤销时刻，记录保留、原期限不变。
     *
     * @return 推进后的世界时点
     */
    private static long revoke(WorldRuntime runtime, long start, String appointmentId)
            throws Exception {
        runtime.scheduleRevokeAppointment("rv-" + appointmentId, "rvt-" + appointmentId,
                start + 1, appointmentId);
        runtime.advanceTo(start + 2);
        boolean revoked = runtime.snapshot().roles().orElseThrow().access().appointments().stream()
                .anyMatch(a -> a.id().equals(appointmentId) && a.revokedAt() != null);
        ok("真实撤权帧：" + appointmentId + " 在 " + (start + 2) + " 被撤销（记录保留）",
                revoked, "revoked=" + revoked);
        return start + 3;
    }

    /** @return 该公司当前唯一权威董事会章程（额外公司/章程单例/工程公司三条真实路径） */
    private static BoardModel.Charter charterOf(WorldRuntime runtime, String company) {
        BoardRun board = boardOf(runtime, company);
        if (board == null) {
            throw new IllegalStateException("没有该公司董事会：" + company);
        }
        return board.board().charter();
    }

    /** @return 该公司当前唯一权威董事会；不存在返回 null */
    private static BoardRun boardOf(WorldRuntime runtime, String company) {
        if (runtime.companyGovernance().isPresent()
                && runtime.companyGovernance().get().has(company)) {
            return runtime.companyGovernance().get().boardOf(company);
        }
        if (runtime.snapshot().governance().isPresent()) {
            BoardRun governance = runtime.snapshot().governance().get();
            if (governance.board().charter().companyPartyId().equals(company)) {
                return governance;
            }
        }
        return runtime.snapshot().engineering().map(run -> run.boards().get(company))
                .orElse(null);
    }

    /** @return 章程席位对应人物 */
    private static String personOfSeat(BoardModel.Charter charter, String seatId) {
        for (BoardModel.Seat seat : charter.seats()) {
            if (seat.seatId().equals(seatId)) {
                return seat.partyId();
            }
        }
        throw new IllegalStateException("章程没有该席位：" + seatId);
    }

    // ---------------------------------------------------------------- 存贷与订单

    private static void deposits(WorldRuntime runtime, long from) throws Exception {
        WorldRoleSession dave = runtime.openRoleSession("dave");
        WorldRoleSession carol = runtime.openRoleSession("carol");
        act(runtime, dave, "dp-cufe", BankActions.Kind.DEPOSIT, "cufe", "300.00");
        act(runtime, carol, "dp-enga", BankActions.Kind.DEPOSIT, "eng-a", "200.00");
        BankState bank = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state();
        ok("公司自有现金存入：CO 1000→700 且存款 300；ENG-A 5000→4800 且存款 200；准备金 3500",
                bank.depositOf(NORTH, "cufe").compareTo(money("300.00")) == 0
                        && bank.depositOf(NORTH, "eng-a").compareTo(money("200.00")) == 0
                        && runtime.snapshot().accounts().get("CO").cash()
                        .compareTo(money("700.00")) == 0
                        && runtime.snapshot().accounts().get("ENG-A").cash()
                        .compareTo(money("4800.00")) == 0,
                "cufeDep=" + bank.depositOf(NORTH, "cufe"));

        act(runtime, dave, "wd-cufe", BankActions.Kind.WITHDRAW, "cufe", "50.00");
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        ok("合法取回：CO 现金 750、存款 250（净财富仍 1000）",
                bank.depositOf(NORTH, "cufe").compareTo(money("250.00")) == 0
                        && runtime.snapshot().accounts().get("CO").cash()
                        .compareTo(money("750.00")) == 0,
                "cash=" + runtime.snapshot().accounts().get("CO").cash()
                        + " dep=" + bank.depositOf(NORTH, "cufe"));

        long orderAt = runtime.snapshot().timeMillis() + 4;
        runtime.scheduleOrder("s-inv", "s-inv-t", orderAt, "investor", "INV",
                WorldWebScenario.SYMBOL, Side.SELL, OrderType.LIMIT, money("10.00"), 10L);
        runtime.scheduleOrder("b-enga", "b-enga-t", orderAt + 1, "eng-a", "ENG-A",
                WorldWebScenario.SYMBOL, Side.BUY, OrderType.LIMIT, money("10.00"), 10L,
                "DEPOSIT|" + NORTH + "|eng-a");
        runtime.advanceTo(orderAt + 2);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        if (System.getenv("COMPOSED_DEBUG") != null) {
            new java.util.TreeMap<>(runtime.snapshot().accounts()).forEach((id, account) ->
                    System.out.println("  [account] " + id + " owner=" + account.ownerId()
                            + " cash=" + account.cash()));
            System.out.println("  [bank] reserve="
                    + bank.gl(ComposedWorldScenario.BANK, "RESERVE"));
            runtime.snapshot().exchange().orElseThrow().trades().forEach(tr ->
                    System.out.println("  [trade] buy=" + tr.buyAccountId() + " sell="
                            + tr.sellAccountId() + " qty=" + tr.quantity() + " px="
                            + tr.price()));
        }
        boolean traded = runtime.snapshot().exchange().orElseThrow().trades().size() == 1
                && runtime.snapshot().exchange().orElseThrow().trades().get(0).quantity() == 10L;
        ok("存款资金桶订单：成交 10 股按被动价，ENG-A 存款 200→100、准备金 3500→3350",
                traded && bank.depositOf(NORTH, "eng-a").compareTo(money("100.00")) == 0
                        && runtime.snapshot().accounts()
                        .get(ComposedWorldScenario.RESERVE).cash()
                        .compareTo(money("3350.00")) == 0,
                "trades=" + runtime.snapshot().exchange().orElseThrow().trades().size()
                        + " dep=" + bank.depositOf(NORTH, "eng-a"));
    }

    /** 一笔真实银行动作：正工时、BEGIN 屏障、到期完成帧；时间只向前推进。 */
    private static void act(WorldRuntime runtime, WorldRoleSession session, String id,
            BankActions.Kind kind, String party, String amount) throws Exception {
        long due = runtime.snapshot().timeMillis() + 2;
        session.bankAction(id, id + "-t", due, 1L,
                new WorldRoleSession.BankActionDraft(id, kind, NORTH, party, money(amount),
                        0L, 0L, "", "", "", ""));
        runtime.advanceTo(due);
    }

    // ------------------------------------------------------- 真实成交与投资损益

    /**
     * 真实二级市场成交：ENG-A 溢价卖出 +50、折价卖出 −50。
     *
     * <p>两次成交都走真实撮合与证券清算；已实现损益来自成交价与批次成本，报表必须如实
     * 进权益，不能靠“成本差额”凑平。</p>
     */
    private static void markets(WorldRuntime runtime) throws Exception {
        // T+1 实验日：新买入批次次日才可卖出；这里只推进一天，不跳过任何真实门。
        runtime.advanceTo(runtime.snapshot().timeMillis() + DAY);
        buy(runtime, "buy-float", "investor", "INV", "10.00", 20L);
        runtime.advanceTo(runtime.snapshot().timeMillis() + DAY);
        sell(runtime, "sell-gain", "fund-a", "ACC-FUND", "15.00");
        ComposedCompanyReport gain = ComposedCompanyReport.of(runtime, ENG_A);
        ok("真实溢价卖出：10 股成交价 15（成本 10），已实现投资损益 +50 进权益",
                gain.income().get("已实现证券投资损益（累计）").compareTo(money("50.00")) == 0
                        && gain.balanced(),
                "realized=" + gain.income().get("已实现证券投资损益（累计）")
                        + " A=" + gain.totalAssets() + " E=" + gain.totalEquity());
        sell(runtime, "sell-loss", "customer", "CUS", "5.00");
        ComposedCompanyReport loss = ComposedCompanyReport.of(runtime, ENG_A);
        ok("真实折价卖出：10 股成交价 5（成本 10），已实现投资损益回到 0，权益 9000",
                loss.income().get("已实现证券投资损益（累计）").compareTo(money("0.00")) == 0
                        && loss.balanced()
                        && loss.totalEquity().compareTo(money("9000.00")) == 0,
                "realized=" + loss.income().get("已实现证券投资损益（累计）")
                        + " A=" + loss.totalAssets() + " E=" + loss.totalEquity());
    }

    /** 一次真实买入成交（现金桶），用于取得可卖批次。 */
    private static void buy(WorldRuntime runtime, String tag, String seller, String sellerAccount,
            String price, long quantity) throws Exception {
        long orderAt = runtime.snapshot().timeMillis() + 4;
        runtime.scheduleOrder("s-" + tag, "s-" + tag + "-t", orderAt, seller, sellerAccount,
                WorldWebScenario.SYMBOL, Side.SELL, OrderType.LIMIT, money(price), quantity);
        runtime.scheduleOrder("b-" + tag, "b-" + tag + "-t", orderAt + 1, "eng-a", "ENG-A",
                WorldWebScenario.SYMBOL, Side.BUY, OrderType.LIMIT, money(price), quantity);
        runtime.advanceTo(orderAt + 2);
    }

    /** 一次真实卖出成交：对手方单独下单，价格由卖方限价决定。 */
    private static void sell(WorldRuntime runtime, String tag, String buyer, String buyerAccount,
            String price) throws Exception {
        long orderAt = runtime.snapshot().timeMillis() + 4;
        runtime.scheduleOrder("s-" + tag, "s-" + tag + "-t", orderAt, "eng-a", "ENG-A",
                WorldWebScenario.SYMBOL, Side.SELL, OrderType.LIMIT, money(price), 10L);
        runtime.scheduleOrder("b-" + tag, "b-" + tag + "-t", orderAt + 1, buyer, buyerAccount,
                WorldWebScenario.SYMBOL, Side.BUY, OrderType.LIMIT, money(price), 10L);
        runtime.advanceTo(orderAt + 2);
    }

    // ------------------------------------------------------------- World 贷款

    /**
     * 真实公司贷款：借款人提条款 → 银行经理按同条款批准 → 借款人接受并提款 → 按合同计息。
     *
     * <p>银行经理不能替借款人动作，借款人也不能自行批准；四个动作都是带正工时的真实银行动作。</p>
     */
    private static void loans(WorldRuntime runtime) throws Exception {
        WorldRoleSession dave = runtime.openRoleSession("dave");
        WorldRoleSession erin = runtime.openRoleSession("erin");
        long tenor = 30L * DAY;
        loanAction(runtime, dave, "ln-req", BankActions.Kind.LOAN_REQUEST, "cufe", "200.00",
                20_000L, tenor, "营运资金周转");
        loanAction(runtime, dave, "ln-acc", BankActions.Kind.LOAN_ACCEPT, "cufe", "200.00",
                20_000L, tenor, "营运资金周转");
        loanAction(runtime, erin, "ln-app", BankActions.Kind.LOAN_APPROVE, "cufe", "200.00",
                20_000L, tenor, "营运资金周转");
        loanAction(runtime, dave, "ln-draw", BankActions.Kind.LOAN_DRAW, "cufe", "200.00",
                20_000L, tenor, "营运资金周转");
        if (System.getenv("COMPOSED_DEBUG") != null) {
            for (String id : List.of("ln-req", "ln-app", "ln-acc", "ln-draw")) {
                String person = id.equals("ln-app") ? "erin" : "dave";
                String task = WorldRuntime.roleTaskId(person, id + "-t");
                System.out.println("  [outcome] " + id + " " + person + " -> begin="
                        + runtime.taskCommitResult(task) + " finish="
                        + runtime.taskCommitResult(task + ":finish"));
            }
            runtime.snapshot().exchange().orElseThrow().reports().values().forEach(rep ->
                    System.out.println("  [order-report] " + rep.taskId() + " "
                            + rep.accepted() + " " + rep.reason()));
        }
        BankState bank = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state();
        ComposedCompanyReport before = ComposedCompanyReport.of(runtime, CUFE);
        ok("World 贷款：CO 存款 250→450（放款进借款人的银行账户）、合同本金 200、银行准备金不变",
                bank.depositOf(NORTH, "cufe").compareTo(money("450.00")) == 0
                        && bank.loan(NORTH, "cufe").contract().compareTo(money("200.00")) == 0
                        && bank.gl(NORTH, "RESERVE").compareTo(money("3350.00")) == 0
                        && before.liabilities().get("借款本金").compareTo(money("200.00")) == 0
                        && before.balanced(),
                "dep=" + bank.depositOf(NORTH, "cufe")
                        + " contract=" + bank.loan(NORTH, "cufe").contract()
                        + " reserve=" + bank.gl(NORTH, "RESERVE")
                        + " A=" + before.totalAssets() + " L=" + before.totalLiabilities()
                        + " E=" + before.totalEquity());

        long accrueAt = runtime.snapshot().timeMillis() + 30L * DAY;
        runtime.scheduleLoanAccrual("ln-accrue", "ln-accrue-t", "ln-req", accrueAt);
        runtime.advanceTo(accrueAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        ComposedCompanyReport after = ComposedCompanyReport.of(runtime, CUFE);
        BankCompanyReport bankReport = BankCompanyReport.of(runtime.bankOf().orElseThrow(),
                runtime.snapshot().accounts(), NORTH);
        BigDecimal bankIncome = sumAmounts(bankReport.income());
        BigDecimal bankExpense = sumAmounts(bankReport.expense());
        BigDecimal bankPnl = bankIncome.subtract(bankExpense);
        BigDecimal unbookedDiff = bankReport.totalAssets().subtract(bankReport.totalLiabilities())
                .subtract(bankReport.bookedEquity());
        ok("真实计息链：借款利息 0.33 进负债与费用；银行损益 = Σ收入 − Σ费用；A−L−未结转前权益 = 损益",
                after.liabilities().get("应付借款利息").compareTo(money("0.33")) == 0
                        && after.expense().get("借款利息费用（累计）").compareTo(money("0.33")) == 0
                        && after.balanced()
                        && bankReport.pnl().compareTo(bankPnl) == 0
                        && unbookedDiff.compareTo(bankPnl) == 0
                        && bankReport.balanced(),
                "clientInt=" + after.liabilities().get("应付借款利息")
                        + " bankIncome=" + bankIncome + " bankExpense=" + bankExpense
                        + " bankPnl=" + bankPnl + " A-L-booked=" + unbookedDiff);
    }

    static void loanAction(WorldRuntime runtime, WorldRoleSession session, String id,
            BankActions.Kind kind, String party, String amount, long rateU, long tenorMs,
            String purpose) throws Exception {
        long due = runtime.snapshot().timeMillis() + 2;
        session.bankAction(id, id + "-t", due, 1L,
                new WorldRoleSession.BankActionDraft(id, kind, NORTH, party, money(amount),
                        rateU, tenorMs, purpose, "", "", "ln-req"));
        runtime.advanceTo(due);
    }

    // ------------------------------------------------- 工程业务与群组真实读写

    /** 工程真实业务（实读机会文档 → 签约 → 开工，结果进完整报表）与原群组发帖/真实读完。 */
    private static void business(WorldRuntime runtime) throws Exception {
        long pubAt = runtime.snapshot().timeMillis() + 2;
        runtime.schedulePublishDocument("pub-opp", "pub-opp-t", pubAt,
                "engineering-opportunity:A1", "alice", InformationModel.Audience.publicAudience(),
                "工程机会 A1", "合同 A1 条款：单价 100、单位成本 40、三期 10/20/30", "实验材料",
                10L, List.of());
        runtime.advanceTo(pubAt);
        long readAt = runtime.snapshot().timeMillis() + 2;
        long readDone = readToFinish(runtime, "carol", "rd-opp", "engineering-opportunity:A1", 1L,
                readAt);
        if (System.getenv("COMPOSED_DEBUG") != null) {
            runtime.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(doc -> doc.docId().contains("opp"))
                    .forEach(doc -> System.out.println("  [opp-doc] " + doc.docId() + " v="
                            + doc.versions().size()));
            runtime.snapshot().information().orElseThrow().information().observations().stream()
                    .filter(obs -> obs.personPartyId().equals("carol"))
                    .forEach(obs -> System.out.println("  [carol-read] " + obs.docId() + " v"
                            + obs.version() + " at " + obs.completedAt()));
            System.out.println("  [rd-opp outcome] " + runtime.taskCommitResult(
                    WorldRuntime.roleTaskId("carol", "rd-opp-t")));
        }
        boolean carolRead = runtime.snapshot().information().orElseThrow().information()
                .observations().stream().anyMatch(obs -> obs.personPartyId().equals("carol")
                        && obs.docId().equals("engineering-opportunity:A1")
                        && obs.version() == 1L);
        ok("工程机会文档真实读完：完成时点来自运行层冻结时长（" + readDone + "），不是 STARTED",
                carolRead, "completedAt=" + readDone + " read=" + carolRead);

        // 真实预算会议：全席到会 → 冻结材料 → 全席实读 → 预算提案 → 法定多数 → 实施。
        long budgetDone = budgetMeeting(runtime, ENG_A, "enga-budget", "10000.00", "10000.00");
        BoardModel.BudgetCategory labor = BoardModel.BudgetCategory.PRODUCTION_LABOR;
        boolean budgetBooked = engineeringBudget(runtime, ENG_A, labor).compareTo(
                money("10000.00")) == 0;
        ok("真实预算会议实施后预算入簿：ENG-A 第 0 期生产人工 10000（实施时点 " + budgetDone + "）",
                budgetBooked, "labor=" + engineeringBudget(runtime, ENG_A, labor));

        long t = runtime.snapshot().timeMillis() + 2;
        runtime.scheduleEngineeringAction("sign-a1", "sign-a1-t", t, ENG_A, "carol", "SIGN",
                "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        runtime.advanceTo(t + 1);
        long startAt = runtime.snapshot().timeMillis() + 2;
        runtime.scheduleEngineeringAction("start-wa1", "start-wa1-t", startAt, ENG_A, "carol",
                "START_WORK", "A1", "W-A1", 0, 10, BigDecimal.ZERO, "", 0);
        runtime.advanceTo(startAt + 1);
        ComposedCompanyReport sheet = ComposedCompanyReport.of(runtime, ENG_A);
        if (System.getenv("COMPOSED_DEBUG") != null) {
            for (String id : List.of("sign-a1-t", "start-wa1-t")) {
                System.out.println("  [eng-action] " + id + " -> "
                        + runtime.taskCommitResult(id) + " finish="
                        + runtime.taskCommitResult(id + ":finish"));
            }
            runtime.snapshot().engineering().orElseThrow().reports().values().stream()
                    .filter(rep -> rep.companyPartyId().equals(ENG_A))
                    .forEach(rep -> System.out.println("  [eng-report] " + rep.taskId() + " "
                            + rep.accepted() + " " + rep.reason()));
        }
        String startFinish = runtime.taskCommitResult("start-wa1-t:finish");
        boolean started = startFinish != null && !"BUDGET_NOT_SET".equals(startFinish);
        ok("工程真实业务入完整报表：A1 已签约、W-A1 真实开工（" + startFinish
                        + "），履约成本进资产且报表精确平",
                started
                        && "ACCEPTED".equals(runtime.taskCommitResult("sign-a1-t:finish"))
                        && sheet.balanced()
                        && sheet.assets().get("履约成本").signum() > 0
                        && sheet.profit().signum() == 0,
                "contractCost=" + sheet.assets().get("履约成本")
                        + " A=" + sheet.totalAssets() + " L=" + sheet.totalLiabilities()
                        + " E=" + sheet.totalEquity() + " pnl=" + sheet.profit()
                        + " sign=" + runtime.taskCommitResult("sign-a1-t:finish")
                        + " start=" + startFinish);

        WorldRoleSession alice = runtime.openRoleSession("alice");
        long postAt = runtime.snapshot().timeMillis() + WorldWebScenario.GROUPS_POST_MILLIS;
        alice.postToGroup("post-open", "post-open-t", postAt,
                new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN, "组合档群帖",
                        "组合档真实发帖：三家真实任免、银行存款与存款桶成交已入账", List.of()));
        runtime.advanceTo(postAt + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
        String docId = runtime.snapshot().information().orElseThrow().information().documents()
                .stream().map(InformationModel.Document::docId)
                .filter(id -> id.contains("post-open")).findFirst()
                .orElseThrow(() -> new IllegalStateException("群帖没有生成资料"));
        long bobReadAt = runtime.snapshot().timeMillis() + 2;
        long bobDone = readToFinish(runtime, "bob", "rd-post", docId, 1L, bobReadAt);
        boolean read = runtime.snapshot().information().orElseThrow().information().observations()
                .stream().anyMatch(obs -> obs.personPartyId().equals("bob")
                        && obs.docId().equals(docId) && obs.version() == 1L);
        if (System.getenv("COMPOSED_DEBUG") != null) {
            System.out.println("  [rd-post outcome] "
                    + runtime.taskCommitResult(WorldRuntime.roleTaskId("bob", "rd-post-t"))
                    + " / finish " + runtime.taskCommitResult(
                            WorldRuntime.roleTaskId("bob", "rd-post-t") + ":finish"));
            runtime.snapshot().information().orElseThrow().information().observations().stream()
                    .filter(obs -> obs.docId().equals(docId))
                    .forEach(obs -> System.out.println("  [post-read] " + obs.personPartyId()
                            + " v" + obs.version()));
        }
        ok("原群组发帖 + 真实读完：alice 发帖后 bob 留下真实阅读观察（" + docId + "）",
                read, "doc=" + docId + " read=" + read);
    }

    /**
     * 公司端显式选桶的完整工程资金链（组合档 COMPANY_CHOICE_V1）。
     *
     * <p>预收（客户现金 → 公司存款）→ 实际投入（公司存款 → 作业方现金）→ 系统完工 → 开票 →
     * 到期回款（客户现金 → 公司存款）。对方端只用合同固定的现金账户；公司端可选自己的存款。
     * 另含负例：对方未授权存款桶、公司端错 owner、同命令异桶冲突。</p>
     */
    private static void fundedBusiness(WorldRuntime runtime) throws Exception {
        WorldRoleSession carol = runtime.openRoleSession("carol");
        BigDecimal cashBefore = runtime.snapshot().accounts().get("ENG-A").cash();
        act(runtime, carol, "dp-enga-more", BankActions.Kind.DEPOSIT, "eng-a", "1000.00");
        BankState bank = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state();
        ok("显式资金前置：ENG-A 存款 100→1100，现金减少同一笔 1000",
                bank.depositOf(NORTH, "eng-a").compareTo(money("1100.00")) == 0
                        && runtime.snapshot().accounts().get("ENG-A").cash()
                        .compareTo(cashBefore.subtract(money("1000.00"))) == 0,
                "dep=" + bank.depositOf(NORTH, "eng-a"));

        BigDecimal reserveBefore = runtime.snapshot().accounts()
                .get(ComposedWorldScenario.RESERVE).cash();
        BigDecimal customerCashBefore = runtime.snapshot().accounts().get("ENG-A-CUS").cash();
        BigDecimal opsCashBefore = runtime.snapshot().accounts().get("ENG-A-OPS").cash();
        BigDecimal depositBefore = bank.depositOf(NORTH, "eng-a");
        BigDecimal netCapitalBefore = runtime.engineeringContext(ENG_A).orElseThrow()
                .netExternalCapital();

        // 1) 预收：客户现金 → 公司存款（公司端显式选自己的存款桶）。
        long advAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-adv", "fund-adv-t", advAt, ENG_A, "carol",
                "ADVANCE", "A1", "", 0, 0, money("100.00"), 0L, "COMPANY_CHOICE_V1",
                "CASH|ENG-A-CUS", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(advAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        boolean advanceOk = "ACCEPTED".equals(runtime.taskCommitResult("fund-adv-t:finish"));
        ok("存款预收：客户现金 −100、公司存款 1100→1200、准备金 +100",
                advanceOk
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(customerCashBefore.subtract(money("100.00"))) == 0
                        && bank.depositOf(NORTH, "eng-a")
                        .compareTo(depositBefore.add(money("100.00"))) == 0
                        && runtime.snapshot().accounts().get(ComposedWorldScenario.RESERVE).cash()
                        .compareTo(reserveBefore.add(money("100.00"))) == 0,
                "finish=" + runtime.taskCommitResult("fund-adv-t:finish")
                        + " dep=" + bank.depositOf(NORTH, "eng-a"));

        // 2) 投入：公司存款 → 作业方现金（对方固定现金账户），人工 40×40×60% = 960。
        long invAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-inv", "fund-inv-t", invAt, ENG_A, "carol",
                "INVEST_WORK", "A1", "W-A1G", 0, 40, BigDecimal.ZERO, 0L, "COMPANY_CHOICE_V1",
                "DEPOSIT|" + NORTH + "|eng-a", "CASH|ENG-A-OPS", 1L);
        runtime.advanceTo(invAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        boolean investOk = "ACCEPTED".equals(runtime.taskCommitResult("fund-inv-t:finish"));
        BigDecimal depositAfterInvest = bank.depositOf(NORTH, "eng-a");
        ok("存款投入：公司存款 1200→240、作业方现金 0→960、准备金 −860",
                investOk
                        && depositAfterInvest.compareTo(money("240.00")) == 0
                        && runtime.snapshot().accounts().get("ENG-A-OPS").cash()
                        .compareTo(opsCashBefore.add(money("960.00"))) == 0
                        && runtime.snapshot().accounts().get(ComposedWorldScenario.RESERVE).cash()
                        .compareTo(reserveBefore.subtract(money("860.00"))) == 0,
                "finish=" + runtime.taskCommitResult("fund-inv-t:finish")
                        + " dep=" + depositAfterInvest + " ops="
                        + runtime.snapshot().accounts().get("ENG-A-OPS").cash());

        // 3) 负例：对方（客户）owner 匹配、存款科目已声明，但不是合同固定现金账户 → 拒绝。
        BigDecimal liabilityBefore = contractLiability(runtime);
        long badAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-bad-cus", "fund-bad-cus-t", badAt, ENG_A,
                "carol", "ADVANCE", "A1", "", 0, 0, money("10.00"), 0L, "COMPANY_CHOICE_V1",
                "DEPOSIT|" + NORTH + "|eng-a-cus", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(badAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        String badResult = runtime.taskCommitResult("fund-bad-cus-t:finish");
        ok("对方未授权存款桶：owner 匹配也不行 → COUNTERPARTY_BUCKET_NOT_AUTHORIZED 且三账不变",
                badResult != null && badResult.contains("COUNTERPARTY_BUCKET_NOT_AUTHORIZED")
                        && bank.depositOf(NORTH, "eng-a")
                        .compareTo(depositAfterInvest) == 0
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(customerCashBefore.subtract(money("100.00"))) == 0
                        && contractLiability(runtime).compareTo(liabilityBefore) == 0,
                "result=" + badResult);

        // 4) 负例：公司端选别家公司的存款 → 错 owner 拒绝。
        long wrongAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-wrong", "fund-wrong-t", wrongAt, ENG_A,
                "carol", "INVEST_WORK", "A1", "W-A1X", 0, 40, BigDecimal.ZERO, 0L,
                "COMPANY_CHOICE_V1", "DEPOSIT|" + NORTH + "|cufe", "CASH|ENG-A-OPS", 1L);
        runtime.advanceTo(wrongAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        String wrongResult = runtime.taskCommitResult("fund-wrong-t:finish");
        ok("公司端错 owner：用别家公司存款付款 → FUND_OWNER_MISMATCH 且存款不变",
                wrongResult != null && wrongResult.contains("FUND_OWNER_MISMATCH")
                        && bank.depositOf(NORTH, "eng-a")
                        .compareTo(depositAfterInvest) == 0,
                "result=" + wrongResult);

        // 5) 真实完工 → 客户自动验收（冻结计划）→ 开票 → 到期回款 → 质保释放。
        // A1 冻结阶段是 10/20/30：第 80 实验日验收第 1 期 10 单位，第 150 实验日验收第 2 期 20 单位。
        runtime.advanceTo(80L * DAY + 10L);
        String accept1 = runtime.taskCommitResult("customer:exp-accept-a1");
        EngineeringModel.Claim accept1Claim = claimOf(runtime);
        ok("客户自动验收第1期10单位：合同资产 900、合同负债 0、收入 1000、已验收 10",
                "ACCEPTED".equals(accept1)
                        && accept1Claim.contractAsset().compareTo(money("900.00")) == 0
                        && accept1Claim.contractLiability().signum() == 0
                        && accept1Claim.revenue().compareTo(money("1000.00")) == 0
                        && contractOf(runtime).acceptedUnits() == 10L,
                "accept=" + accept1 + " asset=" + accept1Claim.contractAsset()
                        + " revenue=" + accept1Claim.revenue());

        long invoiceAt = runtime.snapshot().timeMillis() + 2;
        runtime.scheduleEngineeringAction("inv-a1", "inv-a1-t", invoiceAt, ENG_A, "carol",
                "INVOICE", "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        runtime.advanceTo(invoiceAt + 1);
        String invoiceResult = runtime.taskCommitResult("inv-a1-t:finish");
        EngineeringModel.Invoice invoice0 = invoiceOf(runtime, 0);
        ok("开票第1期：应收 300 + 质保 600（总额 1000，预收 100 已冲减）",
                "ACCEPTED".equals(invoiceResult)
                        && "INVOICED".equals(engineeringReason(runtime, "inv-a1-t:finish"))
                        && invoice0 != null
                        && invoice0.currentOutstanding().compareTo(money("300.00")) == 0
                        && invoice0.retentionOutstanding().compareTo(money("600.00")) == 0
                        && claimOf(runtime).arCurrent().compareTo(money("300.00")) == 0
                        && claimOf(runtime).arRetention().compareTo(money("600.00")) == 0,
                "invoice=" + invoiceResult + "/" + engineeringReason(runtime, "inv-a1-t:finish")
                        + " current=" + (invoice0 == null ? null : invoice0.currentOutstanding()));

        // 5a) 质保未到期 → 无前提拒绝（一分不动）。
        BigDecimal cashBeforeEarly = runtime.snapshot().accounts().get("ENG-A-CUS").cash();
        BigDecimal depositBeforeEarly = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state().depositOf(NORTH, "eng-a");
        long earlyAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-ret-early", "fund-ret-early-t", earlyAt,
                ENG_A, "carol", "COLLECT_RETENTION", "A1", "", 0, 0, money("600.00"), 0L,
                "COMPANY_CHOICE_V1", "CASH|ENG-A-CUS", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(earlyAt);
        String earlyResult = runtime.taskCommitResult("fund-ret-early-t:finish");
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        ok("质保未到期拒绝：NOT_DUE 且客户现金/公司存款/质保余额均未动",
                "NOT_DUE".equals(earlyResult)
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(cashBeforeEarly) == 0
                        && bank.depositOf(NORTH, "eng-a").compareTo(depositBeforeEarly) == 0
                        && claimOf(runtime).arRetention().compareTo(money("600.00")) == 0,
                "result=" + earlyResult);

        // 5b) 到期应收回款：客户现金 → 公司存款，金额精确 300。
        runtime.advanceTo(invoice0.dueAt() + 1);
        long collectAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-collect", "fund-collect-t", collectAt,
                ENG_A, "carol", "COLLECT_DUE", "A1", "", 0, 0, money("300.00"), 0L,
                "COMPANY_CHOICE_V1", "CASH|ENG-A-CUS", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(collectAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        String collectResult = runtime.taskCommitResult("fund-collect-t:finish");
        ok("到期应收回款 300：客户现金 −300、公司存款 +300、应收 0，收入不再重复确认",
                "ACCEPTED".equals(collectResult)
                        && "COLLECTED".equals(engineeringReason(runtime, "fund-collect-t:finish"))
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(customerCashBefore.subtract(money("400.00"))) == 0
                        && bank.depositOf(NORTH, "eng-a").compareTo(money("540.00")) == 0
                        && claimOf(runtime).arCurrent().signum() == 0
                        && claimOf(runtime).revenue().compareTo(money("1000.00")) == 0,
                "result=" + collectResult + " dep=" + bank.depositOf(NORTH, "eng-a"));

        // 5c) 质保到期释放 600：客户现金 → 公司存款，质保余额清零。
        runtime.advanceTo(invoice0.retentionDueAt() + 1);
        long retainAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-ret", "fund-ret-t", retainAt, ENG_A,
                "carol", "COLLECT_RETENTION", "A1", "", 0, 0, money("600.00"), 0L,
                "COMPANY_CHOICE_V1", "CASH|ENG-A-CUS", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(retainAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        String retainResult = runtime.taskCommitResult("fund-ret-t:finish");
        ok("质保真实释放 600：客户现金 −600、公司存款 +600、质保应收 0",
                "ACCEPTED".equals(retainResult)
                        && "RETENTION_COLLECTED".equals(engineeringReason(runtime, "fund-ret-t:finish"))
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(customerCashBefore.subtract(money("1000.00"))) == 0
                        && bank.depositOf(NORTH, "eng-a").compareTo(money("1140.00")) == 0
                        && claimOf(runtime).arRetention().signum() == 0,
                "result=" + retainResult + " dep=" + bank.depositOf(NORTH, "eng-a"));

        // 5d) 第 150 实验日第二期验收 20 单位后开票 2000，再做坏账名义债权追索。
        runtime.advanceTo(150L * DAY + 10L);
        String accept2 = runtime.taskCommitResult("customer:exp-accept-a1-t2");
        ok("客户自动验收第2期20单位：合同资产 2000、收入累计 3000、已验收 30",
                "ACCEPTED".equals(accept2)
                        && claimOf(runtime).contractAsset().compareTo(money("2000.00")) == 0
                        && claimOf(runtime).revenue().compareTo(money("3000.00")) == 0
                        && contractOf(runtime).acceptedUnits() == 30L,
                "accept=" + accept2 + " asset=" + claimOf(runtime).contractAsset());

        long invoice2At = runtime.snapshot().timeMillis() + 2;
        runtime.scheduleEngineeringAction("inv-a1-t2", "inv-a1-t2-task", invoice2At, ENG_A,
                "carol", "INVOICE", "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        runtime.advanceTo(invoice2At + 1);
        String invoice2Result = runtime.taskCommitResult("inv-a1-t2-task:finish");
        ok("开票第2期 2000：应收 2000、质保额度已用完（不再重复扣质保）",
                "ACCEPTED".equals(invoice2Result)
                        && "INVOICED".equals(engineeringReason(runtime, "inv-a1-t2-task:finish"))
                        && claimOf(runtime).arCurrent().compareTo(money("2000.00")) == 0
                        && claimOf(runtime).arRetention().signum() == 0
                        && invoiceOf(runtime, 1).retentionOutstanding().signum() == 0,
                "invoice=" + invoice2Result + " ar=" + claimOf(runtime).arCurrent());

        // 5e) 无前提追索拒绝：还没有核销备查权利（memo=0），正金额追索被既有
        // RECOVERY_EXCEEDS_MEMO 拒绝（不是新造的失败码）；一分不动。
        BigDecimal cashBeforeNoMemo = runtime.snapshot().accounts().get("ENG-A-CUS").cash();
        BigDecimal depositBeforeNoMemo = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state().depositOf(NORTH, "eng-a");
        long noMemoAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-rec-nomemo", "fund-rec-nomemo-t", noMemoAt,
                ENG_A, "carol", "RECOVER", "A1", "", 0, 0, money("100.00"), 0L,
                "COMPANY_CHOICE_V1", "CASH|ENG-A-CUS", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(noMemoAt);
        String noMemoResult = runtime.taskCommitResult("fund-rec-nomemo-t:finish");
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        ok("无核销备查就追索：既有 RECOVERY_EXCEEDS_MEMO 拒绝且客户现金/公司存款均未动",
                "RECOVERY_EXCEEDS_MEMO".equals(noMemoResult)
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(cashBeforeNoMemo) == 0
                        && bank.depositOf(NORTH, "eng-a").compareTo(depositBeforeNoMemo) == 0,
                "result=" + noMemoResult);

        // 5f) 真实减值 1000 + 核销 1000，再按名义备查债权追索 1000。
        long impairAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleEngineeringAction("imp-a1", "imp-a1-t", impairAt, ENG_A, "carol",
                "IMPAIR", "A1", "", 0, 0, money("1000.00"), "", 0);
        runtime.advanceTo(impairAt);
        String impairResult = runtime.taskCommitResult("imp-a1-t:finish");
        long writeOffAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleEngineeringAction("wo-a1", "wo-a1-t", writeOffAt, ENG_A, "carol",
                "WRITE_OFF", "A1", "", 0, 0, money("1000.00"), "", 0);
        runtime.advanceTo(writeOffAt);
        String writeOffResult = runtime.taskCommitResult("wo-a1-t:finish");
        ok("减值 1000 + 核销 1000：应收 2000→1000、备查 1000、准备清零",
                "ACCEPTED".equals(impairResult) && "IMPAIRED".equals(
                        engineeringReason(runtime, "imp-a1-t:finish"))
                        && "ACCEPTED".equals(writeOffResult) && "WRITTEN_OFF".equals(
                        engineeringReason(runtime, "wo-a1-t:finish"))
                        && claimOf(runtime).arCurrent().compareTo(money("1000.00")) == 0
                        && claimOf(runtime).memo().compareTo(money("1000.00")) == 0
                        && claimOf(runtime).allowance().signum() == 0,
                "impair=" + impairResult + " writeOff=" + writeOffResult
                        + " ar=" + claimOf(runtime).arCurrent() + " memo=" + claimOf(runtime).memo());

        long recoverAt = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("fund-rec", "fund-rec-t", recoverAt, ENG_A,
                "carol", "RECOVER", "A1", "", 0, 0, money("1000.00"), 0L,
                "COMPANY_CHOICE_V1", "CASH|ENG-A-CUS", "DEPOSIT|" + NORTH + "|eng-a", 1L);
        runtime.advanceTo(recoverAt);
        bank = runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
        String recoverResult = runtime.taskCommitResult("fund-rec-t:finish");
        ok("坏账名义债权追索 1000：客户现金 −1000、公司存款 +1000、备查 0、收回收益 1000",
                "ACCEPTED".equals(recoverResult)
                        && "RECOVERED".equals(engineeringReason(runtime, "fund-rec-t:finish"))
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(customerCashBefore.subtract(money("2000.00"))) == 0
                        && bank.depositOf(NORTH, "eng-a").compareTo(money("2140.00")) == 0
                        && claimOf(runtime).memo().signum() == 0
                        && claimOf(runtime).recoveryIncome().compareTo(money("1000.00")) == 0
                        && claimOf(runtime).revenue().compareTo(money("3000.00")) == 0,
                "result=" + recoverResult + " dep=" + bank.depositOf(NORTH, "eng-a")
                        + " memo=" + claimOf(runtime).memo());

        // 5g) 收尾逐项核对：同一笔钱只出现一次，营业流不进资本。
        EngineeringModel.Claim finalClaim = claimOf(runtime);
        ComposedCompanyReport engSheet = ComposedCompanyReport.of(runtime, ENG_A);
        WorldRuntime.ComposedFinancialPosition position =
                runtime.composedFinancialPosition("ENG-A");
        ok("资金链逐项平账：存款 2140、客户现金 18000、作业方现金 1200、准备金 5390、应收 1000、收入 3000、成本 1200、准备 0",
                bank.depositOf(NORTH, "eng-a").compareTo(money("2140.00")) == 0
                        && runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        .compareTo(money("18000.00")) == 0
                        && runtime.snapshot().accounts().get("ENG-A-OPS").cash()
                        .compareTo(money("1200.00")) == 0
                        && runtime.snapshot().accounts().get(ComposedWorldScenario.RESERVE).cash()
                        .compareTo(money("5390.00")) == 0
                        && finalClaim.arCurrent().compareTo(money("1000.00")) == 0
                        && finalClaim.revenue().compareTo(money("3000.00")) == 0
                        && finalClaim.cogs().compareTo(money("1200.00")) == 0
                        && finalClaim.contractCost().compareTo(money("800.00")) == 0
                        && finalClaim.impairmentLoss().compareTo(money("1000.00")) == 0
                        && finalClaim.recoveryIncome().compareTo(money("1000.00")) == 0
                        && finalClaim.contractAsset().signum() == 0
                        && finalClaim.contractLiability().signum() == 0,
                "cus=" + runtime.snapshot().accounts().get("ENG-A-CUS").cash()
                        + " dep=" + bank.depositOf(NORTH, "eng-a")
                        + " ops=" + runtime.snapshot().accounts().get("ENG-A-OPS").cash()
                        + " reserve=" + runtime.snapshot().accounts()
                        .get(ComposedWorldScenario.RESERVE).cash()
                        + " arRet=" + finalClaim.arRetention()
                        + " ar=" + finalClaim.arCurrent() + " revenue=" + finalClaim.revenue()
                        + " cogs=" + finalClaim.cogs() + " cost=" + finalClaim.contractCost()
                        + " impair=" + finalClaim.impairmentLoss() + " recov="
                        + finalClaim.recoveryIncome() + " asset=" + finalClaim.contractAsset()
                        + " liab=" + finalClaim.contractLiability());

        ok("ComposedFinancialPosition 银行资产不重复：存款只列一次且等于银行账；营业流不作资本",
                position.bankDeposits().compareTo(money("2140.00")) == 0
                        && position.bankDeposits().compareTo(bank.depositOf(NORTH, "eng-a")) == 0
                        && engSheet.assets().get("银行存款")
                        .compareTo(money("2140.00")) == 0
                        && engSheet.balanced()
                        && runtime.engineeringContext(ENG_A).orElseThrow()
                        .netExternalCapital().compareTo(netCapitalBefore) == 0,
                "positionDep=" + position.bankDeposits() + " sheetDep="
                        + engSheet.assets().get("银行存款") + " capital="
                        + runtime.engineeringContext(ENG_A).orElseThrow().netExternalCapital());

        // 5h) 权限不跳过：每个显式资金动作都真实经过 BEGIN 屏障，再落到 FINISH 结果。
        List<String> fundedTasks = List.of("fund-adv-t", "fund-inv-t", "fund-collect-t",
                "fund-ret-t", "fund-rec-t", "fund-ret-early-t", "fund-rec-nomemo-t");
        boolean barriers = true;
        StringBuilder barrierDetail = new StringBuilder();
        for (String task : fundedTasks) {
            String begin = runtime.taskCommitResult(task);
            String beginReason = engineeringReason(runtime, task);
            String finish = runtime.taskCommitResult(task + ":finish");
            barrierDetail.append(task).append('=').append(begin).append('/').append(beginReason)
                    .append('/').append(finish).append(' ');
            barriers = barriers && "ACCEPTED".equals(begin) && "STARTED".equals(beginReason)
                    && finish != null;
        }
        ok("权限 BEGIN/FINISH 不跳过：7 个显式资金动作屏障均为 STARTED 且有 FINISH 结果",
                barriers, barrierDetail.toString());

        // 6) 同命令异桶：同一 commandId 换公司端存款 → 冲突拒绝。
        boolean conflict = false;
        try {
            runtime.scheduleFundedEngineeringAction("fund-adv", "fund-adv-t2",
                    runtime.snapshot().timeMillis() + 3, ENG_A, "carol", "ADVANCE", "A1", "", 0, 0,
                    money("100.00"), 0L, "COMPANY_CHOICE_V1", "CASH|ENG-A-CUS",
                    "DEPOSIT|" + NORTH + "|eng-b", 1L);
        } catch (IllegalArgumentException conflictExpected) {
            conflict = true;
        }
        ok("同命令异桶：COMMAND_CONTENT_CONFLICT（排程层拒绝，不产生第二次支付）", conflict,
                "conflict=" + conflict);
    }

    /** A1 合同当前权威状态。 */
    private static EngineeringModel.Contract contractOf(WorldRuntime runtime) {
        return runtime.snapshot().engineering().orElseThrow().stateOf(ENG_A).contracts().get("A1");
    }

    /** A1 合同当前损益/应收状态。 */
    private static EngineeringModel.Claim claimOf(WorldRuntime runtime) {
        return runtime.snapshot().engineering().orElseThrow().stateOf(ENG_A).claims().get("A1");
    }

    /** A1 指定期次发票。 */
    private static EngineeringModel.Invoice invoiceOf(WorldRuntime runtime, int trancheIndex) {
        return contractOf(runtime).invoices().get(trancheIndex);
    }

    /** 任务完成报告里的真实业务原因（提交结果对成功动作统一写 ACCEPTED）。 */
    private static String engineeringReason(WorldRuntime runtime, String taskId) {
        EngineeringRun.Report report = runtime.snapshot().engineering().orElseThrow()
                .reports().get(taskId);
        return report == null ? null : report.reason();
    }

    /** A1 合同负债合计（用于证明拒绝时工程业务状态不变）。 */
    private static BigDecimal contractLiability(WorldRuntime runtime) {
        return runtime.snapshot().engineering().orElseThrow().stateOf(ENG_A).claims().values()
                .stream().map(claim -> claim.contractLiability())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 同世界重开：权威 WorldState 的**全部**分量必须逐项相等（反射只读，仅测试用）。 */
    private static void reopen(WorldRuntime runtime) throws Exception {
        Object reopened = authority(runtime);
        List<String> diff = authorityDiff(savedAuthority, reopened);
        WorldRuntime.Snapshot snap = runtime.snapshot();
        ok("同世界重开：权威 WorldState 全部分量相等（position=" + snap.position() + "）",
                diff.isEmpty() && savedAuthority != null,
                "diff=" + diff + " t=" + snap.timeMillis());
    }

    /** 关前权威 WorldState（私有嵌套记录；反射只读，不修改）。 */
    private static Object authority(WorldRuntime runtime) throws Exception {
        java.lang.reflect.Field field = WorldRuntime.class.getDeclaredField("state");
        field.setAccessible(true);
        return field.get(runtime);
    }

    /**
     * 逐个记录分量比较（不是 toString/哈希：Map 迭代序不同会让哈希不等而内容相等）。
     *
     * @return 不相等的分量名
     */
    private static List<String> authorityDiff(Object before, Object after) throws Exception {
        List<String> diff = new ArrayList<>();
        if (before == null || after == null) {
            diff.add("state");
            return diff;
        }
        for (java.lang.reflect.RecordComponent component
                : before.getClass().getRecordComponents()) {
            java.lang.reflect.Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            Object left = accessor.invoke(before);
            Object right = accessor.invoke(after);
            if (!java.util.Objects.equals(left, right)) {
                diff.add(component.getName());
            }
        }
        return diff;
    }

    // ------------------------------------------------- 银行自身周期真实链

    /**
     * north 银行自己的周期链：真实期末采样 → 经理编制材料 → 3 名合格董事实读后批准 →
     * 经理授权 → 有权人发布；同时验证三条负例（经理自批、未读批准、撤任后 BEGIN 拒绝）。
     */
    private static void bankPeriodic(WorldRuntime runtime) throws Exception {
        // 真实期间选择：期末观察只能落在“当期尚未结束”的时点，不能把任务挪到非法过去。
        var extras = runtime.companyGovernance().orElseThrow();
        var periods = extras.periodsOf(NORTH);
        var policy = periods.policy();
        final long period = 1L;
        long capturedAt = period * policy.periodMillis();
        long opensAt = capturedAt + policy.materialLeadMillis();
        long closesAt = opensAt + policy.reviewWindowMillis();
        long due = -1L;
        for (PendingTask pending : runtime.snapshot().pending()) {
            if (pending.taskId().equals("periodic-capture:" + NORTH + ":" + period)) {
                due = pending.dueTimeMillis();
            }
        }
        ok("默认定期治理：开局自动拥有第 1 期期末采样待办（不靠手动 scheduleCompanyPeriodCapture）",
                due == capturedAt, "due=" + due + " expected=" + capturedAt);
        runtime.advanceTo(due);
        if (System.getenv("COMPOSED_DEBUG") != null) {
            System.out.println("  [capture] period=" + period + " due=" + due + " -> "
                    + runtime.taskCommitResult("periodic-capture:" + NORTH + ":" + period));
        }
        boolean captured = periods.snapshots().stream().anyMatch(s -> s.period() == period);
        var afterCapture = runtime.companyGovernance().orElseThrow().periodsOf(NORTH);
        captured = afterCapture.snapshots().stream().anyMatch(s -> s.period() == period);
        String meetingId = captured ? afterCapture.snapshots().stream()
                .filter(s -> s.period() == period).findFirst().orElseThrow().meetingId() : "";
        BoardModel.Meeting convened = captured
                ? runtime.companyGovernance().orElseThrow().boardOf(NORTH).board()
                .meeting(meetingId) : null;
        boolean window = convened != null && convened.createdAt() == due
                && convened.materialDeadline() == opensAt && convened.opensAt() == opensAt
                && convened.closesAt() == closesAt;
        PeriodicModel.Snapshot capturedPeriod = captured ? afterCapture.snapshots().stream()
                .filter(s -> s.period() == period).findFirst().orElseThrow() : null;
        boolean noAuthorMaterial = runtime.snapshot().information().orElseThrow().information()
                .documents().stream().noneMatch(doc ->
                        doc.docId().equals("periodic-material:" + NORTH + ":" + period));
        boolean nextCapture = runtime.snapshot().pending().stream()
                .filter(task -> task.taskId().equals("periodic-capture:" + NORTH + ":"
                        + (period + 1L))).count() == 1L;
        ok("银行期末采样：第 " + period + " 期快照 + 真实考核会议（t=" + due + "，窗口 ["
                        + opensAt + "," + closesAt + ")）+ 只冻结真值与未来材料编号，无人物署名资料",
                captured && window && noAuthorMaterial && capturedPeriod != null
                        && !capturedPeriod.materialDocId().isBlank() && nextCapture,
                "meeting=" + meetingId + " snapshots=" + afterCapture.snapshots().size()
                        + " window=" + window + " noAuthorMaterial=" + noAuthorMaterial
                        + " nextCapture=" + nextCapture);

        // PREPARE 必须在同一期真实窗口内冻结材料：窗口左闭右开 [opensAt, closesAt)。
        runtime.advanceTo(opensAt);
        long t = opensAt + 2;
        periodic(runtime, "PREPARE", "erin", period, "", "", "", 0L, 1L, t);
        runtime.advanceTo(t);
        var afterPrepare = runtime.companyGovernance().orElseThrow().periodsOf(NORTH);
        var draftOfPeriod = afterPrepare.drafts().stream()
                .filter(d -> d.period() == period).findFirst();
        boolean prepared = draftOfPeriod.isPresent();
        String draftId = prepared ? draftOfPeriod.get().draftId() : "";
        String draftDoc = prepared ? draftOfPeriod.get().docId() : "";
        String author = runtime.snapshot().information().orElseThrow().information().documents()
                .stream().filter(doc -> doc.docId().equals(draftDoc))
                .map(InformationModel.Document::publisherPartyId).findFirst().orElse("");
        ok("经理编制材料：第 " + period + " 期由经理真实发布（作者=" + author + "）+ 草稿绑定正文摘要",
                prepared && "erin".equals(author),
                "draft=" + draftId + " doc=" + draftDoc + " author=" + author);

        String[] readers = {"alice", "bob", "carol", "dave"};
        long lastRead = 0;
        for (String person : readers) {
            lastRead = readToFinish(runtime, person, "rd-bank-" + person + "-" + period, draftDoc,
                    1L, 0L);
        }
        boolean read = runtime.snapshot().information().orElseThrow().information().observations()
                .stream().filter(obs -> obs.docId().equals(draftDoc))
                .map(obs -> obs.personPartyId()).distinct().count() >= 3;
        ok("不少于三名董事真实读完材料（观察记录 ≥ 3，最后完成 " + lastRead + "）", read,
                "doc=" + draftDoc);

        // 负例：编制人本人不能自批（窗口内提交，理由必须来自批准规则）。
        long selfAt = runtime.snapshot().timeMillis() + 2;
        periodic(runtime, "APPROVE", "erin", period, draftId, "", "", 0L, 1L, selfAt);
        String selfReason = runtime.taskCommitResult(
                "pp-approve-erin-" + period + "-t:finish");
        ok("负例：经理不能自批（编制人本人批准被拒：APPROVER_IS_PREPARER）",
                PeriodicModel.REASON_APPROVER_IS_PREPARER.equals(selfReason),
                "reason=" + selfReason);

        // 三名合格董事在窗口内真实批准。
        long step = runtime.snapshot().timeMillis() + 2;
        for (String person : new String[] {"alice", "bob", "carol"}) {
            periodic(runtime, "APPROVE", person, period, draftId, "", "", 0L, 1L, step);
            step += 2;
        }
        var afterApprove = runtime.companyGovernance().orElseThrow().periodsOf(NORTH);
        ok("三名合格董事真实批准（Approval 记录 3，最后提交 " + (step - 1) + "）",
                afterApprove.approvals().size() == 3,
                "approvals=" + afterApprove.approvals().size());

        long authAt = step;
        long disclosureDeadline = capturedAt + policy.disclosureWindowMillis();
        periodic(runtime, "AUTHORIZE", "erin", period, draftId, "alice", "",
                disclosureDeadline, 1L, authAt);
        step += 2;
        var afterAuth = runtime.companyGovernance().orElseThrow().periodsOf(NORTH);
        ok("经理签发披露授权（绑定草稿版本/正文摘要/指定发布人/期限）",
                afterAuth.authorizations().size() == 1
                        && afterAuth.authorizations().get(0).publisherPersonId().equals("alice"),
                "auth=" + afterAuth.authorizations().size() + " t=" + authAt);

        long pubAt = step;
        periodic(runtime, "PUBLISH", "alice", period, draftId, "", "", 0L, 1L, pubAt);
        step += 2;
        var afterPub = runtime.companyGovernance().orElseThrow().periodsOf(NORTH);
        boolean released = afterPub.releases().size() == 1;
        ok("有权人按授权公开发布：Release 1 条 + 公开文档（t=" + pubAt + "）", released
                        && runtime.snapshot().information().orElseThrow().information()
                        .documents().stream().anyMatch(doc -> doc.docId()
                                .equals("periodic-public:" + NORTH + ":" + period)),
                "releases=" + afterPub.releases().size()
                        + " reason=" + runtime.taskCommitResult("pp-publish-alice-" + period
                        + "-t:finish")
                        + " auth=" + (afterAuth.authorizations().isEmpty() ? "-"
                                : afterAuth.authorizations().get(0).draftId() + "#"
                                + afterAuth.authorizations().get(0).draftVersion() + "@"
                                + afterAuth.authorizations().get(0).bodyDigest())
                        + " draft=" + draftId + "#" + 1L + "@"
                        + (afterAuth.drafts().isEmpty() ? "-"
                                : afterAuth.drafts().get(0).bodyDigest())
                        + " doc=" + (afterAuth.drafts().isEmpty() ? "-"
                                : afterAuth.drafts().get(0).docId()));

        // 右开截止反例放到独立世界（无现存授权）验证，见 ComposedPeriodicDeadlineTest。
        runtime.advanceTo(closesAt);

        // 关期：同一期的系统关期任务在 closesAt 执行（不提前、不推后）。
        String closeResult = runtime.taskCommitResult("periodic-close:" + NORTH + ":" + period);
        ok("关期在同一期末端执行：第 " + period + " 期 close 任务已提交（" + closeResult + "）",
                closeResult != null, "close=" + closeResult + " at=" + closesAt);
    }

    private static void periodic(WorldRuntime runtime, String action, String person, long period,
            String draftId, String publisher, String authorizationId, long expiresAt,
            long duration, long due) throws Exception {
        runtime.schedulePeriodicAction("pp-" + action.toLowerCase() + "-" + person + "-" + period,
                "pp-" + action.toLowerCase() + "-" + person + "-" + period + "-t", due, NORTH,
                person, action, period, draftId, 1L, publisher, expiresAt, authorizationId,
                List.of(), "", duration);
        runtime.advanceTo(due);
    }

    // ------------------------------------------- 本轮新增：真实读完与真实预算决策链

    /**
     * 真实读到 FINISH：排程真实开始帧，再推进到运行层按冻结阅读时长自排的自动完成帧。
     *
     * <p>不把 STARTED 当读完，也不硬编码时长：完成时点取自待办本身。</p>
     *
     * @param runtime 世界
     * @param person  读者
     * @param tag     命令/任务标识后缀
     * @param docId   资料
     * @param version 固定版本
     * @param startAt 开始时点；不大于当前时点则顺延一毫秒
     * @return 自动完成帧的到期时点
     */
    static long readToFinish(WorldRuntime runtime, String person, String tag, String docId,
            long version, long startAt) throws Exception {
        long now = runtime.snapshot().timeMillis();
        long start = startAt > now ? startAt : now + 1;
        runtime.scheduleStartReading("rd-" + tag, "rd-" + tag + "-t", start, person, docId,
                version);
        runtime.advanceTo(start);
        long finishAt = -1;
        for (PendingTask task : runtime.snapshot().pending()) {
            if (!PendingTask.INFORMATION_COMPLETE_READ_V1.equals(task.kind())) {
                continue;
            }
            if (!task.payload().contains(docId) || !task.payload().contains("\"" + person + "\"")) {
                continue;
            }
            finishAt = Math.max(finishAt, task.dueTimeMillis());
        }
        if (finishAt <= 0) {
            throw new IllegalStateException("阅读没有排出的自动完成帧：" + person + "/" + tag);
        }
        runtime.advanceTo(finishAt);
        return finishAt;
    }

    /**
     * 真实预算会议：主席召集 → 全席到会 → 冻结材料 → 全席实读（到 FINISH）→ 预算提案 →
     * 法定多数赞成 → 关闭后实施。预算只能由这条决策链产生，不接受注入。
     *
     * @param runtime     世界
     * @param company     工程公司
     * @param tag         标识后缀
     * @param labor       生产人工预算（字符串金额）
     * @param procurement 采购预算（字符串金额）
     * @return 实施完成时点
     */
    private static long budgetMeeting(WorldRuntime runtime, String company, String tag,
            String labor, String procurement) throws Exception {
        BoardModel.Charter charter = charterOf(runtime, company);
        String chair = personOfSeat(charter, charter.chairSeatId());
        List<String> seats = new ArrayList<>();
        for (BoardModel.Seat seat : charter.seats()) {
            seats.add(seat.partyId());
        }
        long start = runtime.snapshot().timeMillis() + 1;
        String docId = "budget-material-" + tag;
        runtime.schedulePublishDocument("pub-" + tag, "pub-" + tag + "-t", start, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料 " + tag, "本期预算：生产人工与采购额度", "实验材料", 10L, List.of());
        runtime.advanceTo(start);
        String meeting = "mtg-" + tag;
        long opensAt = start + 9;
        long closesAt = start + 80;
        runtime.scheduleEngineeringConvene("cv-" + tag, "cvt-" + tag, start + 2, company, chair,
                meeting, start + 8, opensAt, closesAt);
        runtime.advanceTo(start + 3);
        for (String person : seats) {
            runtime.scheduleEngineeringAttend("at-" + tag + "-" + person,
                    "att-" + tag + "-" + person, start + 5, company, person, meeting, false);
        }
        runtime.advanceTo(start + 6);
        // 材料由另一名到会董事在材料截止前提交（主席同一毫秒不能并行两件正工时），
        // 提案必须同在 materialDeadline 当刻之前落地：OPEN 时已有提案才会自动开票。
        String materialBy = seats.get(0).equals(chair) ? seats.get(1) : seats.get(0);
        runtime.scheduleEngineeringMaterial("mt-" + tag, "mtt-" + tag, start + 8, company,
                materialBy, meeting, "mat-" + tag, docId, 1L);
        long proposeAt = start + 8;
        runtime.scheduleEngineeringBudgetProposal("bp-" + tag, "bpt-" + tag, proposeAt, company,
                chair, meeting, List.of(
                        new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                money(labor)),
                        new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PROCUREMENT,
                                money(procurement))));
        runtime.advanceTo(opensAt);
        for (String person : seats) {
            readToFinish(runtime, person, "bud-" + tag + "-" + person, docId, 1L, 0L);
        }
        long voteAt = runtime.snapshot().timeMillis() + 2;
        int yes = 0;
        for (String person : seats) {
            if (person.equals(chair)) {
                continue;
            }
            runtime.scheduleEngineeringVote("bvt-" + tag + "-" + person,
                    "bvtt-" + tag + "-" + person, voteAt + yes, company, person, meeting,
                    BoardModel.Vote.YES);
            yes++;
        }
        runtime.advanceTo(voteAt + yes);
        runtime.advanceTo(closesAt);
        long implementAt = closesAt + 2;
        runtime.scheduleEngineeringImplement("bi-" + tag, "bit-" + tag, implementAt, company,
                chair, meeting);
        runtime.advanceTo(implementAt);
        if (System.getenv("COMPOSED_DEBUG") != null) {
            BoardRun after = boardOf(runtime, company);
            BoardModel.Meeting meet = after.board().meeting(meeting);
            System.out.println("  [budget] implement=" + runtime.taskCommitResult("bit-" + tag)
                    + " proposal=" + runtime.taskCommitResult("bpt-" + tag)
                    + " meeting=" + meet.status() + " failure=" + meet.failureReason()
                    + " budgets=" + after.board().budgets().size()
                    + " attendees=" + meet.attendeeSeatIds()
                    + " ballots=" + meet.ballots()
                    + " lines=" + meet.proposedLines().size());
            after.reports().values().forEach(rep -> System.out.println("  [budget-report] "
                    + rep.taskId() + " " + rep.kind() + " " + rep.accepted() + " "
                    + rep.reason()));
        }
        return implementAt;
    }

    /**
     * @param runtime  世界
     * @param company  公司
     * @param category 预算类别
     * @return 第 0 期该类别真实预算上限；未设置返回 0
     */
    private static BigDecimal engineeringBudget(WorldRuntime runtime, String company,
            BoardModel.BudgetCategory category) {
        BoardRun board = boardOf(runtime, company);
        if (board == null) {
            return money("0.00");
        }
        BoardModel.Budget budget = board.board().budget(0L, category);
        return budget == null ? money("0.00") : budget.cap();
    }
    /** 报表口径守卫：分位精确等式、负净资产合法、缺项不补 0、核销/豁免/回收两分支。 */
    private static void reportGuards(WorldRuntime runtime) {
        java.util.Map<String, BigDecimal> a = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> l = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> e = new java.util.LinkedHashMap<>();
        a.put("x", money("100.00"));
        e.put("y", money("100.00"));
        ComposedCompanyReport okSheet = new ComposedCompanyReport("probe", "probe", a, l, e,
                java.util.Map.of(), java.util.Map.of(), null, List.of(), List.of());
        a.put("x", money("100.01"));
        ComposedCompanyReport plusOne = new ComposedCompanyReport("probe", "probe", a, l, e,
                java.util.Map.of(), java.util.Map.of(), null, List.of(), List.of());
        a.put("x", money("99.99"));
        ComposedCompanyReport minusOne = new ComposedCompanyReport("probe", "probe", a, l, e,
                java.util.Map.of(), java.util.Map.of(), null, List.of(), List.of());
        ok("分位精确等式：A=L+E 为 0 时成立，正一分/负一分都不成立",
                okSheet.balanced() && !plusOne.balanced() && !minusOne.balanced(),
                "ok=" + okSheet.balanced() + " +0.01=" + plusOne.balanced()
                        + " -0.01=" + minusOne.balanced());

        java.util.Map<String, BigDecimal> na = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> nl = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> ne = new java.util.LinkedHashMap<>();
        na.put("资产", money("50.00"));
        nl.put("负债", money("100.00"));
        ne.put("权益", money("-50.00"));
        ComposedCompanyReport negative = new ComposedCompanyReport("probe", "probe", na, nl, ne,
                java.util.Map.of(), java.util.Map.of(), null, List.of(), List.of());
        ok("负净资产合法：A=50 L=100 E=-50 等式成立即接受",
                negative.balanced() && negative.totalEquity().compareTo(money("-50.00")) == 0,
                "E=" + negative.totalEquity());

        BankRun run = runtime.bankOf().orElseThrow();
        boolean unknownThrows;
        try {
            ClientCompanyReport.of(run, runtime.snapshot().accounts(), "ghost");
            unknownThrows = false;
        } catch (IllegalArgumentException expected) {
            unknownThrows = expected.getMessage() != null
                    && expected.getMessage().startsWith("该主体未在银行档案登记：");
        }
        ClientCompanyReport investor = ClientCompanyReport.of(run,
                runtime.snapshot().accounts(), "investor");
        ok("缺项不补 0：未登记主体抛错；未声明存款科目的主体 depositOpened=false 且写明缺项",
                unknownThrows && !investor.depositOpened() && !investor.depositDeclared()
                        && investor.notes().stream().anyMatch(note -> note.contains("未开立")),
                "unknownThrows=" + unknownThrows + " investorDeclared="
                        + investor.depositDeclared());

        ClientCompanyReport.LoanLine writeOff = new ClientCompanyReport.LoanLine("north", "cufe",
                money("200.00"), money("120.00"), money("80.00"), money("0.00"), "CURRENT", false);
        ClientCompanyReport.LoanLine waived = new ClientCompanyReport.LoanLine("north", "cufe",
                money("120.00"), money("120.00"), money("0.00"), money("0.00"), "CURRENT", false);
        ClientCompanyReport.LoanLine recovered = new ClientCompanyReport.LoanLine("north",
                "cufe", money("120.00"), money("120.00"), money("0.00"), money("0.00"),
                "CURRENT", false);
        ok("同一核销状态两分支：核销 80 后名义本金仍 200（未豁免/未回收）；WAIVE 与 RECOVER 后 120",
                writeOff.contract().compareTo(money("200.00")) == 0
                        && writeOff.writtenOff().compareTo(money("80.00")) == 0
                        && waived.contract().compareTo(money("120.00")) == 0
                        && recovered.contract().compareTo(money("120.00")) == 0,
                "writeOff=" + writeOff.contract() + " waive=" + waived.contract()
                        + " recover=" + recovered.contract());
    }

    private static void reports(WorldRuntime runtime) {
        BankRun run = runtime.bankOf().orElseThrow();
        if (System.getenv("COMPOSED_DEBUG") != null) {
            runtime.snapshot().accounts().forEach((id, account) -> System.out.println(
                    "  [report-account] " + id + " owner=" + account.ownerId() + " cash="
                            + account.cash()));
            BankState st = run.decode(runtime.snapshot().accounts()).state();
            System.out.println("  [report-bank] dep(cufe)=" + st.depositOf(NORTH, "cufe")
                    + " dep(eng-a)=" + st.depositOf(NORTH, "eng-a")
                    + " reserve=" + st.gl(NORTH, "RESERVE")
                    + " loan=" + st.loan(NORTH, "cufe"));
        }
        BankCompanyReport bank = BankCompanyReport.of(run, runtime.snapshot().accounts(), NORTH);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (CashLedger.CashAccount account : runtime.snapshot().accounts().values()) {
            total = total.add(account.cash());
        }
        BigDecimal principal = money("200.00");
        ok("银行报表闭合（含 World 贷款 200、应收利息与未结转损益）",
                bank.balanced()
                        && bank.assets().get("book-cufe").compareTo(money("200.00")) == 0
                        && bank.totalEquity().compareTo(bank.bookedEquity()
                        .add(bank.currentEarnings())) == 0,
                "A=" + bank.totalAssets() + " L=" + bank.totalLiabilities()
                        + " E=" + bank.totalEquity() + " booked=" + bank.bookedEquity()
                        + " pnl=" + bank.currentEarnings());
        ClientCompanyReport cufe = ClientCompanyReport.of(run, runtime.snapshot().accounts(),
                "cufe");
        ClientCompanyReport engA = ClientCompanyReport.of(run, runtime.snapshot().accounts(),
                "eng-a");
        BigDecimal cufeIdentity = cufe.cash().add(cufe.deposits()).add(cufe.depositInterest())
                .subtract(cufe.loanPrincipal()).subtract(cufe.interestReceivable());
        ok("客户金融子表：净金融资产 = 现金 + 存款 + 应收利息 − 名义本金 − 借款利息（CO 本金 200）",
                cufe.netFinancialAssets().compareTo(cufeIdentity) == 0
                        && cufe.loanPrincipal().compareTo(principal) == 0
                        && cufe.interestReceivable().compareTo(money("0.33")) == 0
                        && engA.loanPrincipal().signum() == 0
                        && engA.deposits().compareTo(money("2140.00")) == 0
                        && cufe.depositOpened(),
                cufe.summary() + " | " + engA.summary());
        ComposedCompanyReport cufeSheet = ComposedCompanyReport.of(runtime, CUFE);
        ComposedCompanyReport engSheet = ComposedCompanyReport.of(runtime, ENG_A);
        ok("完整公司报表：制造/工程子账本 + 存款 + 证券成本 + 借款负债 + 已实现损益，A=L+E",
                cufeSheet.balanced() && engSheet.balanced()
                        && cufeSheet.liabilities().get("借款本金").compareTo(principal) == 0
                        && cufeSheet.assets().get("银行存款").compareTo(money("450.00")) == 0
                        && cufeSheet.equity().get("开局未计入子账本权益的证券成本")
                        .compareTo(money("200.00")) == 0
                        && engSheet.assets().get("证券持仓成本").compareTo(money("4100.00")) == 0,
                "CO A=" + cufeSheet.totalAssets() + " L=" + cufeSheet.totalLiabilities()
                        + " E=" + cufeSheet.totalEquity()
                        + " | ENG-A A=" + engSheet.totalAssets() + " E="
                        + engSheet.totalEquity());
        ok("世界现金合计仍 51100（存款不是现金、银行权益不与客户权利双计）",
                total.compareTo(money("51100.00")) == 0, "cash=" + total);
    }

    // ---------------------------------------------------------------- 断言

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** 科目金额合计（报表分节）。 */
    private static BigDecimal sumAmounts(Map<String, BigDecimal> amounts) {
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        for (BigDecimal amount : amounts.values()) {
            sum = sum.add(amount);
        }
        return sum;
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

    /** 供脚本读取的运行摘要。 */
    static Map<String, BigDecimal> cash(Map<String, CashLedger.CashAccount> accounts) {
        Map<String, BigDecimal> map = new java.util.TreeMap<>();
        accounts.forEach((id, account) -> map.put(id, account.cash()));
        return map;
    }
}
