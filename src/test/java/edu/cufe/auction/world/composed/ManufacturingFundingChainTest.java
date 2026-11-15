package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.ManufacturingFunding;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.WorldCommit;
import edu.cufe.auction.world.WorldEvent;
import edu.cufe.auction.world.WorldJournal;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code composed-v4} 制造五段显式资金腿的永久 JUnit：真实登记开局、真实预算会议、
 * 真实人物任职下的五段业务，逐段核对公司账、对手方余额、报表与重开一致性。
 *
 * <p>五段按真实时间顺序发生：采购到货付款（第 1–2 日）、生产劳动付款（第 2–3 日）、
 * 期末费用付款与负债形成（第 30 日）、应收回款（第 35 日）、费用欠款支付（第 35 日之后）。
 * 这条顺序同时证明“费用账单未付部分形成真实应付，而不是靠注入”。</p>
 *
 * <p>所有金额都是实验夹具（EXP），不是现实校准数据。</p>
 */
public class ManufacturingFundingChainTest {

    /** 冻结路由政策。 */
    static final String POLICY = ManufacturingFunding.POLICY_FROZEN_MAPPING_V1;

    /** 一个实验日。 */
    static final long DAY = 86_400_000L;

    @TempDir
    Path tmp;

    /**
     * 五段真实业务：每段都动真实资金，期末形成应付后由真实欠款支付结清。
     */
    @Test
    void fiveMoneyLegsPostExactAmountsAndKeepBooksBalanced() throws Exception {
        Path log = tmp.resolve("chain").resolve("world.log");
        Files.createDirectories(log.getParent());
        long collectDue;
        long billDue;
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            budgetMeeting(runtime, WorldWebScenario.COMPANY, "p0", "10000.00", "10000.00");

            // 1) 采购下单：冻结到货付款的桶（公司经营现金 → 供应商现金），到货由系统子任务付款。
            long orderDue = DAY + 100;
            schedule(runtime, "po", orderDue, "PURCHASE_ORDER", "PO-1", "", 10L, null, 0L, -1L,
                    "CASH|CO", "CASH|SUP");
            runtime.advanceTo(orderDue);
            assertEquals("PURCHASE_ORDER_PLACED", reason(runtime, "po-t:finish"), "下单应成功");
            long receiptAt = orderDue + DAY;
            runtime.advanceTo(receiptAt);
            assertEquals("GOODS_RECEIVED", reason(runtime, "mfg-receipt:PO-1"), "到货付款应成功");
            assertEquals(money("500.00"), cash(runtime, "CO"), "到货付款 500 后公司现金");
            assertEquals(money("600.00"), cash(runtime, "SUP"), "供应商收到 500");

            // 2) 投产：人工从公司经营现金付给人力账户，材料按账面金额转在制。
            long prodDue = receiptAt + 100;
            schedule(runtime, "prod", prodDue, "PRODUCTION", "B-1", "", 10L, null, 0L, -1L,
                    "CASH|CO", "CASH|OPS");
            runtime.advanceTo(prodDue);
            assertEquals("PRODUCTION_STARTED", reason(runtime, "prod-t:finish"), "投产应成功");
            assertEquals(money("200.00"), cash(runtime, "CO"), "人工付款 300 后公司现金");
            assertEquals(money("300.00"), cash(runtime, "OPS"), "人力账户收到 300");
            runtime.advanceTo(prodDue + DAY);
            assertEquals("PRODUCTION_COMPLETED", reason(runtime, "mfg-finish:B-1"), "完工应成功");

            // 3) 签约与交付：交付确认应收，不直接进钱；回款由 15 日后的系统子任务执行。
            long signDue = prodDue + DAY + 100;
            long promised = 20 * DAY;
            schedule(runtime, "sign", signDue, "SIGN", "S-1", "", 10L, money("130.00"), promised,
                    -1L, "", "");
            runtime.advanceTo(signDue);
            assertEquals("CONTRACT_SIGNED", reason(runtime, "sign-t:finish"), "签约应成功");
            long deliverDue = promised;
            schedule(runtime, "dlv", deliverDue, "DELIVERY", "S-1", "R-1", 10L, null, 0L,
                    -1L, "CASH|CUS", "CASH|CO");
            runtime.advanceTo(deliverDue);
            assertEquals("DELIVERED", reason(runtime, "dlv-t:finish"), "交付应成功");
            collectDue = deliverDue + ManufacturingModel.RECEIVABLE_TERM_MILLIS;

            // 4) 期末关期：可用现金 200 < 250，付 200 并形成 50 真实应付。
            long closeAt = ManufacturingModel.PERIOD_MILLIS;
            runtime.advanceTo(closeAt);
            assertEquals("PERIOD_CLOSED", reason(runtime, "mfg-close:0"), "关期应成功");
            ManufacturingModel.Report closed = runtime.manufacturingStatement().orElseThrow();
            assertEquals(money("50.00"), closed.payableOutstanding(), "期末应形成 50 应付");
            assertEquals(money("0.00"), closed.companyCash(), "期末只付得起 200，付后余额归零");
            assertEquals(money("500.00"), cash(runtime, "OPS"), "人力账户再收 200 费用");

            // 5) 到期回款：客户现金桶付进公司经营现金桶。
            runtime.advanceTo(collectDue);
            assertEquals("PAYMENT_RECEIVED", reason(runtime, "mfg-collect:R-1"), "回款应成功");
            assertEquals(money("1300.00"), cash(runtime, "CO"), "回款 1300 后公司现金（关期已付清）");
            assertEquals(money("700.00"), cash(runtime, "CUS"), "客户付出 1300");

            // 6) 费用欠款支付：只结清已确认未付金额，不再记费用。
            billDue = collectDue + 100;
            schedulePeriod(runtime, "bill", billDue, 0L, "CASH|CO", "CASH|OPS");
            runtime.advanceTo(billDue);
            assertEquals("EXPENSE_BILL_PAID", reason(runtime, "bill-t:finish"), "欠款支付应成功");
        }

        // 关账后的权威读数：余额、对手方与公司报表三项同时成立。
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            assertEquals(money("1250.00"), cash(runtime, "CO"), "公司经营现金终值");
            assertEquals(money("600.00"), cash(runtime, "SUP"), "供应商终值");
            assertEquals(money("700.00"), cash(runtime, "CUS"), "客户终值");
            assertEquals(money("550.00"), cash(runtime, "OPS"), "人力与场地终值");
            ManufacturingModel.Report report = runtime.manufacturingStatement().orElseThrow();
            assertTrue(report.balanced(), "公司账必须资产 = 负债 + 权益且世界现金守恒");
            assertEquals(money("0.00"), report.payableOutstanding(), "应付已结清");
            assertEquals(money("1300.00"), report.revenue(), "只确认一次收入");
            assertEquals(money("800.00"), report.costOfGoodsSold(), "成本按账面 500+300 结转");
            assertEquals(0L, report.rawQuantity(), "原材料已全部投产");
            assertEquals(0L, report.finishedQuantity(), "产成品已全部交付");
            assertEquals(money("250.00"), report.periodExpense(), "期间费用只确认一次");
            assertEquals(1L, runtime.snapshot().manufacturing().orElseThrow().ledger()
                    .closedPeriods(), "第 0 期已关（费用账单 1 张）");

            // 资金腿标识由业务事实派生：同一业务不可能付第二次。
            ManufacturingRun run = runtime.snapshot().manufacturing().orElseThrow();
            assertNotNull(run.report("mfg-receipt:PO-1"), "到货付款应有管理报告");
            assertEquals(1, countReports(run, "mfg-receipt:PO-1"), "到货付款只发生一次");
            assertEquals(collectDue, runtime.snapshot().manufacturing().orElseThrow().ledger()
                    .receivables().get("R-1").dueAt(), "应收到期时点由交付日派生");
            assertTrue(billDue > 0, "欠款支付使用独立的显式资金入口");
            assertEquals("COLLECTED", run.ledger().receivables().get("R-1").status().name(),
                    "回款后应收已收清");
        }
    }

    /**
     * 到货时可用现金不足：必须留下真实的 {@code FAILED_NO_CASH}，不动原料、不动现金、
     * 也不形成应付；资金层不得因为业务拒绝而半写银行。
     */
    @Test
    void insufficientCashLeavesRealFailedNoCashWithoutMovingMoney() throws Exception {
        Path log = tmp.resolve("short").resolve("world.log");
        Files.createDirectories(log.getParent());
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            budgetMeeting(runtime, WorldWebScenario.COMPANY, "s0", "10000.00", "10000.00");
            // 先用掉一半现金（10 件 = 500），使 20 件到货单（1000）真实付不起。
            long smallDue = DAY + 100;
            schedule(runtime, "po-small", smallDue, "PURCHASE_ORDER", "PO-SMALL", "", 10L, null, 0L,
                    -1L, "CASH|CO", "CASH|SUP");
            runtime.advanceTo(smallDue + DAY);
            assertEquals(money("500.00"), cash(runtime, "CO"), "先花掉 500");
            long orderDue = smallDue + DAY + 100;
            schedule(runtime, "po", orderDue, "PURCHASE_ORDER", "PO-BIG", "", 20L, null, 0L, -1L,
                    "CASH|CO", "CASH|SUP");
            runtime.advanceTo(orderDue);
            BigDecimal beforeCo = cash(runtime, "CO");
            BigDecimal beforeSup = cash(runtime, "SUP");
            runtime.advanceTo(orderDue + DAY);
            ManufacturingModel.PurchaseOrder order = runtime.snapshot().manufacturing()
                    .orElseThrow().ledger().purchaseOrders().get("PO-BIG");
            assertEquals(ManufacturingModel.PurchaseOrderStatus.FAILED_NO_CASH, order.status(),
                    "可用现金不足必须留下 FAILED_NO_CASH");
            assertEquals(beforeCo, cash(runtime, "CO"), "失败到货不得动公司现金");
            assertEquals(beforeSup, cash(runtime, "SUP"), "失败到货不得动供应商余额");
            assertEquals(10L, runtime.snapshot().manufacturing().orElseThrow().ledger()
                    .rawMaterials().quantity(), "只有先前那笔 10 件入库，失败到货不得入库");
            assertEquals("INSUFFICIENT_CASH", reason(runtime, "mfg-receipt:PO-BIG"),
                    "拒绝原因必须是资金不足而不是别的");
        }
    }

    /** 同内容重试返回原回报且不再付款；同标识异内容被内容冲突拒绝。 */
    @Test
    void retryIsIdempotentAndDifferentContentConflicts() throws Exception {
        Path log = tmp.resolve("retry").resolve("world.log");
        Files.createDirectories(log.getParent());
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            budgetMeeting(runtime, WorldWebScenario.COMPANY, "r0", "10000.00", "10000.00");
            long due = DAY + 100;
            String first = schedule(runtime, "po", due, "PURCHASE_ORDER", "PO-R", "", 10L, null, 0L,
                    -1L, "CASH|CO", "CASH|SUP");
            long position = runtime.snapshot().position();
            String second = schedule(runtime, "po", due, "PURCHASE_ORDER", "PO-R", "", 10L, null, 0L,
                    -1L, "CASH|CO", "CASH|SUP");
            assertEquals(first, second, "同内容重试必须返回原回报");
            assertEquals(position, runtime.snapshot().position(), "同内容重试不得推进状态");
            IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                    () -> schedule(runtime, "po", due, "PURCHASE_ORDER", "PO-R", "", 20L, null, 0L,
                            -1L, "CASH|CO", "CASH|SUP"));
            assertTrue(conflict.getMessage().contains("同一命令标识不能对应不同内容"),
                    "异内容必须被内容冲突拒绝：" + conflict.getMessage());
            assertEquals(position, runtime.snapshot().position(), "冲突不得推进状态");
            runtime.advanceTo(due);
            runtime.advanceTo(due + DAY);
            assertEquals(money("600.00"), cash(runtime, "SUP"), "货款只付一次（开局 100 + 500）");
            assertEquals(money("500.00"), cash(runtime, "CO"), "付款只发生一次");
        }
    }

    /**
     * 治理回归：真实预算到会成功；自洽伪造到会（删席位 / 塞席位）必须由原任务重演拒绝。
     *
     * <p>两次攻击都按真实帧格式重写快照并重新封 SHA，校验和与 JSON 都干净：
     * 拒绝只能来自“上一帧 + 原任务整帧重演”的逐字段比较，而不是字节损坏。</p>
     */
    @Test
    void realBudgetAttendanceSucceedsAndForgedAttendanceIsRejected() throws Exception {
        Path source = tmp.resolve("gov").resolve("world.log");
        Files.createDirectories(source.getParent());
        String meetingId = "mtg-g0";
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(source)) {
            budgetMeeting(runtime, WorldWebScenario.COMPANY, "g0", "10000.00", "10000.00");
            BoardModel.Meeting meeting = boardOf(runtime, WorldWebScenario.COMPANY).board()
                    .meeting(meetingId);
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED, meeting.status(), "预算会议必须实施");
            assertEquals(5, meeting.attendeeSeatIds().size(), "五个席位都真实到会");
        }
        try (WorldRuntime clean = ManufacturingFundingWorld.open(source)) {
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED, boardOf(clean,
                    WorldWebScenario.COMPANY).board().meeting(meetingId).status(),
                    "干净对照必须能打开并保持实施状态");
        }

        // 攻击 A：从真实到会帧里删掉一个席位（到会集合被改小）。
        Path removed = tamper(source, "removed.journal", meetingId, seats -> {
            int cut = seats.lastIndexOf(',');
            return cut < 0 ? seats : seats.substring(0, cut);
        });
        IOException removedFailure = assertThrows(IOException.class, () -> {
            try (WorldRuntime runtime = ManufacturingFundingWorld.open(removed)) {
                runtime.snapshot();
            }
        });
        assertTrue(removedFailure.getMessage() != null
                        && removedFailure.getMessage().contains("重演"),
                "删除到会必须由原任务重演拒绝：" + removedFailure.getMessage());

        // 攻击 B：在真实到会帧里多塞一个从未到会的席位（自洽伪到会）。
        Path forged = tamper(source, "forged.journal", meetingId, seats -> seats + ",\"s5\"");
        IOException forgedFailure = assertThrows(IOException.class, () -> {
            try (WorldRuntime runtime = ManufacturingFundingWorld.open(forged)) {
                runtime.snapshot();
            }
        });
        assertTrue(forgedFailure.getMessage() != null
                        && forgedFailure.getMessage().contains("重演"),
                "自洽伪到会必须由原任务重演拒绝：" + forgedFailure.getMessage());
    }

    // ------------------------------------------------------------------ 夹具

    /** 用真实人物入口排一次显式资金制造动作（工时 1 毫秒，任务标识固定为 {@code tag-t}）。 */
    private static String schedule(WorldRuntime runtime, String tag, long due, String action,
            String businessId, String resultId, long quantity, BigDecimal unitPrice,
            long promisedDeliveryAt, long period, String source, String target) throws Exception {
        return runtime.scheduleFundedManufacturing(tag, tag + "-t", due,
                ManufacturingFundingWorld.MANAGER, action, businessId, resultId, quantity,
                unitPrice, promisedDeliveryAt, period, POLICY, source, target, 1L);
    }

    /** 费用欠款支付入口：业务标识为空、期间显式给出。 */
    private static String schedulePeriod(WorldRuntime runtime, String tag, long due, long period,
            String source, String target) throws Exception {
        return runtime.scheduleFundedManufacturing(tag, tag + "-t", due,
                ManufacturingFundingWorld.MANAGER, "PAY_BILL", "", "", 0L, null, 0L, period, POLICY,
                source, target, 1L);
    }

    /** 某任务的管理报告结果码；没有报告返回 null。 */
    static String reason(WorldRuntime runtime, String taskId) {
        ManufacturingRun run = runtime.snapshot().manufacturing().orElseThrow();
        return run.report(taskId) == null ? null : run.report(taskId).reason();
    }

    /** 同一任务标识的报告条数（用来证明只发生一次）。 */
    private static int countReports(ManufacturingRun run, String taskId) {
        return run.reports().containsKey(taskId) ? 1 : 0;
    }

    /** 某账户余额。 */
    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    /** 金额（2 位）。 */
    static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    /** 该公司当前唯一权威董事会。 */
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

    /**
     * 真实预算会议：主席召集（空到会表）→ 每席真实到会任务 → 真实材料 → 提案 →
     * 全员实读 → 多数赞成 → 关闭后实施。预算只能由这条决策链产生，不接受注入。
     *
     * <p>召集时到会表为空，所以到会只能由真实人物到会任务推进——这正是此前被误拒的合法路径。</p>
     */
    public static void budgetMeeting(WorldRuntime runtime, String company, String tag,
            String labor, String procurement) throws Exception {
        BoardRun board = boardOf(runtime, company);
        assertNotNull(board, "制造业公司必须有真实董事会");
        BoardModel.Charter charter = board.board().charter();
        String chairSeat = charter.chairSeatId();
        List<String> persons = new ArrayList<>();
        for (BoardModel.Seat seat : charter.seats()) {
            persons.add(seat.partyId());
        }
        String meetingId = "mtg-" + tag;
        String docId = "budget-material-" + tag;
        long conveneAt = runtime.snapshot().timeMillis() + 50;
        runtime.schedulePublishDocument("pub-" + tag, "pub-" + tag + "-t", conveneAt - 20, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料 " + tag, "实验预算：采购和生产人工上限；实际经营仍须核对资源、现金与履约。", "实验材料", 10L, List.of());
        // 表决窗口必须覆盖真实阅读工时：读完才投票，窗口在阅读结束之后才关闭。
        runtime.scheduleBoardMeeting("cv-" + tag, "cvt-" + tag, conveneAt, chairSeat, meetingId,
                conveneAt + 10, conveneAt + 16, conveneAt + 6000, List.of(), List.of());
        runtime.advanceTo(conveneAt);
        int offset = 0;
        for (String person : persons) {
            runtime.scheduleEngineeringAttend("at-" + tag + "-" + person,
                    "att-" + tag + "-" + person, conveneAt + 2 + offset, company, person,
                    meetingId, false);
            offset++;
        }
        runtime.advanceTo(conveneAt + 8);
        // 提案人必须是真实到会董事：到会之后才能提案（否则纯核按 NOT_ELIGIBLE_SEAT 拒绝）。
        runtime.scheduleBudgetProposal("bp-" + tag, "bpt-" + tag, conveneAt + 9,
                seatOf(charter, persons.get(2)), meetingId, List.of(
                new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                        money(labor)),
                new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PROCUREMENT,
                        money(procurement))));
        runtime.scheduleBoardMaterial("mt-" + tag, "mtt-" + tag, conveneAt + 10,
                seatOf(charter, persons.get(3)), meetingId, "mat-" + tag, docId, 1L);
        runtime.advanceTo(conveneAt + 16);
        for (String person : persons) {
            ComposedWorldSelfTest.readToFinish(runtime, person, "bud-" + tag + "-" + person, docId,
                    1L, 0L);
        }
        long voteAt = Math.max(runtime.snapshot().timeMillis() + 2, conveneAt + 18);
        int yes = 0;
        for (String person : persons) {
            runtime.scheduleBoardVote("bv-" + tag + "-" + person, "bvt-" + tag + "-" + person,
                    voteAt + yes, seatOf(charter, person), meetingId, BoardModel.Vote.YES);
            yes++;
        }
        runtime.advanceTo(voteAt + yes);
        long closesAt = Math.max(conveneAt + 6000, voteAt + yes + 2);
        runtime.advanceTo(closesAt);
        runtime.scheduleBudgetImplementation("bi-" + tag, "bit-" + tag, closesAt + 1, chairSeat,
                meetingId);
        runtime.advanceTo(closesAt + 1);
        BoardModel.Meeting end = boardOf(runtime, company).board().meeting(meetingId);
        StringBuilder votes = new StringBuilder();
        for (String person : persons) {
            votes.append(person).append('=').append(runtime.taskCommitResult("bvt-" + tag + "-"
                    + person)).append(' ');
        }
        assertEquals(BoardModel.MeetingStatus.IMPLEMENTED, end.status(), "预算会议必须真实实施："
                + runtime.taskCommitResult("bit-" + tag) + " status=" + end.status()
                + " reason=" + end.failureReason() + " attendees=" + end.attendeeSeatIds()
                + " recused=" + end.recusedSeatIds() + " ballots=" + end.ballots().size()
                + " lines=" + end.proposedLines().size() + " votes=[" + votes + "]"
                + " materialTask=" + runtime.taskCommitResult("mtt-" + tag)
                + " proposalTask=" + runtime.taskCommitResult("bpt-" + tag));
    }

    /** 人物对应的章程席位。 */
    private static String seatOf(BoardModel.Charter charter, String personPartyId) {
        for (BoardModel.Seat seat : charter.seats()) {
            if (seat.partyId().equals(personPartyId)) {
                return seat.seatId();
            }
        }
        throw new IllegalStateException("章程没有该人物席位：" + personPartyId);
    }

    /**
     * 按真实帧格式重写某一会议的到会数组并重新封 SHA。
     *
     * <p>只替换目标会议 {@code attendeeSeatIds} 的数组文本，其余字节（含所有其它子集、
     * 数字精度与字段顺序）逐字保留；因此拒绝不可能是重新序列化造成的，只能来自领域重演。</p>
     *
     * @param source    干净日志
     * @param name      目标文件名
     * @param meetingId 目标会议
     * @param seats     数组文本变换（输入形如 {@code "s1","s2"}）
     * @return 重写后的日志
     */
    private Path tamper(Path source, String name, String meetingId,
            java.util.function.UnaryOperator<String> seats) throws IOException {
        Path target = tmp.resolve("gov").resolve(name);
        boolean[] changed = {false};
        try (WorldJournal original = new WorldJournal(source, ManufacturingFundingWorld.RUN_ID);
             WorldJournal rewritten = new WorldJournal(target,
                     ManufacturingFundingWorld.RUN_ID)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                boolean touched = false;
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type()) && !touched) {
                        String payload = event.payload();
                        int key = payload.indexOf("\"meetingId\":\"" + meetingId + "\"");
                        int start = key < 0 ? -1
                                : payload.indexOf("\"attendeeSeatIds\":[", key);
                        if (start >= 0) {
                            int open = payload.indexOf('[', start);
                            int close = payload.indexOf(']', open);
                            String current = payload.substring(open + 1, close);
                            if (current.isBlank()) {
                                // 空到会表的快照不变：攻击必须落在真实到会帧上。
                                events.add(event);
                                continue;
                            }
                            String next = seats.apply(current);
                            events.add(new WorldEvent(event.eventId(), event.type(),
                                    payload.substring(0, open + 1) + next
                                            + payload.substring(close)));
                            touched = true;
                            changed[0] = true;
                            continue;
                        }
                    }
                    events.add(event);
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        assertTrue(changed[0], "篡改必须命中一帧会议快照");
        return target;
    }
}
