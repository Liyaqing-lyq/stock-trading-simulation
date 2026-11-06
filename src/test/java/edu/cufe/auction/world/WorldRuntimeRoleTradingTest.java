package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleTradingModel.Status;
import edu.cufe.auction.world.WorldRoleSession.ChildDraft;
import edu.cufe.auction.world.WorldRoleSession.ChildRef;
import edu.cufe.auction.world.WorldRoleSession.Citation;
import edu.cufe.auction.world.WorldRoleSession.HaltDraft;
import edu.cufe.auction.world.WorldRoleSession.ResearchDraft;
import edu.cufe.auction.world.WorldRoleSession.ReviewDraft;
import edu.cufe.auction.world.WorldRoleSession.TargetDraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4c Task2–4：四岗交易链的唯一日志运行桥（role-trading-experiment-v1 / schema7）。
 *
 * <p>全部动作走真实人物会话与唯一撮合；钱股只用有限夹具（产品 1000.00 / 外部持有人 200 股），
 * 每批传不可变候选。额度只在真实 {@link WorldExchange} 受理后才占用。</p>
 *
 * <p>关键窗口：目标更新与外部卖单排在同一未来 T 的 COMPLETION，按
 * {@link WorldScheduler#fairOrder(long, String)} 选定"更新先、成交后"的顺序；撤单在 T+1 的 RULES，
 * 因此 T 时成交已入账而撤单尚未发生。</p>
 */
@DisplayName("WorldRuntime：四岗交易运行桥")
class WorldRuntimeRoleTradingTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-role-trading";
    private static final String SYM = "CUFE1";
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;

    private static final String ORG_F = "ORG-F";
    private static final String ORG_G = "ORG-G";
    private static final String PRODUCT = "fund-a";
    private static final String PRODUCT_G = "fund-b";
    private static final String ACC_FUND = "ACC-FUND";
    private static final String ACC_FUND_G = "ACC-FUND2";
    private static final String ACC_INV = "INV";
    private static final String INV = "investor";

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";

    private static final long ERIN_ENDS = 400_000L;

    private static final String TGT = "tgt-1";
    private static final String SRC_DOC = "src-1";
    private static final String NOTE_DOC = "note-1";
    private static final String RESEARCH = "res-1";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 1. 四人真实链路 + 40+40+30 拒 + 撤单未确认仍占额
    // ------------------------------------------------------------------

    @Test
    @DisplayName("四人研究→目标→审核→确认→真实部分成交；40+40+30 拒；撤单确认前仍占额")
    void fourRolesProduceRealPartialFillsUnderAbsoluteTargetAndChildQuota() throws IOException {
        try (WorldRuntime rt = open("happy.journal")) {
            establishTarget(rt, 0L, 500_000L, 400_000L, 100L, 100L, 40L);

            rt.scheduleOrder("o1", "ot1", 127_000L, INV, ACC_INV, SYM, Side.SELL,
                    OrderType.LIMIT, price("9.00"), 20L);
            rt.advanceTo(127_000L);

            child(rt, DAVE, "c1", 40L, "10.00", 128_000L);
            rt.advanceTo(128_000L);
            assertEquals(820_00L, cents(cash(rt, ACC_FUND)), "只按实际成交价扣款");
            assertEquals(20L, held(rt, ACC_FUND));
            assertEquals(20L, leaves(rt, DAVE, "c1"));

            child(rt, DAVE, "c2", 40L, "10.00", 129_000L);
            rt.advanceTo(129_000L);
            assertEquals(600_00L, cents(frozen(rt)), "20+40 股按限价冻结");

            child(rt, DAVE, "c3", 30L, "10.00", 130_000L);
            rt.advanceTo(130_000L);
            assertEquals(RoleTradingModel.REASON_QUOTA_EXCEEDED, notice(rt, DAVE, t("c3")).reason());
            assertEquals(3, orderCount(rt), "拒单不建订单");

            child(rt, DAVE, "c4", 20L, "10.00", 131_000L);
            rt.advanceTo(131_000L);
            assertEquals(800_00L, cents(frozen(rt)));

            session(rt, DAVE).cancelChild("cc1", "ct1", 140_000L, new ChildRef(TGT, DAVE, "c1"));
            child(rt, DAVE, "c5", 10L, "10.00", 132_000L);
            rt.advanceTo(132_000L);
            assertEquals(RoleTradingModel.REASON_QUOTA_EXCEEDED, notice(rt, DAVE, t("c5")).reason(),
                    "撤单未确认前额度不得释放");

            rt.advanceTo(140_000L);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status());
            assertEquals(20L, orderOf(rt, "c1").filled(), "撤单保留既有成交");
            assertEquals(600_00L, cents(frozen(rt)), "只释放未成交的 20 股");

            child(rt, DAVE, "c6", 10L, "10.00", 141_000L);
            rt.advanceTo(141_000L);
            assertEquals(700_00L, cents(frozen(rt)));
            assertEquals(90L, RoleTradingModel.used(trading(rt), exchange(rt), TGT, Side.BUY),
                    "filled 20 + 活动剩余 70");

            assertEquals(1_000_00L, cents(totalCash(rt)), "世界现金总量不变");
            assertEquals(20L, held(rt, ACC_FUND));
            assertEquals(180L, held(rt, ACC_INV));
        }
    }

    // ------------------------------------------------------------------
    // 2. 更新后、撤单前的真实成交（同 T 公平顺序），撤净后按最新持仓重建基点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("更新任务先于外部卖单执行：T 时成交入账、T+1 才撤单，基点取最新持仓")
    void updateSettlesAfterRealCancelsAndRebuildsBaseFromLatestHolding() throws IOException {
        long base = DAY;
        long updateAt = base + 131_000L;
        try (WorldRuntime rt = open("update.journal")) {
            establishTarget(rt, base, 3 * DAY, 3 * DAY - 1, 100L, 100L, 60L);

            rt.scheduleOrder("o1", "ot1", base + 127_000L, INV, ACC_INV, SYM, Side.SELL,
                    OrderType.LIMIT, price("10.00"), 30L);
            rt.advanceTo(base + 127_000L);

            child(rt, DAVE, "c1", 30L, "10.00", base + 128_000L);
            rt.advanceTo(base + 128_000L);
            child(rt, DAVE, "c2", 50L, "10.00", base + 129_000L);
            rt.advanceTo(base + 129_000L);
            assertEquals(30L, held(rt, ACC_FUND));
            assertEquals(700_00L, cents(cash(rt, ACC_FUND)));

            // 同一未来 T：更新任务与外部卖单都在 COMPLETION，用公平顺序选定"更新先"
            String updateTask = WorldRuntime.roleTaskId(BOB, "ut1");
            String sellTask = fairOrderAfter("ot-buy-after", updateTask);
            rt.scheduleOrder("ob", sellTask, updateAt, INV, ACC_INV, SYM, Side.SELL,
                    OrderType.LIMIT, price("10.00"), 10L);
            session(rt, BOB).setTarget("cu1", "ut1", updateAt,
                    new TargetDraft(TGT, 1, ORG_F, PRODUCT, ACC_FUND, SYM, 120L,
                            price("10.00"), 3 * DAY, RESEARCH));
            rt.advanceTo(updateAt);

            assertTrue(exchange(rt).report(sellTask) != null, "外部卖单未执行");
            assertEquals(WorldExchange.REASON_FILLED, exchange(rt).report(sellTask).reason(),
                    "外部卖单应全部成交");
            assertEquals(Status.UPDATING, target(rt, TGT).status(), "更新阶段先停新子单");
            assertEquals(40L, held(rt, ACC_FUND), "撤单确认前仍有合法成交并立即入账");
            assertEquals(600_00L, cents(cash(rt, ACC_FUND)));
            assertEquals(40L, leaves(rt, DAVE, "c2"), "旧挂单在撤单前仍活动");
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c2").status());

            child(rt, DAVE, "c3", 10L, "10.00", updateAt + 1);
            rt.advanceTo(updateAt + 1);
            assertEquals(RoleTradingModel.REASON_STALE_VERSION, notice(rt, DAVE, t("c3")).reason(),
                    "旧版本迟到子单被拒");
            child(rt, DAVE, "c7", 10L, "10.00", updateAt + 2, 2, 1);
            rt.advanceTo(updateAt + 2);
            assertEquals(RoleTradingModel.REASON_REVIEW_REQUIRED, notice(rt, DAVE, t("c7")).reason(),
                    "新版本必须先重新审核确认");

            RoleTradingView.TargetView after = target(rt, TGT);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c2").status(),
                    "撤单在下一个 RULES 执行");
            assertEquals(0L, leaves(rt, DAVE, "c2"));
            assertEquals(2, after.current().version());
            assertEquals(40L, after.current().baseQuantity(), "基点取撤净时的真实持仓");
            assertEquals(80L, after.current().changeQuantity(), "120-40，不重复扣旧成交");
            assertEquals(Status.PENDING_REVIEW, after.status());

            session(rt, CAROL).reviewTarget("cr2", "rt2", updateAt + 3,
                    new ReviewDraft(TGT, 2, true, 80L, 40L, price("10.00"), 3 * DAY - 1, "复核"));
            rt.advanceTo(updateAt + 3);
            session(rt, BOB).confirmTarget("cc2", "ct2", updateAt + 4, TGT, 2, 1);
            rt.advanceTo(updateAt + 4);
            assertEquals(Status.ACTIVE, target(rt, TGT).status());
        }
    }

    // ------------------------------------------------------------------
    // 3. 风险暂停
    // ------------------------------------------------------------------

    @Test
    @DisplayName("风险暂停关闭新子单、不提前释放，T+1 撤单一次释放，到期回 PENDING_REVIEW")
    void riskHaltStopsNewChildrenAndCancelsAfterConfirmation() throws IOException {
        try (WorldRuntime rt = open("halt.journal")) {
            establishTarget(rt, 0L, 500_000L, 400_000L, 100L, 100L, 40L);

            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
            child(rt, DAVE, "c2", 40L, "10.00", 200_001L);
            rt.advanceTo(200_001L);
            assertEquals(800_00L, cents(frozen(rt)));

            session(rt, CAROL).haltTarget("ch1", "ht1", 201_000L,
                    new HaltDraft("halt-1", TGT, 1, 250_000L, "实验风险暂停"));
            rt.advanceTo(201_000L);
            assertEquals(Status.HALTED, target(rt, TGT).status());
            assertEquals(800_00L, cents(frozen(rt)), "暂停不得提前释放额度或提前撤单");
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status());

            child(rt, DAVE, "c3", 10L, "10.00", 201_500L);
            rt.advanceTo(201_500L);
            assertEquals(RoleTradingModel.REASON_HALTED, notice(rt, DAVE, t("c3")).reason());

            rt.advanceTo(202_000L);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status());
            assertEquals(0L, cents(frozen(rt)), "撤单确认后只释放一次");
            assertEquals(1_000_00L, cents(cash(rt, ACC_FUND)));

            rt.advanceTo(250_000L);
            assertEquals(Status.PENDING_REVIEW, target(rt, TGT).status(), "暂停到期回待审");
            child(rt, DAVE, "c4", 10L, "10.00", 251_000L);
            rt.advanceTo(251_000L);
            assertEquals(RoleTradingModel.REASON_NOT_CONFIRMED, notice(rt, DAVE, t("c4")).reason());

            session(rt, CAROL).reviewTarget("cr2", "rt2", 252_000L,
                    new ReviewDraft(TGT, 1, true, 100L, 40L, price("10.00"), 400_000L, "恢复复核"));
            rt.advanceTo(252_000L);
            session(rt, BOB).confirmTarget("cc2", "ct2", 253_000L, TGT, 1, 2);
            rt.advanceTo(253_000L);
            child(rt, DAVE, "c5", 10L, "10.00", 254_000L, 1, 2);
            rt.advanceTo(254_000L);
            assertEquals(10L, leaves(rt, DAVE, "c5"));
        }
    }

    // ------------------------------------------------------------------
    // 4. 审核收紧：先停新单、撤旧单，撤净后确认才生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审核降低额度先停新单并撤旧子单，撤净前确认被拒")
    void reviewTighteningWithdrawsOldChildrenBeforeReconfirmation() throws IOException {
        try (WorldRuntime rt = open("review.journal")) {
            establishTarget(rt, 0L, 500_000L, 400_000L, 100L, 100L, 40L);

            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
            assertEquals(400_00L, cents(frozen(rt)));

            session(rt, CAROL).reviewTarget("cr2", "rt2", 201_000L,
                    new ReviewDraft(TGT, 1, true, 20L, 10L, price("9.50"), 400_000L, "收紧"));
            rt.advanceTo(201_000L);
            assertEquals(Status.PENDING_REVIEW, target(rt, TGT).status(), "审核变更先停新单");
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status(),
                    "审核当刻旧单尚未撤，额度也不释放");
            assertEquals(400_00L, cents(frozen(rt)));

            child(rt, DAVE, "c9", 5L, "9.50", 201_500L, 1, 2);
            rt.advanceTo(201_500L);
            assertEquals(RoleTradingModel.REASON_NOT_CONFIRMED, notice(rt, DAVE, t("c9")).reason(),
                    "撤净并重新确认前不放新单");

            rt.advanceTo(202_000L);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status(),
                    "旧单在下一个 RULES 由系统真实撤销");
            assertEquals(0L, cents(frozen(rt)));

            session(rt, BOB).confirmTarget("cc3", "ct3", 203_000L, TGT, 1, 2);
            rt.advanceTo(203_000L);
            assertEquals(Status.ACTIVE, target(rt, TGT).status(), "撤净后才允许确认新额度");

            child(rt, DAVE, "c2", 20L, "9.50", 204_000L, 1, 2);
            rt.advanceTo(204_000L);
            assertEquals(RoleTradingModel.REASON_QUOTA_EXCEEDED, notice(rt, DAVE, t("c2")).reason(),
                    "新单笔上限是收紧后的 10 股");
        }
    }

    // ------------------------------------------------------------------
    // 5. 真实库存：当日买入不可卖，次日 T+1 才可卖
    // ------------------------------------------------------------------

    @Test
    @DisplayName("当日买入后同日 SELL 被唯一撮合拒绝且不落链接，次日才成功")
    void sameDaySaleIsRejectedByRealExchangeAndNextDaySucceeds() throws IOException {
        try (WorldRuntime rt = open("settle.journal")) {
            establishTarget(rt, 0L, 3 * DAY, 3 * DAY - 1, 40L, 40L, 40L);

            rt.scheduleOrder("o1", "ot1", 127_000L, INV, ACC_INV, SYM, Side.SELL,
                    OrderType.LIMIT, price("10.00"), 40L);
            rt.advanceTo(127_000L);
            child(rt, DAVE, "b1", 40L, "10.00", 128_000L);
            rt.advanceTo(128_000L);
            assertEquals(40L, held(rt, ACC_FUND));
            assertEquals(600_00L, cents(cash(rt, ACC_FUND)));

            session(rt, BOB).setTarget("cu1", "ut1", 129_000L,
                    new TargetDraft(TGT, 1, ORG_F, PRODUCT, ACC_FUND, SYM, 0L,
                            price("9.00"), 3 * DAY, RESEARCH));
            rt.advanceTo(130_000L);
            assertEquals(2, target(rt, TGT).current().version());
            assertEquals(Side.SELL, target(rt, TGT).current().side(), "只形成待执行意图");
            assertEquals(40L, held(rt, ACC_FUND), "当日没有卖出任何股份");

            session(rt, CAROL).reviewTarget("cr2", "rt2", 131_000L,
                    new ReviewDraft(TGT, 2, true, 40L, 20L, price("9.00"), 3 * DAY - 1, "反向"));
            rt.advanceTo(131_000L);
            session(rt, BOB).confirmTarget("cc2", "ct2", 132_000L, TGT, 2, 1);
            rt.advanceTo(132_000L);

            int ordersBefore = orderCount(rt);
            int linksBefore = children(rt);
            child(rt, DAVE, "s1", 20L, "9.00", 133_000L, 2, 1);
            rt.advanceTo(133_000L);
            assertEquals(WorldExchange.REASON_INSUFFICIENT_SHARES, notice(rt, DAVE, t("s1")).reason(),
                    "当日买入不可卖由真实台账拒绝");
            assertEquals(ordersBefore, orderCount(rt), "拒单不落订单");
            assertEquals(linksBefore, children(rt), "拒单不落链接");
            assertEquals(40L, held(rt, ACC_FUND));

            child(rt, DAVE, "s2", 20L, "9.00", DAY + 1, 2, 1);
            rt.advanceTo(DAY + 1);
            assertEquals(linksBefore + 1, children(rt), "次日 T+1 受理并落链接");
            assertEquals(20L, leaves(rt, DAVE, "s2"));
            assertEquals(40L, held(rt, ACC_FUND), "挂单只冻结不减少持仓");
        }
    }

    // ------------------------------------------------------------------
    // 6. 任职结束、离任会话、多组织、跨人引用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("任职结束撤本人子单；离任会话只读可用，动作完成期拒绝，重新任职不复活")
    void appointmentEndAndDepartedSessionsStayIsolated() throws IOException {
        try (WorldRuntime rt = openIsolation("isolation.journal")) {
            establishTarget(rt, 0L, 2 * DAY, 500_000L, 100L, 100L, 40L);
            WorldRoleSession staleErin = rt.openRoleSession(ERIN);

            child(rt, ERIN, "e1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
            assertEquals("A-5", staleErin.view().appointments().get(0).appointmentId());

            rt.scheduleOrder("o1", "ot1", ERIN_ENDS - 1, INV, ACC_INV, SYM, Side.SELL,
                    OrderType.LIMIT, price("10.00"), 20L);
            rt.advanceTo(ERIN_ENDS - 1);
            assertEquals(20L, orderOf(rt, "e1").filled(), "结束前一刻仍可合法成交");
            rt.advanceTo(ERIN_ENDS);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "e1").status());
            assertEquals(20L, orderOf(rt, "e1").filled(), "保留此前成交");
            assertEquals(0L, cents(frozen(rt)));

            // 离任者仍能开会话并查看，但岗位动作在完成期被拒
            assertEquals("SCHEDULED:" + t("e9"), staleErin.submitChild("ex", t("e9"),
                    ERIN_ENDS + 1, new ChildDraft(TGT, 1, 1, "e9", 10L, price("10.00"))));
            rt.advanceTo(ERIN_ENDS + 1);
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, notice(rt, ERIN, t("e9")).reason());
            assertEquals(1, children(rt), "被拒不落链接");

            // 同人重新上任：新会话可前进，旧会话仍不能借新任职
            rt.scheduleAppointment("ap2", "at2", ERIN_ENDS + 2, "A-5b", ERIN, ORG_F,
                    Role.EXECUTION_TRADER, ERIN_ENDS + 2, END);
            rt.advanceTo(ERIN_ENDS + 2);
            WorldRoleSession fresh = rt.openRoleSession(ERIN);
            assertEquals("A-5b", fresh.view().appointments().get(0).appointmentId());
            fresh.submitChild("en", "tn", ERIN_ENDS + 3,
                    new ChildDraft(TGT, 1, 1, "e10", 10L, price("10.00")));
            rt.advanceTo(ERIN_ENDS + 3);
            assertEquals(10L, leaves(rt, ERIN, "e10"));

            staleErin.submitChild("ex2", "tx2", ERIN_ENDS + 4,
                    new ChildDraft(TGT, 1, 1, "e11", 10L, price("10.00")));
            rt.advanceTo(ERIN_ENDS + 4);
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, notice(rt, ERIN, "tx2").reason(),
                    "旧会话不得借新任职执行");

            session(rt, CAROL).cancelChild("cx2", "ctx2", ERIN_ENDS + 5,
                    new ChildRef(TGT, ERIN, "e10"));
            rt.advanceTo(ERIN_ENDS + 5);
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, notice(rt, CAROL, "ctx2").reason());
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "e10").status());

            WorldRoleSession bob = rt.openRoleSession(BOB);
            assertEquals(2, bob.view().appointments().size());
            assertEquals(2, bob.view().appointments().stream()
                    .map(RoleTradingView.Appointment::organizationPartyId).distinct().count());
        }
    }

    // ------------------------------------------------------------------
    // 7. 命名空间、幂等、未知/无权统一
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(person,clientId) 命名空间无碰撞、同内容重放不写盘、未知与无权同一条原因")
    void commandNamespaceIdempotencyAndUniformDenial() throws IOException {
        try (WorldRuntime rt = open("namespace.journal")) {
            establishTarget(rt, 0L, 500_000L, 400_000L, 100L, 100L, 40L);

            String first = session(rt, DAVE).submitChild("dup", "task-dup", 200_000L,
                    new ChildDraft(TGT, 1, 1, "k1", 10L, price("10.00")));
            assertEquals("SCHEDULED:task-dup", first, "回报只含 clientTaskId");
            rt.advanceTo(200_000L);
            long afterFirst = rt.snapshot().position();

            assertEquals(first, session(rt, DAVE).submitChild("dup", "task-dup", 200_000L,
                    new ChildDraft(TGT, 1, 1, "k1", 10L, price("10.00"))));
            assertEquals(afterFirst, rt.snapshot().position(), "同内容重放不写盘");

            assertThrows(IllegalArgumentException.class, () -> session(rt, DAVE).submitChild(
                    "dup", "task-dup", 200_010L,
                    new ChildDraft(TGT, 1, 1, "k1", 10L, price("10.00"))),
                    "同一命令换到期必须拒绝");

            assertEquals("SCHEDULED:task-dup", session(rt, BOB).submitChild("dup", "task-dup",
                    200_020L, new ChildDraft(TGT, 1, 1, "k1", 10L, price("10.00"))));
            rt.advanceTo(200_020L);
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, notice(rt, BOB, "task-dup").reason());
            assertEquals(1, children(rt), "被拒不建链接");

            session(rt, DAVE).submitChild("ghost", "task-ghost", 200_030L,
                    new ChildDraft("tgt-ghost", 1, 1, "k2", 10L, price("10.00")));
            rt.advanceTo(200_030L);
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED,
                    notice(rt, DAVE, "task-ghost").reason(), "未知与无权同一条原因");
            assertEquals(1, children(rt));

            session(rt, DAVE).submitChild("stale", "task-stale", 200_040L,
                    new ChildDraft(TGT, 9, 1, "k3", 10L, price("10.00")));
            rt.advanceTo(200_040L);
            assertEquals(RoleTradingModel.REASON_STALE_VERSION,
                    notice(rt, DAVE, "task-stale").reason(),
                    "已授权目标上旧版本返回真实原因");
        }
    }

    // ------------------------------------------------------------------
    // 8. 真实写失败：线程中断 → IOException、整帧不变、暂停、恢复只执行一次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交中断保留完整旧快照并暂停，恢复后同一撤单只生效一次")
    void writeFailureKeepsWholeSnapshotPausedAndRecoversOnce() throws IOException {
        Path journal = tempDir.resolve("write-fail.journal");
        WorldRuntime rt = open(journal);
        establishTarget(rt, 0L, 500_000L, 400_000L, 100L, 100L, 40L);
        child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
        rt.advanceTo(200_000L);

        session(rt, DAVE).cancelChild("cc1", "ct1", 300_000L, new ChildRef(TGT, DAVE, "c1"));
        rt.advanceTo(201_000L);
        WorldRuntime.Snapshot checkpoint = rt.snapshot();
        long checkpointPosition = checkpoint.position();

        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> rt.advanceTo(300_000L),
                    "真实写失败必须抛出 IOException");
        } finally {
            Thread.interrupted();
        }
        assertTrue(rt.isPaused(), "写入失败后暂停");
        WorldRuntime.Snapshot after = rt.snapshot();
        assertEquals(checkpointPosition, after.position(), "位置不前进");
        assertEquals(checkpoint.accounts(), after.accounts(), "现金不变");
        assertEquals(checkpoint.securities(), after.securities(), "证券不变");
        assertEquals(checkpoint.exchange(), after.exchange(), "订单与成交不变");
        assertEquals(checkpoint.trading(), after.trading(), "工作流不变");
        assertEquals(checkpoint.pending(), after.pending(), "待办仍保留撤单任务");
        assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status());
        try {
            rt.close();
        } catch (RuntimeException ignored) {
            // 通道已因中断关闭，不影响恢复证据
        }

        try (WorldRuntime resumed = open(journal)) {
            assertFalse(resumed.isPaused());
            assertEquals(checkpoint.position(), resumed.snapshot().position());
            assertEquals(checkpoint.exchange(), resumed.snapshot().exchange());
            resumed.advanceTo(300_000L);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(resumed, "c1").status());
            assertEquals(0L, cents(frozen(resumed)));
            assertEquals(1, children(resumed));
        }
        try (WorldRuntime again = open(journal)) {
            assertEquals(1, children(again), "第二次重启不重复链接");
            assertEquals(0L, cents(frozen(again)), "释放恰好一次");
            assertEquals(1_000_00L, cents(totalCash(again)));
        }
    }

    // ------------------------------------------------------------------
    // 9. 半帧恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已写完成帧截半后回滚到旧快照，重做撤单只释放一次")
    void halfFrameRecoveryAppliesCancelOnce() throws IOException {
        Path journal = tempDir.resolve("half.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 500_000L, 400_000L, 100L, 100L, 40L);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
        }
        Path checkpoint = copy("half-checkpoint.journal", journal);
        byte[] saved = Files.readAllBytes(checkpoint);
        Path broken = copy("half-broken.journal", checkpoint);
        Files.write(broken, new byte[]{0, 0, 0, 1, 0, 0, 0, 2}, StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> open(broken));
        assertEquals(saved.length, Files.readAllBytes(checkpoint).length, "损坏的是副本");

        Path done = copy("half-done.journal", checkpoint);
        long fullPosition;
        try (WorldRuntime rt = open(done)) {
            session(rt, DAVE).cancelChild("cc1", "ct1", 300_000L, new ChildRef(TGT, DAVE, "c1"));
            rt.advanceTo(300_000L);
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status());
            fullPosition = rt.snapshot().position();
        }
        long fullSize = Files.size(done);
        Path half = copy("half-truncated.journal", done);
        try (FileChannel channel = FileChannel.open(half, StandardOpenOption.WRITE)) {
            channel.truncate(fullSize - 4);
        }
        WorldRuntime.Snapshot recovered;
        try (WorldRuntime rt = open(half)) {
            assertEquals(fullPosition - 1, rt.snapshot().position(), "半帧批次不得生效");
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status());
            assertEquals(400_00L, cents(frozen(rt)), "撤单未确认，额度仍在");
            rt.advanceTo(300_001L);
            recovered = rt.snapshot();
        }
        try (WorldRuntime rt = open(half)) {
            assertEquals(recovered, rt.snapshot(), "再次重启保留完整已确认快照");
            rt.advanceTo(300_001L);
            assertEquals(recovered, rt.snapshot(), "重复推进不再追加撤单或时钟批次");
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status());
            assertEquals(0L, cents(frozen(rt)));
            assertEquals(1, children(rt));
        }
    }

    // ------------------------------------------------------------------
    // 10. 档案与开局
    // ------------------------------------------------------------------

    @Test
    @DisplayName("profile7 精确模块、初始任职须为登记读者、schema6 不出现 trading 字段")
    void profileSevenModulesAndLegacyShape() throws IOException {
        Map<String, Integer> missingTrading = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information")) {
            missingTrading.put(name, 1);
        }
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_ROLE_TRADING_EXPERIMENT_V1, missingTrading, Map.of(),
                RunManifest.MODEL_NONE), "收紧档案必须带 trading 模块");

        Path notReader = tempDir.resolve("not-reader.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(notReader, manifest(),
                OPENINGS, registry(), MFG, charter(),
                new RoleRun.Config(List.of(
                        new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND))),
                        List.of(new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER,
                                0L, END, null))),
                new InformationRun.Config(List.of(BOB, CAROL, DAVE))),
                "初始任职人必须具备已配置的信息读者能力");
        assertFalse(Files.exists(notReader), "写盘前失败不得留下文件");

        Path trading = tempDir.resolve("trading.journal");
        try (WorldRuntime rt = open(trading)) {
            assertTrue(rt.snapshot().trading().isPresent());
            assertEquals(0, rt.snapshot().trading().orElseThrow().trading().targets().size());
        }
        assertTrue(snapshotJson(trading).contains("\"trading\""), "profile7 持久化 trading");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static String t(String childId) {
        return "t-" + childId;
    }

    /** 选一个公平顺序严格晚于给定内部任务标识的标识。 */
    private static String fairOrderAfter(String prefix, String reference) {
        String ref = WorldScheduler.fairOrder(SEED, reference);
        for (int index = 0; index < 10_000; index++) {
            String candidate = prefix + index;
            if (WorldScheduler.fairOrder(SEED, candidate).compareTo(ref) > 0) {
                return candidate;
            }
        }
        throw new AssertionError("找不到公平顺序靠后的任务标识");
    }

    /** 走完研究→阅读→目标→审核→确认，停在 ACTIVE。 */
    private void establishTarget(WorldRuntime rt, long base, long deadline, long riskUntil,
                                 long absolute, long maxTotal, long maxChild) throws IOException {
        rt.schedulePublishDocument("c-src", "t-src", base + 500L, SRC_DOC, ALICE,
                Audience.organization(ORG_F), "订单线索", "渠道反馈订单改善，金额待证实。",
                "调研纪要", 60_000L, List.of("internal-src"));
        rt.advanceTo(base + 500L);
        session(rt, ALICE).startReading("c-r1", "t-r1", base + 1_000L, SRC_DOC, 1L);
        rt.advanceTo(base + 1_000L);
        rt.advanceTo(base + 61_000L);
        assertTrue(rt.canCite(ALICE, SRC_DOC, 1L));

        session(rt, ALICE).submitResearch("c-res", "t-res", base + 62_000L,
                new ResearchDraft(RESEARCH, ORG_F, PRODUCT, SYM, NOTE_DOC, "S 公司订单线索",
                        "渠道反馈订单改善，交付需持续核对。", List.of(new Citation(SRC_DOC, 1L))));
        rt.advanceTo(base + 62_000L);

        session(rt, BOB).startReading("c-r2", "t-r2", base + 63_000L, NOTE_DOC, 1L);
        rt.advanceTo(base + 63_000L);
        rt.advanceTo(base + 123_000L);
        assertTrue(rt.canCite(BOB, NOTE_DOC, 1L));

        session(rt, BOB).setTarget("c-tgt", "t-tgt", base + 124_000L,
                new TargetDraft(TGT, 0, ORG_F, PRODUCT, ACC_FUND, SYM, absolute,
                        price("10.00"), deadline, RESEARCH));
        rt.advanceTo(base + 124_000L);
        assertEquals(Status.PENDING_REVIEW, target(rt, TGT).status());

        session(rt, CAROL).reviewTarget("c-rev", "t-rev", base + 125_000L,
                new ReviewDraft(TGT, 1, true, maxTotal, maxChild, price("10.00"), riskUntil,
                        "实验任务输入"));
        rt.advanceTo(base + 125_000L);
        session(rt, BOB).confirmTarget("c-cfm", "t-cfm", base + 126_000L, TGT, 1, 1);
        rt.advanceTo(base + 126_000L);
        assertEquals(Status.ACTIVE, target(rt, TGT).status());
    }

    private void child(WorldRuntime rt, String person, String childId, long quantity,
                       String price, long due) throws IOException {
        child(rt, person, childId, quantity, price, due, 1, 1);
    }

    private void child(WorldRuntime rt, String person, String childId, long quantity, String price,
                       long due, int parentVersion, int riskRevision) throws IOException {
        session(rt, person).submitChild("c-" + childId, t(childId), due,
                new ChildDraft(TGT, parentVersion, riskRevision, childId, quantity, price(price)));
    }

    private Path copy(String name, Path source) throws IOException {
        Path target = tempDir.resolve(name);
        Files.copy(source, target);
        return target;
    }

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name));
    }

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), OPENINGS, registry(), MFG, charter(), roles(),
                info());
    }

    private static WorldRoleSession session(WorldRuntime rt, String person) throws IOException {
        return rt.openRoleSession(person);
    }

    private static RoleTradingView view(WorldRuntime rt, String person) throws IOException {
        return session(rt, person).view();
    }

    private static RoleTradingView.TargetView target(WorldRuntime rt, String id) throws IOException {
        for (RoleTradingView.TargetView target : view(rt, BOB).targets()) {
            if (target.targetId().equals(id)) {
                return target;
            }
        }
        throw new AssertionError("缺少目标视图：" + id);
    }

    private static long leaves(WorldRuntime rt, String person, String childId) throws IOException {
        for (RoleTradingView.ChildView child : view(rt, person).children()) {
            if (child.ref().childId().equals(childId)) {
                return child.leaves();
            }
        }
        throw new AssertionError("缺少子单视图：" + childId);
    }

    private static RoleTradingView.Notice notice(WorldRuntime rt, String person, String clientTaskId)
            throws IOException {
        for (RoleTradingView.Notice notice : view(rt, person).notices()) {
            if (notice.clientTaskId().equals(clientTaskId)) {
                return notice;
            }
        }
        throw new AssertionError("缺少回报：" + person + "/" + clientTaskId);
    }

    private static RoleTradingModel.State trading(WorldRuntime rt) {
        return rt.snapshot().trading().orElseThrow().trading();
    }

    private static WorldExchange.State exchange(WorldRuntime rt) {
        return rt.snapshot().exchange().orElseThrow();
    }

    private static long held(WorldRuntime rt, String accountId) {
        return RoleTradingModel.held(rt.snapshot().securities().orElseThrow(), accountId, SYM);
    }

    private static BigDecimal frozen(WorldRuntime rt) {
        return WorldExchange.frozenCash(exchange(rt), ACC_FUND);
    }

    private static int orderCount(WorldRuntime rt) {
        return exchange(rt).orders().size();
    }

    private static int children(WorldRuntime rt) {
        return trading(rt).children().size();
    }

    private static WorldExchange.Order orderOf(WorldRuntime rt, String childId) {
        for (RoleTradingModel.ChildLink link : trading(rt).children()) {
            if (link.childId().equals(childId)) {
                return exchange(rt).order(link.orderId());
            }
        }
        throw new AssertionError("缺少订单链接：" + childId);
    }

    private static BigDecimal cash(WorldRuntime rt, String accountId) {
        return rt.snapshot().accounts().get(accountId).cash();
    }

    /** 交易双方的现金合计：产品账户 + 外部持有人，守恒式断言只看这两个账户。 */
    private static BigDecimal totalCash(WorldRuntime rt) {
        return cash(rt, ACC_FUND).add(cash(rt, ACC_INV));
    }

    private static long cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private String snapshotJson(Path journal) throws IOException {
        try (WorldJournal reader = new WorldJournal(journal, RUN)) {
            for (WorldCommit commit : reader.commits()) {
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                        return event.payload();
                    }
                }
            }
        }
        throw new AssertionError("日志中没有状态快照");
    }

    // ------------------------------------------------------------------
    // 清单与开局
    // ------------------------------------------------------------------

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(ACC_FUND, PRODUCT, new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_FUND_G, PRODUCT_G, new BigDecimal("500.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading")) {
            modules.put(name, 1);
        }
        return new RunManifest(RUN, SEED, RunManifest.RULES_ROLE_TRADING_EXPERIMENT_V1, modules,
                Map.of("fixture", "role-trading-runtime-v1"), RunManifest.MODEL_NONE);
    }

    private static RoleRun.Config roles() {
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)),
                new RoleAccess.ProductMandate(ORG_G, PRODUCT_G, List.of(ACC_FUND_G)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L, END, null),
                new RoleAccess.Appointment("A-2G", BOB, ORG_G, Role.PORTFOLIO_MANAGER, 0L, END, null),
                new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L, END, null),
                new RoleAccess.Appointment("A-4", DAVE, ORG_F, Role.EXECUTION_TRADER, 0L, END, null)));
        return new RoleRun.Config(mandates, appointments);
    }

    /** 任职结束场景专用：ORG-F 只有段期执行员 erin，避免同机构同岗位任职重叠。 */
    private WorldRuntime openIsolation(String name) throws IOException {
        return new WorldRuntime(tempDir.resolve(name), manifest(), OPENINGS, registry(), MFG,
                charter(), new RoleRun.Config(List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)),
                new RoleAccess.ProductMandate(ORG_G, PRODUCT_G, List.of(ACC_FUND_G))),
                List.of(
                        new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END,
                                null),
                        new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L,
                                END, null),
                        new RoleAccess.Appointment("A-2G", BOB, ORG_G, Role.PORTFOLIO_MANAGER, 0L,
                                END, null),
                        new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L, END,
                                null),
                        new RoleAccess.Appointment("A-5", ERIN, ORG_F, Role.EXECUTION_TRADER, 0L,
                                ERIN_ENDS, null))), info());
    }

    private static InformationRun.Config info() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG_F, PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party(ORG_G, PartyKind.ORGANIZATION, "外部机构"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner(PRODUCT_G, OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("orgf", OwnerKind.ORGANIZATION, ORG_F),
                new OpeningRegistry.PropertyOwner("orgg", OwnerKind.ORGANIZATION, ORG_G),
                new OpeningRegistry.PropertyOwner(ALICE, OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
