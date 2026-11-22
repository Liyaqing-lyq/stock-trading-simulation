package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.ManufacturingTemplateModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 三制造公司测试夹具：真实董事预算与任免、真实资料阅读、真实经营命令的公共步骤。
 *
 * <p>不 mock、不改状态：所有事实都由 {@link WorldRuntime} 的真实入口按冻结章程与焦点工时产生。</p>
 */
final class OperationsFixture {

    static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;

    private OperationsFixture() {
    }

    /**
     * 发布两份材料（预算/任免）→ 读者真实读完 → 召集/到会/材料/提案/表决/实施预算 →
     * 同流程任免一名非董事经理。
     *
     * @param world     世界
     * @param company   公司
     * @param directors 五名董事（首名为主席）
     * @param manager   非董事经理候选人
     * @param prefix    命令前缀（运行内唯一）
     * @param periods   预算期间（各期间同额度）
     * @return 经理任职起始时点（排程时点在返回后已经推进到该时刻）
     * @throws Exception 排程或推进失败
     */
    static long grantBudgetAndAppoint(WorldRuntime world, String company, List<String> directors,
            String manager, String prefix, List<Long> periods) throws Exception {
        List<BoardModel.BudgetLine> lines = new ArrayList<>();
        for (long period : periods) {
            lines.add(new BoardModel.BudgetLine(period, BoardModel.BudgetCategory.PROCUREMENT,
                    new BigDecimal("1000000.00")));
            lines.add(new BoardModel.BudgetLine(period, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                    new BigDecimal("1000000.00")));
        }
        return grantLinesAndAppoint(world, company, directors, manager, prefix, lines);
    }

    static long grantLinesAndAppoint(WorldRuntime world, String company, List<String> directors,
            String manager, String prefix, List<BoardModel.BudgetLine> lines) throws Exception {
        long now = Math.max(world.snapshot().timeMillis(), 1L);
        String budgetDoc = prefix + "-budget-doc";
        String appointmentDoc = prefix + "-appt-doc";
        world.schedulePublishDocument(prefix + "-pub-b", prefix + "-pub-b-task", now + 10L,
                budgetDoc, WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "经营预算材料",
                "本公司经营预算实验材料", "本公司经营预算实验材料", 10L, List.of());
        world.schedulePublishDocument(prefix + "-pub-a", prefix + "-pub-a-task", now + 10L,
                appointmentDoc, WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "任免材料",
                "本公司任免实验材料", "本公司任免实验材料", 10L, List.of());
        List<String> readers = new ArrayList<>(directors);
        readers.add(manager);
        int index = 0;
        for (String reader : readers) {
            world.scheduleStartReading(prefix + "-rb-" + reader, prefix + "-rb-task-" + reader,
                    now + 20L + index * 2L, reader, budgetDoc, 1L);
            world.scheduleStartReading(prefix + "-ra-" + reader, prefix + "-ra-task-" + reader,
                    now + 60L + index * 2L, reader, appointmentDoc, 1L);
            index++;
        }
        long convene = now + 1_000L;
        world.scheduleCompanyBoardConvene(prefix + "-bc", prefix + "-bc-task", convene, company,
                directors.get(0), prefix + "-budget-meeting", convene + 50L, convene + 100L,
                convene + 3_000L, false);
        List<String> voters = directors.subList(0, 4);
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardAttend(prefix + "-ba-" + i, prefix + "-ba-task-" + i,
                    convene + 10L + i, company, voters.get(i), prefix + "-budget-meeting");
        }
        world.scheduleCompanyBoardMaterial(prefix + "-bm", prefix + "-bm-task", convene + 30L,
                company, directors.get(0), prefix + "-budget-meeting", "m1", budgetDoc, 1L);
        world.scheduleCompanyBoardBudgetProposal(prefix + "-bp", prefix + "-bp-task",
                convene + 40L, company, directors.get(0), prefix + "-budget-meeting", lines);
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardVote(prefix + "-bv-" + i, prefix + "-bv-task-" + i,
                    convene + 2_000L + i, company, voters.get(i), prefix + "-budget-meeting",
                    BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCompanyBoardImplement(prefix + "-bi", prefix + "-bi-task", convene + 4_000L,
                company, directors.get(0), prefix + "-budget-meeting");

        long appointAt = convene + 5_000L;
        long startsAt = appointAt + 6_000L;
        world.scheduleCompanyBoardConvene(prefix + "-ac", prefix + "-ac-task", appointAt, company,
                directors.get(0), prefix + "-appt-meeting", appointAt + 50L, appointAt + 100L,
                appointAt + 3_000L, true);
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardAttend(prefix + "-aa-" + i, prefix + "-aa-task-" + i,
                    appointAt + 10L + i, company, voters.get(i), prefix + "-appt-meeting");
        }
        world.scheduleCompanyBoardMaterial(prefix + "-am", prefix + "-am-task", appointAt + 30L,
                company, directors.get(0), prefix + "-appt-meeting", "m1", appointmentDoc, 1L);
        world.scheduleCompanyBoardAppointmentProposal(prefix + "-ap", prefix + "-ap-task",
                appointAt + 40L, company, directors.get(0), prefix + "-appt-meeting",
                new BoardModel.AppointmentRequest(prefix + "-mgr", manager, company,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        startsAt, startsAt + 90L * DAY));
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardVote(prefix + "-av-" + i, prefix + "-av-task-" + i,
                    appointAt + 2_000L + i, company, voters.get(i), prefix + "-appt-meeting",
                    BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCompanyBoardImplement(prefix + "-ai", prefix + "-ai-task", appointAt + 4_000L,
                company, directors.get(0), prefix + "-appt-meeting");
        world.advanceTo(startsAt);
        return startsAt;
    }

    /** 发布一份资料并让指定人物真实读完；返回读完时点。 */
    static long publishAndRead(WorldRuntime world, String docId, List<String> readers,
            String prefix) throws Exception {
        long now = world.snapshot().timeMillis();
        long published = now + 100L;
        world.schedulePublishDocument(prefix + "-pub", prefix + "-pub-task", published, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "认证依据材料", "客户认证依据实验材料", "客户认证依据实验材料", 1_000L,
                List.of());
        int index = 0;
        for (String reader : readers) {
            world.scheduleStartReading(prefix + "-rd-" + reader, prefix + "-rd-task-" + reader,
                    published + 100L + index * 10L, reader, docId, 1L);
            index++;
        }
        // 阅读耗时取自资料版本的冻结值，不在测试里硬编码；按世界时钟推进到真实读完为止。
        String last = readers.get(readers.size() - 1);
        for (int step = 0; step < 400; step++) {
            if (hasObservation(world, last, docId)) {
                return world.snapshot().timeMillis();
            }
            world.advanceTo(world.snapshot().timeMillis() + 500L);
        }
        throw new IllegalStateException("阅读未在预期时点完成：" + last + " / " + docId);
    }

    private static boolean hasObservation(WorldRuntime world, String person, String docId) {
        for (InformationModel.ReadObservation observation : InformationModel.observations(
                world.snapshot().information().orElseThrow().information(), person)) {
            if (observation.docId().equals(docId)) {
                return true;
            }
        }
        return false;
    }

    /** @return 本人在该资料上的真实阅读标识（来自已完成阅读，不是自填字符串） */
    static String readIdOf(WorldRuntime world, String person, String docId) {
        for (InformationModel.ReadObservation observation : InformationModel.observations(
                world.snapshot().information().orElseThrow().information(), person)) {
            if (observation.docId().equals(docId)) {
                return observation.readId();
            }
        }
        throw new IllegalStateException("找不到本人已完成阅读：" + person + " / " + docId);
    }

    /** 经营动作字段构建器（显式字段名，避免位置参数错位）。 */
    static Builder op() {
        return new Builder();
    }

    /** 字段构建器；未设置的字段保持 null，由纯核按动作拒绝多余/缺失。 */
    static final class Builder {
        private String materialId;
        private Long quantity;
        private String productId;
        private Integer generation;
        private String purchaseOrderId;
        private String batchId;
        private String trialId;
        private String evidenceId;
        private String customerId;
        private Integer round;
        private String contractId;
        private BigDecimal unitPrice;
        private Long promisedAt;
        private Long expiresAt;
        private String receivableId;
        private String projectId;
        private BigDecimal capex;
        private Long buildMillis;
        private Integer rampPeriods;
        private Long addCapacity;
        private Boolean failed;
        private BigDecimal price;
        private Long period;

        Builder material(String id, long qty) {
            this.materialId = id;
            this.quantity = qty;
            return this;
        }

        Builder product(String id, int gen, long qty) {
            this.productId = id;
            this.generation = gen;
            this.quantity = qty;
            return this;
        }

        Builder purchaseOrder(String id) {
            this.purchaseOrderId = id;
            return this;
        }

        Builder batch(String id) {
            this.batchId = id;
            return this;
        }

        Builder cert(String trial, String evidence, String customer, String product, int gen,
                int trialRound) {
            this.trialId = trial;
            this.evidenceId = evidence;
            this.customerId = customer;
            this.productId = product;
            this.generation = gen;
            this.round = trialRound;
            return this;
        }

        Builder trial(String id) {
            this.trialId = id;
            return this;
        }

        Builder contract(String id, String customer, String product, int gen, long qty,
                BigDecimal unitPrice, long promisedAt, long expiresAt) {
            this.contractId = id;
            this.customerId = customer;
            this.productId = product;
            this.generation = gen;
            this.quantity = qty;
            this.unitPrice = unitPrice;
            this.promisedAt = promisedAt;
            this.expiresAt = expiresAt;
            return this;
        }

        Builder delivery(String id, long qty, String receivable) {
            this.contractId = id;
            this.quantity = qty;
            this.receivableId = receivable;
            return this;
        }

        Builder receivable(String id) {
            this.receivableId = id;
            return this;
        }

        Builder expansion(String id, BigDecimal capex, long buildMillis, int ramp, long add) {
            this.projectId = id;
            this.capex = capex;
            this.buildMillis = buildMillis;
            this.rampPeriods = ramp;
            this.addCapacity = add;
            return this;
        }

        Builder expansionOutcome(String id, boolean failed) {
            this.projectId = id;
            this.failed = failed;
            return this;
        }

        Builder residual(String id, BigDecimal price) {
            this.projectId = id;
            this.price = price;
            return this;
        }

        Builder period(long value) {
            this.period = value;
            return this;
        }

        CompanyOperationsRun.Fields build() {
            return new CompanyOperationsRun.Fields(materialId, quantity, productId, generation,
                    purchaseOrderId, batchId, trialId, evidenceId, customerId, round, contractId,
                    unitPrice, promisedAt, expiresAt, receivableId, projectId, capex, buildMillis,
                    rampPeriods, addCapacity, failed, price, period);
        }
    }

    /** 认证试验在台账里的复合键（客户|产品|代际|轮次）。 */
    static String trialKey(String customer, String product, int generation, int trialRound) {
        return customer + "|" + product + "|" + generation + "|" + trialRound;
    }

    /** 采购单标识（与生产端同一派生式）。 */
    static String poId(String commandId) {
        return commandId + ":po";
    }

    /** 批次标识（与生产端同一派生式）。 */
    static String batchId(String commandId) {
        return commandId + ":batch";
    }
}
