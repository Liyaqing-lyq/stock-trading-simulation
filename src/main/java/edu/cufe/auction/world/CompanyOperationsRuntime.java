package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 三类制造公司的世界接缝：把纯核 {@link ManufacturingTemplateModel} 的一次动作、董事会预算与
 * 唯一现金权威组合成同一候选帧。
 *
 * <p><b>纯函数</b>：本类不持有账、不写日志、不看时钟。每次调用都用调用方给出的
 * 「上一帧 + 原始命令 + 当前账户表 + 当前资金三态」重算候选；拒绝时原状态、原账、原董事会
 * 一并返回，不产生半写。</p>
 *
 * <p><b>职责边界</b></p>
 * <ul>
 *   <li>授权与焦点工时：由 {@code WorldRuntime} 在排程时校验（真实任职 + 焦点窗口），本类只按
 *       已冻结的发起人重新核对一次"人物是否仍是该公司经理"，不采信自报字符串。</li>
 *   <li>预算：采购/人工/认证/扩张/期间费用各自向该公司真实 {@link BoardModel} 承诺额度；
 *       没有已批预算就不允许经营动作。</li>
 *   <li>资金：每次都使用调用方传入的当前 {@link FundState} 与 {@link FundPayer}；本类不缓存
 *       旧三态，也不跨桶补足。</li>
 *   <li>物量、认证、合同、应收、失败与容量语义：全部沿用纯核，不在这里另立第二套规则。</li>
 * </ul>
 */
final class CompanyOperationsRuntime {

    /** 采购单标识唯一派生式：同一命令永远得到同一采购单，重演不会换单。 */
    private static final String PO_SUFFIX = ":po";
    /** 批次标识唯一派生式。 */
    private static final String BATCH_SUFFIX = ":batch";

    private CompanyOperationsRuntime() {
    }

    /** 自动到期子任务：只处理已经开始的采购/批次/认证/建设。 */
    record Child(String action, String businessId, long dueAt) {
        Child {
            Objects.requireNonNull(action, "子任务动作");
            if (businessId == null || businessId.isBlank()) {
                throw new IllegalArgumentException("子任务必须绑定业务标识");
            }
        }
    }

    /**
     * 一次候选结果。
     *
     * @param run        候选经营子集（拒绝时为入参）
     * @param board      候选董事会（拒绝时为入参）
     * @param funds      候选完整资金三态；付款拒绝回执也随同保存
     * @param accepted   是否成功
     * @param reason     纯核或预算结果码
     * @param businessId 关联业务标识，无则空串
     * @param amount     本次实际转移的现金（绝对额，无现金动作为零）
     * @param children   需要按期自动完成的子任务
     */
    record Applied(CompanyOperationsRun run, BoardRun board,
                   FundState funds, boolean accepted, String reason,
                   String businessId, BigDecimal amount, List<Child> children) {

        static Applied reject(CompanyOperationsRun run, BoardRun board,
                              FundState funds, String reason) {
            return new Applied(run, board, funds, false, reason, "", CashLedger.money(BigDecimal.ZERO),
                    List.of());
        }
    }

    /**
     * 一条付款腿的账户对：出款公司账户与收款对手账户。
     *
     * @param sourceAccountId 出款账户（公司）
     * @param targetAccountId 收款账户（外部对手方）；纯收款项为出款方对手、公司为收款方
     */
    record Leg(String sourceAccountId, String targetAccountId) {
    }

    /** @return 采购单标识（由命令标识唯一派生） */
    static String purchaseOrderIdFor(String commandId) {
        return commandId + PO_SUFFIX;
    }

    /** @return 批次标识（由命令标识唯一派生） */
    static String batchIdFor(String commandId) {
        return commandId + BATCH_SUFFIX;
    }

    /**
     * 解析该动作的资金腿（只有真正动钱的动作返回非 null）。
     *
     * @param ledger 公司台账
     * @param action 动作
     * @param fields 字段
     * @return 资金腿；不动钱时返回 null
     */
    static Leg legOf(ManufacturingTemplateModel.State ledger,
                     CompanyOperationsRun.Action action, CompanyOperationsRun.Fields fields) {
        ManufacturingTemplateModel.Accounts accounts = ledger.accounts();
        return switch (action) {
            case RECEIVE_MATERIAL -> {
                ManufacturingTemplateModel.PurchaseOrder order = fields.purchaseOrderId() == null
                        ? null : ledger.purchases().get(fields.purchaseOrderId());
                String supplier = order == null ? null
                        : accounts.supplierAccounts().get(order.supplierId());
                yield new Leg(accounts.companyAccountId(), supplier);
            }
            case START_BATCH -> new Leg(accounts.companyAccountId(), accounts.opsAccountId());
            case REQUEST_CERT -> new Leg(accounts.companyAccountId(), accounts.certifierAccountId());
            case START_EXPANSION ->
                    new Leg(accounts.companyAccountId(), accounts.contractorAccountId());
            case CLOSE_PERIOD -> new Leg(accounts.companyAccountId(), accounts.opsAccountId());
            case DELIVER -> new Leg(accounts.companyAccountId(), accounts.opsAccountId());
            case COLLECT -> {
                ManufacturingTemplateModel.Receivable receivable = fields.receivableId() == null
                        ? null : ledger.receivables().get(fields.receivableId());
                String customer = receivable == null ? null
                        : accounts.customerAccounts().get(receivable.customerId());
                yield new Leg(customer, accounts.companyAccountId());
            }
            case SELL_RESIDUAL -> new Leg(accounts.disposalBuyerAccountId(),
                    accounts.companyAccountId());
            default -> null;
        };
    }

    /**
     * 应用一次经营动作。
     *
     * @param run            当前经营子集
     * @param board          该公司真实董事会
     * @param information    当前资料阅读子集（认证证据来源）
     * @param companyPartyId 公司
     * @param action         动作
     * @param fields         字段（已按动作校验）
     * @param commandId      原始命令（资金腿与纯核幂等键）
     * @param personPartyId  发起人（真实经理，已在排程期校验；本处再核对本人）
     * @param actor          纯核 actor（公司财产主体）
     * @param now            执行时点
     * @param funds          当前资金三态（不得复用旧结果）
     * @param payer          为本次资金腿绑定的付款人；不动钱的动作可为 null
     * @param requireFundedContract 是否按冻结规则在签订新合同时检查客户未承诺现金
     * @return 候选结果
     */
    static Applied apply(CompanyOperationsRun run, BoardRun board, InformationRun information,
                         String companyPartyId, CompanyOperationsRun.Action action,
                         CompanyOperationsRun.Fields fields, String commandId, String personPartyId,
                         String actor, long now, FundState funds, FundPayer payer, boolean requireFundedContract) {
        if (run == null) {
            return Applied.reject(null, board, null, "OPERATIONS_NOT_READY");
        }
        CompanyOperationsRun.Company company = run.company(companyPartyId);
        if (company == null) {
            return Applied.reject(run, board, funds, "UNKNOWN_COMPANY");
        }
        ManufacturingTemplateModel.State ledger = company.ledger();
        if (board == null) {
            return Applied.reject(run, board, funds, "NO_BOARD");
        }
        if (!actor.equals(ledger.accounts().companyOwnerId())) {
            return Applied.reject(run, board, funds, "ACTOR_NOT_COMPANY_OWNER");
        }
        if (action == CompanyOperationsRun.Action.START_EXPANSION
                && (company.expansionOffer() == null || !company.expansionOffer().matches(fields))) {
            return Applied.reject(run, board, funds, "EXPANSION_OFFER_MISMATCH");
        }
        Map<String, CashLedger.CashAccount> base = funds.accounts();
        BigDecimal cashBefore = balance(base, company.cashAccountId());
        long period = now / ManufacturingTemplateModel.PERIOD_MILLIS;

        // 1) 预算承诺（只有需要预算的动作）。承诺失败即整体拒绝，不调纯核、不动现金。
        BoardModel.State budgeted = board.board();
        String commitment = null;
        BigDecimal commitmentAmount = null;
        switch (action) {
            case ORDER_MATERIAL -> {
                BigDecimal price = visiblePrice(ledger, fields.materialId(), now);
                if (price == null) {
                    return Applied.reject(run, board, funds, ManufacturingTemplateModel.NO_QUOTE);
                }
                commitment = "ops-po:" + purchaseOrderIdFor(commandId);
                commitmentAmount = CashLedger.money(
                        price.multiply(BigDecimal.valueOf(fields.quantity())));
            }
            case START_BATCH -> {
                ManufacturingTemplateModel.ProductSpec product = ledger.spec()
                        .product(fields.productId(), fields.generation());
                if (product == null) {
                    return Applied.reject(run, board, funds, ManufacturingTemplateModel.UNKNOWN_PRODUCT);
                }
                commitment = "ops-labor:" + batchIdFor(commandId);
                commitmentAmount = CashLedger.money(
                        product.laborPerUnit().multiply(BigDecimal.valueOf(fields.quantity())));
            }
            case REQUEST_CERT -> {
                if (ledger.spec().certFee().signum() <= 0) {
                    return Applied.reject(run, board, funds,
                            ManufacturingTemplateModel.CERT_NOT_REQUIRED);
                }
                commitment = "ops-cert:" + fields.trialId();
                commitmentAmount = ledger.spec().certFee();
            }
            case START_EXPANSION -> {
                commitment = "ops-capex:" + fields.projectId();
                commitmentAmount = CashLedger.money(fields.capex());
            }
            // 冻结开局设备的固定费用是已有义务：关账照实计费、付款不足留应付。
            // 不凭空制造董事预算，也不让无人任职阻断折旧与后续月份。
            default -> commitmentAmount = null;
        }
        boolean immediateSpend = action == CompanyOperationsRun.Action.START_BATCH
                || action == CompanyOperationsRun.Action.REQUEST_CERT
                || action == CompanyOperationsRun.Action.START_EXPANSION;
        if (commitment != null) {
            BoardModel.Outcome reserved = BoardModel.reserveBudget(budgeted, now, commitment,
                    period, category(action), commitmentAmount);
            if (!reserved.accepted()) {
                return Applied.reject(run, board, funds, reserved.reason());
            }
            budgeted = reserved.state();
            if (immediateSpend) {
                BoardModel.Outcome spent = BoardModel.spendBudget(budgeted, now, commitment,
                        commitmentAmount);
                if (!spent.accepted()) {
                    return Applied.reject(run, board, funds, spent.reason());
                }
                budgeted = spent.state();
            }
        }

        // 2) 纯核动作（物量/认证/合同/应收/失败/容量语义全部在这里）。
        Outcome outcome;
        try {
            outcome = call(ledger, information, action, fields, commandId, personPartyId, actor,
                    now, funds, payer);
        } catch (RuntimeException invalid) {
            return Applied.reject(run, board, funds, "OPERATIONS_REJECTED:" + invalid.getMessage());
        }
        if (!outcome.accepted()) {
            // 到货付不起是纯核的显式终态：如实回写台账并释放采购承诺，其余拒绝保持原账。
            if (ManufacturingTemplateModel.FAILED_NO_CASH.equals(outcome.reason())) {
                BoardModel.State released = releasePurchaseBudget(budgeted, now, commitment,
                        commitmentAmount);
                CompanyOperationsRun failed = run.withLedger(companyPartyId, outcome.ledger());
                return new Applied(failed,
                        new BoardRun(released, board.reports(), board.periodEvidence()),
                        outcome.funds(), false, outcome.reason, outcome.businessId(),
                        CashLedger.money(BigDecimal.ZERO), List.of());
            }
            return Applied.reject(run, board, outcome.funds(), outcome.reason());
        }

        // 签约只核承诺，不冻结现金；已签合同的收款仍取决于到期时客户是否实际有钱。
        if (requireFundedContract && action == CompanyOperationsRun.Action.SIGN_CONTRACT
                && !ledger.contracts().containsKey(fields.contractId())) {
            var contract = outcome.ledger().contracts().get(fields.contractId());
            String account = ledger.accounts().customerAccounts().get(contract.customerId());
            BigDecimal value = contract.unitPrice().multiply(BigDecimal.valueOf(contract.quantity()));
            if (ManufacturingCustomerOffers.uncommittedCash(run, account, base).compareTo(value) < 0)
                return Applied.reject(run, board, funds, "CUSTOMER_AVAILABLE_CASH_INSUFFICIENT");
        }

        // 3) 到货把采购承诺转已花（金额以采购单锁定单价为准，不用当前报价重估）。
        if (action == CompanyOperationsRun.Action.RECEIVE_MATERIAL) {
            ManufacturingTemplateModel.PurchaseOrder order = outcome.ledger().purchases()
                    .get(fields.purchaseOrderId());
            if (order != null) {
                BigDecimal cost = CashLedger.money(
                        order.unitPrice().multiply(BigDecimal.valueOf(order.quantity())));
                BoardModel.Outcome spent = BoardModel.spendBudget(budgeted, now,
                        "ops-po:" + order.purchaseOrderId(), cost);
                if (!spent.accepted()) {
                    return Applied.reject(run, board, funds, spent.reason());
                }
                budgeted = spent.state();
            }
        }

        CompanyOperationsRun next = run.withLedger(companyPartyId, outcome.ledger());
        BigDecimal cashAfter = balance(outcome.accounts(), company.cashAccountId());
        BigDecimal moved = CashLedger.money(cashAfter.subtract(cashBefore).abs());
        return new Applied(next, new BoardRun(budgeted, board.reports(), board.periodEvidence()),
                outcome.funds(), true, outcome.reason(), outcome.businessId(), moved,
                children(companyPartyId, action, fields, commandId, outcome.ledger(),
                        outcome.businessId()));
    }

    /** 纯核结果：候选台账、候选账户表、业务标识与结果码。 */
    private record Outcome(boolean accepted, String reason,
                           ManufacturingTemplateModel.State ledger,
                           FundState funds, String businessId) {
        Map<String, CashLedger.CashAccount> accounts() { return funds.accounts(); }
    }

    private static Outcome call(ManufacturingTemplateModel.State ledger, InformationRun information,
                                CompanyOperationsRun.Action action,
                                CompanyOperationsRun.Fields fields, String commandId,
                                String person, String actor, long now, FundState funds,
                                FundPayer payer) {
        ManufacturingTemplateModel.FundOutcome funded;
        ManufacturingTemplateModel.Outcome plain;
        String business = "";
        switch (action) {
            case ORDER_MATERIAL -> {
                business = purchaseOrderIdFor(commandId);
                plain = ManufacturingTemplateModel.orderMaterial(ledger, now, actor, commandId,
                        business, fields.materialId(), fields.quantity(), funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case RECEIVE_MATERIAL -> {
                business = fields.purchaseOrderId();
                funded = ManufacturingTemplateModel.receiveMaterial(ledger, now, actor, commandId,
                        business, funds, payer);
            }
            case START_BATCH -> {
                business = batchIdFor(commandId);
                funded = ManufacturingTemplateModel.startBatch(ledger, now, actor, commandId,
                        business, fields.productId(), fields.generation(), fields.quantity(),
                        funds, payer);
            }
            case COMPLETE_BATCH -> {
                business = fields.batchId();
                // 完工不动钱：走同一唯一现金权威的现金重载，避免用空付款人伪装一笔付款。
                plain = ManufacturingTemplateModel.completeBatch(ledger, now, actor, commandId,
                        business, funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case REQUEST_CERT -> {
                ManufacturingTemplateModel.CertEvidence evidence =
                        evidence(information, fields, person, now);
                business = fields.trialId();
                funded = ManufacturingTemplateModel.requestCert(ledger, now, actor, commandId,
                        business, fields.customerId(), fields.productId(), fields.generation(),
                        fields.round(), evidence, funds, payer);
            }
            case FINISH_CERT -> {
                business = fields.trialId();
                plain = ManufacturingTemplateModel.finishCert(ledger, now, actor, commandId,
                        business, funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case SIGN_CONTRACT -> {
                business = fields.contractId();
                plain = ManufacturingTemplateModel.signContract(ledger, now, actor, commandId,
                        business, fields.customerId(), fields.productId(), fields.generation(),
                        fields.quantity(), fields.unitPrice(), fields.promisedAt(),
                        fields.expiresAt(), funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case DELIVER -> {
                business = fields.receivableId();
                funded = ManufacturingTemplateModel.deliver(ledger, now, actor, commandId,
                        fields.contractId(), fields.quantity(), fields.receivableId(), funds, payer);
            }
            case COLLECT -> {
                business = fields.receivableId();
                funded = ManufacturingTemplateModel.collect(ledger, now, actor, commandId,
                        business, funds, payer);
            }
            case MARK_OVERDUE -> {
                business = fields.contractId();
                plain = ManufacturingTemplateModel.markOverdue(ledger, now, actor, commandId,
                        business, funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case CANCEL_CONTRACT -> {
                business = fields.contractId();
                plain = ManufacturingTemplateModel.cancelContract(ledger, now, actor, commandId,
                        business, funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case WRITE_OFF_RECEIVABLE -> {
                business = fields.receivableId();
                plain = ManufacturingTemplateModel.writeOffReceivable(ledger, now, actor, commandId,
                        business, funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case START_EXPANSION -> {
                business = fields.projectId();
                funded = ManufacturingTemplateModel.startExpansion(ledger, now, actor, commandId,
                        business, fields.capex(), fields.buildMillis(), fields.rampPeriods(),
                        fields.addCapacity(), funds, payer);
            }
            case COMPLETE_EXPANSION -> {
                business = fields.projectId();
                plain = ManufacturingTemplateModel.completeExpansion(ledger, now, actor, commandId,
                        business, fields.failed(), funds.accounts());
                return new Outcome(plain.accepted(), plain.reason(), plain.state(),
                        new FundState(plain.accounts(), funds.bank(), funds.book()), business);
            }
            case SELL_RESIDUAL -> {
                business = fields.projectId();
                funded = ManufacturingTemplateModel.sellResidual(ledger, now, actor, commandId,
                        business, fields.price(), funds, payer);
            }
            case CLOSE_PERIOD -> {
                business = String.valueOf(fields.period());
                funded = ManufacturingTemplateModel.closePeriod(ledger, now, actor, commandId,
                        fields.period(), funds, payer);
            }
            default -> throw new IllegalArgumentException("未支持的动作：" + action);
        }
        return new Outcome(funded.accepted(), funded.reason(), funded.state(),
                funded.funds(), business);
    }

    /**
     * 认证证据：只接受本人真实完成的阅读记录。
     *
     * <p>证据标识就是阅读标识；本方法从当前资料子集里查该次阅读的完成事实，核对读者、完成时点
     * 与发布时点，再把真实时间写进 {@link ManufacturingTemplateModel.CertEvidence}。
     * 调用方无法自填"已读"。</p>
     */
    private static ManufacturingTemplateModel.CertEvidence evidence(InformationRun information,
            CompanyOperationsRun.Fields fields, String person, long now) {
        if (information == null) {
            throw new IllegalArgumentException("认证证据必须来自真实资料阅读：" + fields.evidenceId());
        }
        for (InformationModel.ReadObservation observation
                : InformationModel.observations(information.information(), person)) {
            if (!observation.readId().equals(fields.evidenceId())) {
                continue;
            }
            String expectedDoc = "cert:" + fields.customerId() + ":" + fields.productId()
                    + ":" + fields.generation();
            if (!expectedDoc.equals(observation.docId())) {
                throw new IllegalArgumentException("认证阅读与本次客户、产品、代际不匹配");
            }
            if (observation.completedAt() > now || observation.publishedAt() > observation.completedAt()) {
                throw new IllegalArgumentException("证据尚未在当前时点读完");
            }
            return new ManufacturingTemplateModel.CertEvidence(observation.readId(),
                    fields.customerId(), fields.productId(), fields.generation(),
                    observation.publishedAt(), observation.completedAt(), person);
        }
        throw new IllegalArgumentException("找不到本人已完成的阅读：" + fields.evidenceId());
    }

    private static BigDecimal visiblePrice(ManufacturingTemplateModel.State ledger,
                                           String materialId, long now) {
        BigDecimal best = null;
        long bestAsOf = -1L;
        for (ManufacturingTemplateModel.MaterialQuote quote : ledger.spec().quotes()) {
            if (!quote.materialId().equals(materialId) || !quote.visibleAt(now)) {
                continue;
            }
            if (quote.asOf() > bestAsOf) {
                best = quote.price();
                bestAsOf = quote.asOf();
            }
        }
        return best;
    }

    private static BoardModel.BudgetCategory category(CompanyOperationsRun.Action action) {
        return action == CompanyOperationsRun.Action.START_BATCH
                ? BoardModel.BudgetCategory.PRODUCTION_LABOR
                : BoardModel.BudgetCategory.PROCUREMENT;
    }

    private static BoardModel.State releasePurchaseBudget(BoardModel.State board, long now,
                                                          String commitment, BigDecimal amount) {
        if (commitment == null || amount == null || amount.signum() <= 0) {
            return board;
        }
        BoardModel.Outcome released = BoardModel.releaseBudget(board, now, commitment, amount);
        return released.accepted() ? released.state() : board;
    }

    private static BigDecimal balance(Map<String, CashLedger.CashAccount> accounts, String accountId) {
        CashLedger.CashAccount account = accounts == null ? null : accounts.get(accountId);
        return account == null ? CashLedger.money(BigDecimal.ZERO) : account.cash();
    }

    /** 自动子任务：只对已开始的定时业务派生，且时点取纯核记录的真实完成时点。 */
    private static List<Child> children(String companyPartyId,
                                        CompanyOperationsRun.Action action,
                                        CompanyOperationsRun.Fields fields, String commandId,
                                        ManufacturingTemplateModel.State ledger,
                                        String businessId) {
        List<Child> children = new ArrayList<>();
        switch (action) {
            case ORDER_MATERIAL -> {
                ManufacturingTemplateModel.PurchaseOrder order = ledger.purchases()
                        .get(purchaseOrderIdFor(commandId));
                if (order != null && order.status() == ManufacturingTemplateModel.PurchaseStatus.ORDERED) {
                    children.add(new Child(CompanyOperationsRun.Action.RECEIVE_MATERIAL.name(),
                            order.purchaseOrderId(), order.arrivalAt()));
                }
            }
            case START_BATCH -> {
                ManufacturingTemplateModel.Batch batch = ledger.batches().get(batchIdFor(commandId));
                if (batch != null && batch.status() == ManufacturingTemplateModel.BatchStatus.IN_PROGRESS) {
                    children.add(new Child(CompanyOperationsRun.Action.COMPLETE_BATCH.name(),
                            batch.batchId(), batch.completesAt()));
                }
            }
            case REQUEST_CERT -> {
                for (ManufacturingTemplateModel.CertTrial trial : ledger.trials().values()) {
                    if (!trial.trialId().equals(businessId)
                            || trial.status() != ManufacturingTemplateModel.TrialStatus.IN_PROGRESS) {
                        continue;
                    }
                    // 结果既受"工时到点"也受冻结计划 readyAt 约束：取两者较晚者，避免派出一个
                    // 必然 CERT_NOT_DUE 的完成帧。
                    long readyAt = trial.finishAt();
                    for (ManufacturingTemplateModel.CertPlan plan : ledger.spec().plans()) {
                        if (plan.customerId().equals(trial.customerId())
                                && plan.productId().equals(trial.productId())
                                && plan.generation() == trial.generation()
                                && plan.round() == trial.round()) {
                            readyAt = Math.max(readyAt, plan.readyAt());
                        }
                    }
                    children.add(new Child(CompanyOperationsRun.Action.FINISH_CERT.name(),
                            trial.trialId(), readyAt));
                }
            }
            case START_EXPANSION -> {
                ManufacturingTemplateModel.Expansion expansion = ledger.expansions().get(businessId);
                if (expansion != null
                        && expansion.status() == ManufacturingTemplateModel.ExpansionStatus.BUILDING) {
                    // 完工由冻结方案决定；经理不能直接伪造完工或指定结果。
                    children.add(new Child(CompanyOperationsRun.Action.COMPLETE_EXPANSION.name(), expansion.projectId(),
                            expansion.completesAt()));
                }
            }
            case CLOSE_PERIOD -> {
                long next = Math.addExact(fields.period(), 1L);
                children.add(new Child(CompanyOperationsRun.Action.CLOSE_PERIOD.name(),
                        Long.toString(next), Math.multiplyExact(Math.addExact(next, 1L),
                                ManufacturingTemplateModel.PERIOD_MILLIS)));
            }
            default -> {
            }
        }
        return List.copyOf(children);
    }

    /** 可供经理观察的自家公开方案：只含冻结规格里的公开报价与产品，不含未来结果。 */
    static Map<String, Object> observation(CompanyOperationsRun run, BoardRun board,
                                           String companyPartyId, long now) {
        Map<String, Object> view = new TreeMap<>();
        CompanyOperationsRun.Company company = run == null ? null : run.company(companyPartyId);
        if (company == null) {
            return view;
        }
        ManufacturingTemplateModel.TemplateSpec spec = company.ledger().spec();
        List<Map<String, Object>> products = new ArrayList<>();
        for (ManufacturingTemplateModel.ProductSpec product : spec.products()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("productId", product.productId());
            row.put("generation", product.generation());
            row.put("certRequired", product.certRequired());
            row.put("laborPerUnit", product.laborPerUnit().toPlainString());
            row.put("bom", new TreeMap<>(product.bom()));
            products.add(row);
        }
        view.put("companyId", companyPartyId);
        view.put("symbol", company.symbol());
        view.put("templateId", spec.templateId());
        view.put("products", products);
        view.put("customers", spec.customers().stream().map(customer -> customer.customerId()).toList());
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (ManufacturingTemplateModel.MaterialQuote quote : spec.quotes()) {
            if (!quote.visibleAt(now)) {
                continue;
            }
            Map<String, Object> row = new TreeMap<>();
            row.put("materialId", quote.materialId());
            row.put("supplierId", quote.supplierId());
            row.put("price", quote.price().toPlainString());
            row.put("validTo", quote.validTo());
            quotes.add(row);
        }
        view.put("visibleQuotes", quotes);
        view.put("capacityThisPeriod",
                ManufacturingTemplateModel.capacityAt(company.ledger(), now / ManufacturingTemplateModel.PERIOD_MILLIS)
                        .subtract(java.math.BigInteger.valueOf(company.ledger().capacityUsed()
                                .getOrDefault(now / ManufacturingTemplateModel.PERIOD_MILLIS, 0L))).toString());
        Map<String, Object> budgets = new TreeMap<>();
        long period = now / PeriodicModel.PERIOD_MILLIS;
        for (BoardModel.BudgetCategory category : BoardModel.BudgetCategory.values()) {
            budgets.put(category.name(), board == null || board.board().budget(period, category) == null
                    ? null : BoardModel.availableBudget(board.board(), period, category).toPlainString());
        }
        view.put("approvedBudgetRemaining", budgets);
        CompanyOperationsRun.ExpansionOffer offer = company.expansionOffer();
        if (offer != null) {
            view.put("expansionOffer", Map.of("capex", offer.capex().toPlainString(),
                    "buildMillis", offer.buildMillis(), "rampPeriods", offer.rampPeriods(),
                    "addCapacity", offer.addCapacity()));
        }
        view.put("actions", actions(company));
        view.put("rules", "这里只列自家公开方案、可见报价与已批预算余额；不含未来认证结果、"
                + "客户私有预算或别家台账。认证所需的『已读』必须来自本人真实阅读。");
        return view;
    }

    /** @return 该公司当前可下发的动作（由冻结台账状态决定，不含"未实现"占位） */
    static List<String> actions(CompanyOperationsRun.Company company) {
        ManufacturingTemplateModel.State ledger = company.ledger();
        List<String> actions = new ArrayList<>();
        actions.add(CompanyOperationsRun.Action.ORDER_MATERIAL.name());
        actions.add(CompanyOperationsRun.Action.START_BATCH.name());
        for (ManufacturingTemplateModel.ProductSpec product : ledger.spec().products()) {
            if (product.certRequired()) {
                actions.add(CompanyOperationsRun.Action.REQUEST_CERT.name());
                break;
            }
        }
        actions.add(CompanyOperationsRun.Action.SIGN_CONTRACT.name());
        actions.add(CompanyOperationsRun.Action.DELIVER.name());
        actions.add(CompanyOperationsRun.Action.COLLECT.name());
        actions.add(CompanyOperationsRun.Action.MARK_OVERDUE.name());
        actions.add(CompanyOperationsRun.Action.CANCEL_CONTRACT.name());
        actions.add(CompanyOperationsRun.Action.WRITE_OFF_RECEIVABLE.name());
        if (company.expansionOffer() != null) {
            actions.add(CompanyOperationsRun.Action.START_EXPANSION.name());
        }
        actions.add(CompanyOperationsRun.Action.SELL_RESIDUAL.name());
        return List.copyOf(actions);
    }
}
