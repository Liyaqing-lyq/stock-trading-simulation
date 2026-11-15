package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 三类制造公司的唯一经营子集：按 companyId 排序的三份纯核台账与准确管理回报。
 *
 * <p><b>为什么不复制账</b>：现金仍然唯一保存在 {@link CashLedger} 的账户表里，银行存款仍在
 * {@code BankRun}，证券持仓仍在 {@link SecuritiesLedger}。本组件只承载每家公司的
 * {@link ManufacturingTemplateModel.State}——其中已经包含模板规格、账户绑定、BOM、批次、
 * 认证计划、合同、应收、扩产与期间账——以及按 taskId 索引的执行回报。任何现金、银行或证券
 * 余额都必须在读取时同批现算，绝不在这里存第二份。</p>
 *
 * <p><b>不可变与排序</b>：公司表按 {@code companyPartyId} 严格升序且不重复，回报表按 taskId
 * 唯一。所有集合在构造期深复制为不可变视图，因此一次候选状态不会被后续动作就地改写。</p>
 *
 * <p><b>旧档不变</b>：本组件是 {@code WorldRuntime} 持久帧的可空末尾组件；没有
 * {@code company-operations} 模块的旧档案里它是 null，旧 JSON 缺该键、旧构造与旧行为逐字不变。</p>
 *
 * @param rulesVersion 冻结规则标识，必须等于 {@link #RULES}
 * @param companies    三家公司状态，按 companyPartyId 升序
 * @param reports      按 taskId 索引的管理回报
 */
public record CompanyOperationsRun(String rulesVersion, List<Company> companies,
                                   Map<String, ReportEntry> reports,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) ResourceCompanyRun resource,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) HydroCompanyRun hydro,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) DigitalCompanyRun digital,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) AgedCompanyRun aged) {
    /** 原水电构造保留旧档形状。 */
    public CompanyOperationsRun(String rulesVersion, List<Company> companies, Map<String, ReportEntry> reports, ResourceCompanyRun resource, HydroCompanyRun hydro) { this(rulesVersion, companies, reports, resource, hydro, null, null); }
    /** 同帧替换数字子账。 */
    public CompanyOperationsRun withDigital(DigitalCompanyRun next) { return new CompanyOperationsRun(rulesVersion, companies, reports, resource, hydro, next, aged); }
    /** 同帧替换陈酿子账。 */
    public CompanyOperationsRun withAged(AgedCompanyRun next) { return new CompanyOperationsRun(rulesVersion, companies, reports, resource, hydro, digital, next); }
    /** 原资源档案不启用水电。 */
    public CompanyOperationsRun(String rulesVersion, List<Company> companies, Map<String, ReportEntry> reports, ResourceCompanyRun resource) { this(rulesVersion, companies, reports, resource, null); }
    /** 同帧替换水电。 */
    public CompanyOperationsRun withHydro(HydroCompanyRun next) { return new CompanyOperationsRun(rulesVersion, companies, reports, resource, next, digital, aged); }

    /** 旧三制造构造保持原JSON，不隐式启用资源公司。 */
    public CompanyOperationsRun(String rulesVersion, List<Company> companies, Map<String, ReportEntry> reports) {
        this(rulesVersion, companies, reports, null);
    }

    /** 同帧替换资源子账，其它公司和历史回报保持不变。 */
    public CompanyOperationsRun withResource(ResourceCompanyRun next) {
        return new CompanyOperationsRun(rulesVersion, companies, reports, next, hydro, digital, aged);
    }

    /** 本组件唯一支持的规则标识；未知版本在构造期拒绝。 */
    public static final String RULES = "company-operations-v1";

    /** 常量校验：规则版本、公司排序与回报索引。 */
    public CompanyOperationsRun {
        if (!RULES.equals(rulesVersion)) {
            throw new IllegalArgumentException("未知的经营子集规则版本：" + rulesVersion);
        }
        List<Company> copy = new ArrayList<>();
        String previous = null;
        for (Company company : Objects.requireNonNull(companies, "公司表")) {
            Objects.requireNonNull(company, "公司状态");
            if (previous != null && previous.compareTo(company.companyPartyId()) >= 0) {
                throw new IllegalArgumentException("公司状态必须按 companyId 严格升序且唯一："
                        + previous + " / " + company.companyPartyId());
            }
            previous = company.companyPartyId();
            copy.add(company);
        }
        companies = List.copyOf(copy);
        Map<String, ReportEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, ReportEntry> entry
                : Objects.requireNonNull(reports, "经营回报").entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("经营回报索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
            entries.put(entry.getKey(), entry.getValue());
        }
        reports = Collections.unmodifiableMap(entries);
    }

    /**
     * 一家公司的经营状态。
     *
     * @param companyPartyId 公司组织标识
     * @param symbol         该公司证券代码
     * @param templateId     冻结模板标识（与台账 spec 一致）
     * @param ledger         纯核唯一业务台账（含规格与账户绑定）
     */
    public record Company(String companyPartyId, String symbol, String templateId,
                          ManufacturingTemplateModel.State ledger, ExpansionOffer expansionOffer) {

        /** 校验标识与规格一致性（模板标识、公司账户属主必须与台账一致）。 */
        public Company {
            requireText(companyPartyId, "公司");
            requireText(symbol, "证券代码");
            requireText(templateId, "模板");
            Objects.requireNonNull(ledger, "经营台账");
            if (!templateId.equals(ledger.spec().templateId())) {
                throw new IllegalArgumentException("模板标识与台账规格不一致："
                        + templateId + " / " + ledger.spec().templateId());
            }
            requireText(ledger.accounts().companyOwnerId(), "公司账户主体");
        }

        /** @return 该公司的经营现金账户（唯一现金权威里的账户标识） */
        @JsonIgnore
        public String cashAccountId() {
            return ledger.accounts().companyAccountId();
        }

        /** @return 用同一模板标识替换台账 */
        public Company withLedger(ManufacturingTemplateModel.State next) {
            return new Company(companyPartyId, symbol, templateId,
                    Objects.requireNonNull(next, "候选台账"), expansionOffer);
        }
    }

    /**
     * 一次真实执行的管理回报（含自动到期子任务）。
     *
     * @param taskId     产生该结果的待办
     * @param commandId  外部原始命令；系统子任务为 {@code system:} 前缀
     * @param companyPartyId 公司
     * @param action     经营动作名
     * @param accepted   业务是否成功
     * @param reason     结果码
     * @param businessId 关联业务标识（采购单/批次/试验/合同/应收/扩产项目/费用账单），无则空串
     * @param amount     本次动作实际转移的现金，精确到分；无现金动作时为零
     * @param timeMillis 执行时点（取待办到期时间）
     * @param position   结果所在的提交位置
     */
    public record ReportEntry(String taskId, String commandId, String companyPartyId,
                              String action, boolean accepted, String reason, String businessId,
                              BigDecimal amount, long timeMillis, long position) {

        /** 校验标识、金额与位置。 */
        public ReportEntry {
            requireText(taskId, "任务标识");
            requireText(commandId, "命令标识");
            requireText(companyPartyId, "公司");
            requireText(action, "动作");
            if (businessId == null) {
                businessId = "";
            }
            amount = CashLedger.money(Objects.requireNonNull(amount, "回报金额"));
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("回报金额不能为负：" + amount);
            }
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("回报时点或提交位置非法：" + timeMillis
                        + " / " + position);
            }
        }
    }

    /**
     * 开局配置：一家公司一份，只在 {@code operations-open:} 帧被接受。
     *
     * <p>金额与产能都在 {@link ManufacturingTemplateModel.TemplateSpec} 里，不在本记录重复；
     * 本记录只把公司标识、证券代码与纯核规格/账户绑定显式绑在一起。开局不预置经理、董事任职、
     * 预算或任何已开始业务。</p>
     *
     * @param companyPartyId 公司组织（必须等于账户绑定的公司财产主体所属组织）
     * @param symbol         该公司证券代码
     * @param spec           冻结模板规格（含 EXP 参数与认证计划）
     * @param accounts       纯核账户绑定（公司/运营/认证方/承包方/处置买方 + 客户/供应商）
     */
    public record Config(String companyPartyId, String symbol,
                         ManufacturingTemplateModel.TemplateSpec spec,
                         ManufacturingTemplateModel.Accounts accounts, ExpansionOffer expansionOffer) {

        /** 没有承包方扩产报价的公司不提供扩产动作。 */
        public Config(String companyPartyId, String symbol, ManufacturingTemplateModel.TemplateSpec spec,
                ManufacturingTemplateModel.Accounts accounts) {
            this(companyPartyId, symbol, spec, accounts, null);
        }

        /** 校验非空。 */
        public Config {
            requireText(companyPartyId, "公司");
            requireText(symbol, "证券代码");
            Objects.requireNonNull(spec, "模板规格");
            Objects.requireNonNull(accounts, "账户绑定");
        }
    }

    /** 承包方冻结报价与交付情景；失败情景只供运行层到期计算，不向经理投影。 */
    public record ExpansionOffer(BigDecimal capex, long buildMillis, int rampPeriods,
                                 long addCapacity, boolean failed) {
        /** 校验价格、建设工期和产能，防止经理凭空设定物理参数。 */
        public ExpansionOffer {
            capex = CashLedger.money(Objects.requireNonNull(capex, "扩产价款"));
            if (capex.signum() <= 0 || buildMillis <= 0 || rampPeriods <= 0 || addCapacity <= 0) {
                throw new IllegalArgumentException("扩产报价参数必须为正");
            }
        }

        /** @return 动作是否完整匹配冻结的公开报价 */
        public boolean matches(Fields fields) {
            return fields.capex() != null && capex.compareTo(fields.capex()) == 0
                    && Long.valueOf(buildMillis).equals(fields.buildMillis())
                    && Integer.valueOf(rampPeriods).equals(fields.rampPeriods())
                    && Long.valueOf(addCapacity).equals(fields.addCapacity());
        }
    }

    /** @return 该公司状态；不存在返回 null */
    @JsonIgnore
    public Company company(String companyPartyId) {
        if (companyPartyId == null) {
            return null;
        }
        for (Company company : companies) {
            if (company.companyPartyId().equals(companyPartyId)) {
                return company;
            }
        }
        return null;
    }

    /** @return 公司标识表（升序） */
    @JsonIgnore
    public List<String> companyIds() {
        List<String> ids = new ArrayList<>();
        for (Company company : companies) {
            ids.add(company.companyPartyId());
        }
        return List.copyOf(ids);
    }

    /** @return 该公司的一次回报；不存在返回 null */
    public ReportEntry report(String taskId) {
        return taskId == null ? null : reports.get(taskId);
    }

    /**
     * 追加一条回报，不改动已有条目。
     *
     * @param entry 新回报
     * @return 追加后的新子集
     * @throws IllegalArgumentException 同一 taskId 已有不同回报
     */
    public CompanyOperationsRun withReport(ReportEntry entry) {
        Objects.requireNonNull(entry, "回报");
        ReportEntry existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条经营回报：" + entry.taskId());
            }
            return this;
        }
        Map<String, ReportEntry> next = new LinkedHashMap<>(reports);
        next.put(entry.taskId(), entry);
        return new CompanyOperationsRun(rulesVersion, companies, next, resource, hydro, digital, aged);
    }

    /**
     * 用同一公司的候选台账替换当前台账（其它公司与回报不变）。
     *
     * @param companyPartyId 公司
     * @param next           候选台账
     * @return 替换后的新子集
     */
    public CompanyOperationsRun withLedger(String companyPartyId, ManufacturingTemplateModel.State next) {
        Objects.requireNonNull(next, "候选台账");
        List<Company> copy = new ArrayList<>(companies.size());
        boolean found = false;
        for (Company company : companies) {
            if (company.companyPartyId().equals(companyPartyId)) {
                copy.add(company.withLedger(next));
                found = true;
            } else {
                copy.add(company);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("经营子集没有该公司：" + companyPartyId);
        }
        return new CompanyOperationsRun(rulesVersion, copy, reports, resource, hydro, digital, aged);
    }

    /**
     * 开局：按配置建立三家公司台账；账户与主体必须已存在于当前唯一账户表。
     *
     * @param configs 公司配置（顺序无关，结果按 companyId 排序）
     * @param cash    当前唯一现金账户表
     * @return 新子集（无任何回报）
     */
    public static CompanyOperationsRun open(List<Config> configs,
                                            Map<String, CashLedger.CashAccount> cash) {
        Objects.requireNonNull(configs, "公司配置");
        Objects.requireNonNull(cash, "现金账户表");
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("经营子集至少需要一家公司");
        }
        Map<String, Company> byId = new TreeMap<>();
        for (Config config : configs) {
            Objects.requireNonNull(config, "公司配置");
            if (byId.containsKey(config.companyPartyId())) {
                throw new IllegalArgumentException("公司配置重复：" + config.companyPartyId());
            }
            ManufacturingTemplateModel.State ledger = ManufacturingTemplateModel.open(
                    config.spec(), config.accounts(), cash);
            byId.put(config.companyPartyId(), new Company(config.companyPartyId(),
                    config.symbol(), config.spec().templateId(), ledger, config.expansionOffer()));
        }
        return new CompanyOperationsRun(RULES, List.copyOf(byId.values()), Map.of());
    }

    /** 经营动作：世界命令与纯核入口的显式对应。 */
    public enum Action {
        /** 采购下单：锁价并占供应商当期容量，到货时点自动子任务。 */
        ORDER_MATERIAL,
        /** 采购到货：真实付款给供应商，入原材料。 */
        RECEIVE_MATERIAL,
        /** 投产：耗料 + 人工付款，完工时点自动子任务。 */
        START_BATCH,
        /** 完工：在制转成品。 */
        COMPLETE_BATCH,
        /** 认证申请：真实付费，结果就绪时点自动子任务。 */
        REQUEST_CERT,
        /** 认证完成：按冻结计划给 PASS/FAIL。 */
        FINISH_CERT,
        /** 签约：只校验认证/需求/预算/价格，不动现金。 */
        SIGN_CONTRACT,
        /** 交付：确认收入/成本/应收。 */
        DELIVER,
        /** 回款：客户真实付款冲应收。 */
        COLLECT,
        /** 逾期标记：只释放未交额度一次。 */
        MARK_OVERDUE,
        /** 取消合同：只释放未交额度一次。 */
        CANCEL_CONTRACT,
        /** 坏账核销：按最小实验政策确认损失。 */
        WRITE_OFF_RECEIVABLE,
        /** 扩产开工：付款给承包方形成 CIP，建成时点自动子任务。 */
        START_EXPANSION,
        /** 扩产完工：转设备（或失败残值）。 */
        COMPLETE_EXPANSION,
        /** 残值处置：有限买方真实付款。 */
        SELL_RESIDUAL,
        /** 期末关账：期间费用/折旧，连续关账。 */
        CLOSE_PERIOD;

        /** @return 该动作到达时点后是否必须由系统子任务完成 */
        @JsonIgnore
        public boolean startsTimedWork() {
            return this == ORDER_MATERIAL || this == START_BATCH
                    || this == REQUEST_CERT || this == START_EXPANSION;
        }

        /** @return 该动作是否直接对应纯核「完成」入口（只能由系统子任务调用） */
        @JsonIgnore
        public boolean completionOnly() {
            return this == RECEIVE_MATERIAL || this == COMPLETE_BATCH
                    || this == FINISH_CERT || this == COMPLETE_EXPANSION || this == CLOSE_PERIOD;
        }
    }

    /** 执行阶段：用户开始动作与到期完工分开。 */
    public enum Stage {
        /** 由真实管理器命令排程、到期开始。 */
        BEGIN,
        /** 由已开始业务派生的系统子任务到期完成。 */
        FINISH
    }

    /**
     * 动作字段：单一显式值对象，未用到的字段必须为空。
     *
     * <p>严格按动作校验，不做缺省推断：例如 {@code COLLECT} 只接受 {@code receivableId}，
     * 多填任何别的字段都会被拒绝，避免"看起来带了公司/客户"的自报字段悄悄生效。</p>
     */
    public record Fields(@JsonInclude(JsonInclude.Include.NON_NULL) String materialId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long quantity,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String productId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer generation,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String purchaseOrderId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String batchId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String trialId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String customerId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer round,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String contractId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal unitPrice,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long promisedAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long expiresAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String receivableId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String projectId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal capex,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long buildMillis,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer rampPeriods,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long addCapacity,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Boolean failed,
                         @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal price,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long period) {

        /** @return 空字段集（构造器便捷入口） */
        public static Fields none() {
            return new Fields(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }

        /**
         * 按动作校验字段：必填齐全、不得多填。
         *
         * @param action 经营动作
         * @return 本对象（链式）
         * @throws IllegalArgumentException 缺字段或多字段
         */
        public Fields require(CompanyOperationsRun.Action action) {
            Objects.requireNonNull(action, "动作");
            Map<String, Object> used = new LinkedHashMap<>();
            switch (action) {
                case ORDER_MATERIAL -> {
                    used.put("materialId", requireText(materialId, "物料"));
                    used.put("quantity", requirePositive(quantity, "数量"));
                }
                case RECEIVE_MATERIAL -> used.put("purchaseOrderId",
                        requireText(purchaseOrderId, "采购单"));
                case START_BATCH -> {
                    used.put("productId", requireText(productId, "产品"));
                    used.put("generation", requirePositive(generation, "代际"));
                    used.put("quantity", requirePositive(quantity, "数量"));
                }
                case COMPLETE_BATCH -> used.put("batchId", requireText(batchId, "批次"));
                case REQUEST_CERT -> {
                    used.put("trialId", requireText(trialId, "认证试验"));
                    used.put("customerId", requireText(customerId, "客户"));
                    used.put("productId", requireText(productId, "产品"));
                    used.put("generation", requirePositive(generation, "代际"));
                    used.put("round", requirePositive(round, "轮次"));
                    used.put("evidenceId", requireText(evidenceId, "证据"));
                }
                case FINISH_CERT -> used.put("trialId", requireText(trialId, "认证试验"));
                case SIGN_CONTRACT -> {
                    used.put("contractId", requireText(contractId, "合同"));
                    used.put("customerId", requireText(customerId, "客户"));
                    used.put("productId", requireText(productId, "产品"));
                    used.put("generation", requirePositive(generation, "代际"));
                    used.put("quantity", requirePositive(quantity, "数量"));
                    used.put("unitPrice", Objects.requireNonNull(unitPrice, "单价"));
                    used.put("promisedAt", Objects.requireNonNull(promisedAt, "承诺交付时点"));
                    used.put("expiresAt", Objects.requireNonNull(expiresAt, "合同失效时点"));
                    if (unitPrice.signum() <= 0) {
                        throw new IllegalArgumentException("单价必须为正");
                    }
                }
                case DELIVER -> {
                    used.put("contractId", requireText(contractId, "合同"));
                    used.put("quantity", requirePositive(quantity, "交付数量"));
                    used.put("receivableId", requireText(receivableId, "应收"));
                }
                case COLLECT -> used.put("receivableId", requireText(receivableId, "应收"));
                case MARK_OVERDUE, CANCEL_CONTRACT ->
                        used.put("contractId", requireText(contractId, "合同"));
                case WRITE_OFF_RECEIVABLE ->
                        used.put("receivableId", requireText(receivableId, "应收"));
                case START_EXPANSION -> {
                    used.put("projectId", requireText(projectId, "扩产项目"));
                    used.put("capex", Objects.requireNonNull(capex, "投资额"));
                    used.put("buildMillis", requirePositive(buildMillis, "建设周期"));
                    used.put("rampPeriods", requirePositive(rampPeriods, "爬坡期数"));
                    used.put("addCapacity", requirePositive(addCapacity, "新增产能"));
                    if (capex.signum() <= 0) {
                        throw new IllegalArgumentException("投资额必须为正");
                    }
                }
                case COMPLETE_EXPANSION -> {
                    used.put("projectId", requireText(projectId, "扩产项目"));
                    used.put("failed", Objects.requireNonNull(failed, "失败标志"));
                }
                case SELL_RESIDUAL -> {
                    used.put("projectId", requireText(projectId, "扩产项目"));
                    used.put("price", Objects.requireNonNull(price, "处置价"));
                    if (price.signum() < 0) {
                        throw new IllegalArgumentException("处置价不能为负");
                    }
                }
                case CLOSE_PERIOD -> used.put("period", Objects.requireNonNull(period, "期间"));
                default -> throw new IllegalArgumentException("未支持的动作：" + action);
            }
            for (Map.Entry<String, Object> field : all().entrySet()) {
                if (!used.containsKey(field.getKey()) && field.getValue() != null) {
                    throw new IllegalArgumentException(action + " 不接受字段 " + field.getKey());
                }
            }
            return this;
        }

        /** @return 全部字段的显式映射（用于"多填字段"校验与投影） */
        @JsonIgnore
        public Map<String, Object> all() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("materialId", materialId);
            values.put("quantity", quantity);
            values.put("productId", productId);
            values.put("generation", generation);
            values.put("purchaseOrderId", purchaseOrderId);
            values.put("batchId", batchId);
            values.put("trialId", trialId);
            values.put("evidenceId", evidenceId);
            values.put("customerId", customerId);
            values.put("round", round);
            values.put("contractId", contractId);
            values.put("unitPrice", unitPrice);
            values.put("promisedAt", promisedAt);
            values.put("expiresAt", expiresAt);
            values.put("receivableId", receivableId);
            values.put("projectId", projectId);
            values.put("capex", capex);
            values.put("buildMillis", buildMillis);
            values.put("rampPeriods", rampPeriods);
            values.put("addCapacity", addCapacity);
            values.put("failed", failed);
            values.put("price", price);
            values.put("period", period);
            return values;
        }

        private static String requireText(String value, String name) {
            return CompanyOperationsRun.requireText(value, name);
        }

        private static long requirePositive(Long value, String name) {
            return CompanyOperationsRun.requirePositive(value, name);
        }

        private static int requirePositive(Integer value, String name) {
            return CompanyOperationsRun.requirePositive(value, name);
        }
    }

    /** 必填文本校验（嵌套记录共用）。 */
    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    /** 必填正整数校验（嵌套记录共用）。 */
    static long requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + "必须为正");
        }
        return value;
    }

    /** 必填正整数校验（嵌套记录共用）。 */
    static int requirePositive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + "必须为正");
        }
        return value;
    }
}
