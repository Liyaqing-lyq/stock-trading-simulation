package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 有限现金外部需求实验纯核：真实交付投入按一比一转为最终消费，不复制现金余额。
 * 分配、加工、加价、需求上限均为 EXP 假设，不代表真实市场校准。
 * @param config 冻结配置
 * @param watermark 最近结算时点
 * @param sales 累计消费回执
 * @param incomeDistributions 累计收入分配回执（零金额也记录已经检查该期）
 */
public record ExternalDemandRun(Config config, long watermark, List<SaleReceipt> sales,
                                List<IncomeReceipt> incomeDistributions) {
    /** 固定规则版本。 */
    public static final String RULES = "external-demand-v1";
    /** 三十日需求期间。 */
    public static final long PERIOD_MILLIS = 30L * 86_400_000L;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MARKUP = new BigDecimal("1.25");
    private static final BigDecimal DISTRIBUTION = new BigDecimal("0.80");

    /** 防御性复制，并由回执重新校验去重、预算和时间约束。 */
    public ExternalDemandRun {
        Objects.requireNonNull(config, "配置");
        require(watermark >= 0, "时间不能为负");
        sales = List.copyOf(sales);
        incomeDistributions = List.copyOf(incomeDistributions);
        Set<String> ids = new HashSet<>();
        Map<Long, Long> quantities = new HashMap<>();
        Map<Long, BigDecimal> spending = new HashMap<>();
        Map<InputKey, Long> consumed = new HashMap<>();
        long previous = 0;
        for (int i = 0; i < sales.size(); i++) {
            SaleReceipt sale = sales.get(i);
            require(ids.add(sale.outputId()) && sale.outputId().equals(saleId(i)), "消费回执标识重复或序号错误");
            require(sale.buyerAccountId().equals(config.finalBuyerAccountId()), "消费付款账户不符");
            require(sale.settledAt() >= previous && sale.settledAt() <= watermark, "消费回执时间越界");
            require(sale.matureAt() == add(sale.recognizedAt(), config.processingDelayMillis()), "加工时间不符");
            previous = sale.settledAt();
            long qty = add(quantities.getOrDefault(sale.period(), 0L), sale.quantity());
            BigDecimal paid = spending.getOrDefault(sale.period(), ZERO).add(sale.cashPaid());
            require(qty <= config.demandUnitsPerPeriod() && paid.compareTo(config.spendingPerPeriod()) <= 0, "期间需求或支出超限");
            quantities.put(sale.period(), qty);
            spending.put(sale.period(), paid);
            InputKey key = new InputKey(sale.companyPartyId(), sale.receivableId());
            consumed.put(key, add(consumed.getOrDefault(key, 0L), sale.quantity()));
        }
        previous = 0;
        for (IncomeReceipt receipt : incomeDistributions) {
            require(ids.add(receipt.outputId()) && receipt.outputId().equals(incomeId(receipt.period(), receipt.sourceAccountId())), "收入分配回执重复或标识错误");
            require(config.incomeSourceAccountIds().contains(receipt.sourceAccountId())
                    && receipt.recipientAccountId().equals(config.finalBuyerAccountId()), "收入分配账户不符");
            require(receipt.settledAt() >= previous && receipt.settledAt() <= watermark, "收入分配时间越界");
            previous = receipt.settledAt();
        }
    }
    /** 外部需求配置。 */
    public record Config(String rulesVersion, String finalBuyerAccountId, String finalBuyerOwnerId,
                         Set<String> incomeSourceAccountIds, long demandUnitsPerPeriod,
                         BigDecimal spendingPerPeriod, long processingDelayMillis) {
        /** 校验版本、账户与精确到分的非负情景参数。 */
        public Config {
            require(RULES.equals(rulesVersion), "未知外部需求版本");
            text(finalBuyerAccountId); text(finalBuyerOwnerId);
            incomeSourceAccountIds = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(incomeSourceAccountIds)));
            incomeSourceAccountIds.forEach(ExternalDemandRun::text);
            require(!incomeSourceAccountIds.contains(finalBuyerAccountId), "最终买方不能向自己分配收入");
            require(demandUnitsPerPeriod >= 0 && processingDelayMillis >= 0, "需求与加工时长不能为负");
            spendingPerPeriod = nonnegative(spendingPerPeriod);
        }
        /** 创建每期二百件、二万元、三日加工的冻结 EXP 基准。 */
        public static Config defaults(String buyerAccountId, String buyerOwnerId, Set<String> sourceAccountIds) {
            return new Config(RULES, buyerAccountId, buyerOwnerId, sourceAccountIds, 200,
                    new BigDecimal("20000.00"), 3L * 86_400_000L);
        }
    }
    /** 最终消费回执。 */
    public record SaleReceipt(String outputId, String companyPartyId, String receivableId,
                              String contractId, String customerId, String sourceCompanyAccountId,
                              String buyerAccountId, String recipientAccountId, long quantity,
                              BigDecimal unitPrice, BigDecimal cashPaid, BigDecimal inputCost,
                              BigDecimal profit, long recognizedAt, long matureAt, long settledAt,
                              long period) {
        /** 校验来源、时间与数量金额恒等式；投入成本指客户购入商品的合同成本。 */
        public SaleReceipt {
            for (String id : List.of(outputId, companyPartyId, receivableId, contractId, customerId,
                    sourceCompanyAccountId, buyerAccountId, recipientAccountId)) text(id);
            require(quantity > 0 && recognizedAt >= 0 && matureAt >= recognizedAt
                    && settledAt >= matureAt && period == settledAt / PERIOD_MILLIS, "消费数量或时间非法");
            require(!buyerAccountId.equals(recipientAccountId) && !sourceCompanyAccountId.equals(recipientAccountId), "消费账户不能互相冒用");
            unitPrice = nonnegative(unitPrice); cashPaid = nonnegative(cashPaid);
            inputCost = nonnegative(inputCost); profit = nonnegative(profit);
            require(unitPrice.signum() > 0 && cashPaid.equals(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                    && profit.equals(cashPaid.subtract(inputCost)), "消费金额或利润不符");
        }
    }
    /** 实际收入分配回执。 */
    public record IncomeReceipt(String outputId, String sourceAccountId, String recipientAccountId,
                                BigDecimal sourceCashBefore, BigDecimal cashPaid, long settledAt, long period) {
        /** 校验分配回执；零金额只表示检查完成，不表示发生现金转移。 */
        public IncomeReceipt {
            text(outputId); text(sourceAccountId); text(recipientAccountId);
            sourceCashBefore = nonnegative(sourceCashBefore); cashPaid = nonnegative(cashPaid);
            require(cashPaid.equals(sourceCashBefore.multiply(DISTRIBUTION).setScale(2, RoundingMode.HALF_DOWN)), "收入分配比例不符");
            require(!sourceAccountId.equals(recipientAccountId) && settledAt >= 0 && period >= 1
                    && period == settledAt / PERIOD_MILLIS, "收入分配账户或时间非法");
        }
    }
    /** 一次候选状态和新增回执。 */
    public record Result(ExternalDemandRun run, Map<String, CashLedger.CashAccount> accounts,
                         List<SaleReceipt> sales, List<IncomeReceipt> incomeDistributions) {
        /** 固化本次候选账户及新增回执，调用者须与运行状态原子提交。 */
        public Result {
            Objects.requireNonNull(run);
            accounts = Map.copyOf(accounts); sales = List.copyOf(sales);
            incomeDistributions = List.copyOf(incomeDistributions);
        }
    }
    /** 创建空进度，不产生资金。 */
    public static ExternalDemandRun opening(Config config) {
        return new ExternalDemandRun(config, 0, List.of(), List.of());
    }
    /** 结算已经交付且成熟的实际投入。 */
    public Result settle(CompanyOperationsRun operations, Map<String, CashLedger.CashAccount> accounts, long now) {
        require(now >= watermark, "外部需求时间不能倒退");
        validateAccounts(Objects.requireNonNull(operations), accounts);
        List<Input> inputs = inputs(operations);
        Map<InputKey, Input> byKey = new HashMap<>();
        inputs.forEach(input -> require(byKey.put(input.key(), input) == null, "重复交付标识"));
        Map<InputKey, Long> consumed = new HashMap<>();
        long period = now / PERIOD_MILLIS;
        long used = 0;
        BigDecimal spent = ZERO;
        for (SaleReceipt sale : sales) {
            Input input = byKey.get(new InputKey(sale.companyPartyId(), sale.receivableId()));
            require(input != null, "消费来源交付已丢失");
            require(sale.contractId().equals(input.receivable().contractId())
                    && sale.customerId().equals(input.receivable().customerId())
                    && sale.recipientAccountId().equals(input.customerAccount())
                    && sale.sourceCompanyAccountId().equals(input.company().cashAccountId())
                    && sale.recognizedAt() == input.receivable().recognizedAt()
                    && sale.unitPrice().equals(input.price())
                    && sale.inputCost().equals(input.contract().unitPrice().multiply(BigDecimal.valueOf(sale.quantity()))), "消费回执来源与交付不符");
            long quantity = add(consumed.getOrDefault(input.key(), 0L), sale.quantity());
            require(quantity <= input.receivable().quantity(), "消费超过交付数量");
            consumed.put(input.key(), quantity);
            if (sale.period() == period) { used = add(used, sale.quantity()); spent = spent.add(sale.cashPaid()); }
        }
        Map<String, CashLedger.CashAccount> next = Map.copyOf(accounts);
        List<IncomeReceipt> addedIncome = new ArrayList<>();
        Set<String> distributed = new HashSet<>();
        incomeDistributions.forEach(receipt -> distributed.add(receipt.outputId()));
        if (period >= 1) {
            for (String source : config.incomeSourceAccountIds()) {
                String id = incomeId(period, source);
                if (distributed.contains(id)) continue;
                BigDecimal before = next.get(source).cash();
                BigDecimal amount = before.multiply(DISTRIBUTION).setScale(2, RoundingMode.HALF_DOWN);
                if (amount.signum() > 0) next = transfer(next, source, config.finalBuyerAccountId(), amount);
                addedIncome.add(new IncomeReceipt(id, source, config.finalBuyerAccountId(), before, amount, now, period));
            }
        }
        List<SaleReceipt> addedSales = new ArrayList<>();
        for (Input input : inputs) {
            if (now < input.matureAt()) continue;
            long remaining = input.receivable().quantity() - consumed.getOrDefault(input.key(), 0L);
            long cap = Math.min(remaining, config.demandUnitsPerPeriod() - used);
            BigDecimal available = config.spendingPerPeriod().subtract(spent).min(next.get(config.finalBuyerAccountId()).cash());
            long quantity = available.divideToIntegralValue(input.price()).min(BigDecimal.valueOf(cap)).longValueExact();
            if (quantity == 0) continue;
            BigDecimal paid = input.price().multiply(BigDecimal.valueOf(quantity));
            BigDecimal cost = input.contract().unitPrice().multiply(BigDecimal.valueOf(quantity));
            next = transfer(next, config.finalBuyerAccountId(), input.customerAccount(), paid);
            addedSales.add(new SaleReceipt(saleId(add(sales.size(), addedSales.size())), input.company().companyPartyId(),
                    input.receivable().receivableId(), input.receivable().contractId(), input.receivable().customerId(),
                    input.company().cashAccountId(), config.finalBuyerAccountId(), input.customerAccount(), quantity,
                    input.price(), paid, cost, paid.subtract(cost), input.receivable().recognizedAt(), input.matureAt(), now, period));
            used = add(used, quantity); spent = spent.add(paid);
        }
        List<SaleReceipt> allSales = new ArrayList<>(sales); allSales.addAll(addedSales);
        List<IncomeReceipt> allIncome = new ArrayList<>(incomeDistributions); allIncome.addAll(addedIncome);
        return new Result(new ExternalDemandRun(config, now, allSales, allIncome), next, addedSales, addedIncome);
    }

    private record InputKey(String companyId, String receivableId) {}
    private record Input(CompanyOperationsRun.Company company, ManufacturingTemplateModel.Receivable receivable,
                         ManufacturingTemplateModel.Contract contract, String customerAccount, BigDecimal price, long matureAt) {
        InputKey key() { return new InputKey(company.companyPartyId(), receivable.receivableId()); }
    }
    private List<Input> inputs(CompanyOperationsRun operations) {
        List<Input> result = new ArrayList<>();
        for (CompanyOperationsRun.Company company : operations.companies()) {
            Map<String, Long> contractQuantities = new HashMap<>();
            for (var entry : company.ledger().receivables().entrySet()) {
                var receivable = entry.getValue();
                require(entry.getKey().equals(receivable.receivableId()) && receivable.recognizedAt() >= 0, "交付标识或时间非法");
                var contract = company.ledger().contracts().get(receivable.contractId());
                require(contract != null && contract.contractId().equals(receivable.contractId())
                        && contract.customerId().equals(receivable.customerId()) && contract.unitPrice().signum() > 0
                        && receivable.amount().equals(contract.unitPrice().multiply(BigDecimal.valueOf(receivable.quantity()))), "交付合同来源不符");
                long total = add(contractQuantities.getOrDefault(contract.contractId(), 0L), receivable.quantity());
                require(total <= contract.delivered(), "应收数量超过合同实际交付");
                contractQuantities.put(contract.contractId(), total);
                String customer = company.ledger().accounts().customerAccounts().get(receivable.customerId());
                text(customer);
                result.add(new Input(company, receivable, contract, customer,
                        contract.unitPrice().multiply(MARKUP).setScale(2, RoundingMode.HALF_UP),
                        add(receivable.recognizedAt(), config.processingDelayMillis())));
            }
        }
        result.sort(Comparator.comparingLong((Input input) -> input.receivable().recognizedAt())
                .thenComparing(input -> input.company().companyPartyId()).thenComparing(input -> input.receivable().receivableId()));
        return result;
    }

    private void validateAccounts(CompanyOperationsRun operations, Map<String, CashLedger.CashAccount> accounts) {
        Objects.requireNonNull(accounts, "现金账户");
        accounts.forEach((id, account) -> { text(id); Objects.requireNonNull(account); text(account.ownerId()); text(account.source()); nonnegative(account.cash()); });
        CashLedger.CashAccount buyer = accounts.get(config.finalBuyerAccountId());
        require(buyer != null && buyer.ownerId().equals(config.finalBuyerOwnerId()), "最终买方账户缺失或属主不符");
        Set<String> eligible = new HashSet<>();
        Set<String> forbidden = new HashSet<>();
        forbidden.add(config.finalBuyerAccountId());
        for (var company : operations.companies()) {
            var mapping = company.ledger().accounts();
            forbidden.add(mapping.companyAccountId()); forbidden.addAll(mapping.customerAccounts().values());
            eligible.add(mapping.opsAccountId()); eligible.addAll(mapping.supplierAccounts().values());
            Set<String> bound = new HashSet<>(mapping.customerAccounts().values());
            bound.addAll(mapping.supplierAccounts().values()); bound.add(mapping.companyAccountId()); bound.add(mapping.opsAccountId());
            for (String id : bound) {
                var account = accounts.get(id);
                String owner = company.ledger().openingOwners().get(id);
                require(account != null && owner != null && owner.equals(account.ownerId()), "经营账户缺失或属主不符：" + id);
            }
        }
        if (operations.resource() != null) {
            var mapping = operations.resource().ledger().config();
            forbidden.addAll(List.of(mapping.companyAccountId(), mapping.coalCustomerAccountId(), mapping.gridAccountId()));
        }
        if (operations.hydro() != null) {
            var mapping = operations.hydro().ledger().config();
            forbidden.addAll(List.of(mapping.companyAccountId(), mapping.buyerAccountId()));
        }
        if (operations.digital() != null) {
            var mapping = operations.digital().config().model();
            forbidden.addAll(List.of(mapping.companyAccountId(), mapping.customerAccountId()));
        }
        if (operations.aged() != null) {
            var mapping = operations.aged().config().model();
            forbidden.add(mapping.companyAccountId());
            mapping.channels().forEach(channel -> forbidden.add(channel.accountId()));
        }
        Set<String> forbiddenOwners = new HashSet<>();
        forbidden.forEach(id -> {
            require(accounts.containsKey(id), "经营公司或客户账户缺失：" + id);
            forbiddenOwners.add(accounts.get(id).ownerId());
        });
        Set<String> sourceOwners = new HashSet<>();
        for (String source : config.incomeSourceAccountIds()) {
            require(eligible.contains(source) && !forbidden.contains(source), "非法收入来源账户：" + source);
            String owner = accounts.get(source).ownerId();
            require(!forbiddenOwners.contains(owner) && sourceOwners.add(owner), "来源属主冒用或重复：" + source);
        }
        for (var entry : accounts.entrySet()) {
            require(entry.getKey().equals(config.finalBuyerAccountId()) || !entry.getValue().ownerId().equals(config.finalBuyerOwnerId()), "最终买方属主存在别名账户");
        }
    }

    private static Map<String, CashLedger.CashAccount> transfer(Map<String, CashLedger.CashAccount> cash, String from, String to, BigDecimal amount) {
        var result = CashLedger.computeTransfer(cash, cash.get(from).ownerId(), from, to, amount);
        require(result.accepted(), "外部需求资金转移失败：" + result.reason());
        return result.accounts();
    }
    private static String saleId(long sequence) { return "external-demand:sale:" + sequence; }
    private static String incomeId(long period, String source) { return "external-demand:income:" + period + ":" + source; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void text(String value) { require(value != null && !value.isBlank(), "标识不能为空"); }
    private static BigDecimal nonnegative(BigDecimal amount) {
        require(amount != null && amount.signum() >= 0, "金额必须非负");
        try { return amount.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("金额必须精确到分", ex); }
    }
    private static long add(long first, long second) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("数量或时间溢出", ex); }
    }
}
