package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个角色可见的只读视图：由同一份已确认世界快照派生，不是管理端原始状态。
 *
 * <p><b>不包含</b>：全局持仓/成交序号、隐藏实体数量、任何内部来源标识或批次标识、别人的账户、
 * 任务或阅读经历、整份开局登记表、公司内部账、董事会原始状态。所有集合在构造期冻结。</p>
 *
 * <p><b>现金口径</b>：产品账户与个人账户共用唯一 {@link CashLedger} 余额；这里只是同一余额的
 * 只读投影，不复制第二份资金。冻结来自活动买单与卖出预留，可用 = 余额 − 冻结。</p>
 *
 * <p><b>工具口径</b>：{@link #tools()} 只列出本单元真正接入的阅读能力。岗位权限（例如执行交易员
 * 可以对授权账户下单）只作为 {@link #permissions()} 的状态出现，<b>不注册也不实现</b>任何角色
 * 下单、改目标或风险暂停工具；四岗 LLM 并未在本单元运行。</p>
 *
 * @param timeMillis    派生时刻，只能是世界时间
 * @param personPartyId 视图所属人物
 * @param appointments  本人当前有效任职
 * @param accounts      本人个人账户与经当前任职明确授权的产品账户
 * @param holdings      上述账户的证券按代码汇总（不含批次标识）
 * @param market        公共市场聚合（不含对手账户与内部成交序号）
 * @param catalog       本人此刻可见的资料目录（不含正文）
 * @param observations  本人真正读完的经历（固定版本全文，仅本人）
 * @param readings      本人当前活动的阅读单（不含正文）
 * @param permissions   岗位动作的权限状态，仅限本人可见范围
 * @param tools         已接入的阅读工具
 */
public record RoleView(long timeMillis, String personPartyId,
                       List<Appointment> appointments, List<Account> accounts,
                       List<Holding> holdings, List<Market> market,
                       List<InformationModel.CatalogEntry> catalog,
                       List<Experience> observations,
                       List<Reading> readings, List<Permission> permissions,
                       List<String> tools) {

    /** 阅读工具：查目录。 */
    public static final String TOOL_CATALOG = "information.catalog";
    /** 阅读工具：开始读一个明确版本。 */
    public static final String TOOL_START_READ = "information.startRead";
    /** 阅读工具：判定本人能否引用。 */
    public static final String TOOL_CITE = "information.cite";

    /** 冻结全部集合。 */
    public RoleView {
        if (timeMillis < 0) {
            throw new IllegalArgumentException("视图时刻不能为负：" + timeMillis);
        }
        if (personPartyId == null || personPartyId.isBlank()) {
            throw new IllegalArgumentException("视图人物不能为空");
        }
        appointments = List.copyOf(appointments);
        accounts = List.copyOf(accounts);
        holdings = List.copyOf(holdings);
        market = List.copyOf(market);
        catalog = List.copyOf(catalog);
        observations = List.copyOf(observations);
        readings = List.copyOf(readings);
        permissions = List.copyOf(permissions);
        tools = List.copyOf(tools);
    }

    /**
     * 本人当前任职。
     *
     * @param appointmentId       任职标识
     * @param organizationPartyId 任职组织
     * @param role                岗位
     * @param startsAt            起始时刻（含）
     * @param endsAt              预定结束时刻（不含）
     */
    public record Appointment(String appointmentId, String organizationPartyId,
                              RoleAccess.Role role, long startsAt, long endsAt) {
    }

    /** 账户种类：本人个人账户，或经当前任职明确授权的产品账户。 */
    public enum AccountKind {
        /** 本人个人账户。 */
        PERSONAL,
        /** 机构产品账户。 */
        PRODUCT
    }

    /**
     * 可见账户的余额投影。
     *
     * @param accountId    账户标识
     * @param kind         账户种类
     * @param ownerId      财产主体标识
     * @param cash         账户余额
     * @param frozenCash   活动买单冻结
     * @param availableCash 可用现金（余额 − 冻结）
     */
    public record Account(String accountId, AccountKind kind, String ownerId, BigDecimal cash,
                          BigDecimal frozenCash, BigDecimal availableCash) {

        /** 校验字段与金额关系。 */
        public Account {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("账户标识不能为空");
            }
            Objects.requireNonNull(kind, "账户种类");
            Objects.requireNonNull(cash, "账户余额");
            Objects.requireNonNull(frozenCash, "账户冻结");
            Objects.requireNonNull(availableCash, "账户可用");
            if (frozenCash.signum() < 0 || availableCash.signum() < 0) {
                throw new IllegalArgumentException("冻结或可用不能为负：" + accountId);
            }
            if (cash.subtract(frozenCash).compareTo(availableCash) != 0) {
                throw new IllegalArgumentException("可用现金必须等于余额减冻结：" + accountId);
            }
        }
    }

    /**
     * 证券汇总（按账户与代码合并，不含批次）。
     *
     * @param accountId 持股账户
     * @param symbol    证券代码
     * @param quantity  总数量（含冻结）
     * @param frozen    卖出预留冻结
     * @param available 可卖数量
     */
    public record Holding(String accountId, String symbol, long quantity, long frozen,
                          long available) {

        /** 校验数量关系。 */
        public Holding {
            if (accountId == null || accountId.isBlank() || symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("持股账户与代码不能为空");
            }
            if (quantity < 0 || frozen < 0 || available < 0 || frozen + available != quantity) {
                throw new IllegalArgumentException("持股数量关系非法：" + accountId + "/" + symbol);
            }
        }
    }

    /**
     * 公共市场聚合。
     *
     * @param symbol             证券代码
     * @param lastPrice          最近成交价；无成交为空
     * @param lastTradeQuantity  最近成交数量；无成交为 0
     * @param lastTradeAtMillis  最近成交时刻；无成交为 -1
     * @param bestBid            买一价；无挂单为空
     * @param bestAsk            卖一价；无挂单为空
     * @param reference          同截止除息参考价；旧档案为 null 且不落 JSON
     */
    public record Market(String symbol, BigDecimal lastPrice, long lastTradeQuantity,
                         long lastTradeAtMillis, BigDecimal bestBid, BigDecimal bestAsk,
                         @com.fasterxml.jackson.annotation.JsonInclude(
                                 com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         ValuationModel.Price reference) {

        /**
         * 旧 6 参构造：保持源码兼容，除息参考为空。
         *
         * @param symbol            证券代码
         * @param lastPrice         最近成交价
         * @param lastTradeQuantity 最近成交数量
         * @param lastTradeAtMillis 最近成交时刻
         * @param bestBid           买一价
         * @param bestAsk           卖一价
         */
        public Market(String symbol, BigDecimal lastPrice, long lastTradeQuantity,
                      long lastTradeAtMillis, BigDecimal bestBid, BigDecimal bestAsk) {
            this(symbol, lastPrice, lastTradeQuantity, lastTradeAtMillis, bestBid, bestAsk, null);
        }
    }

    /**
     * 本人范围的阅读引用：资料 + 固定版本 + <b>本人的第几次阅读</b>。
     *
     * <p>序号只在本人范围内按内部阅读记录顺序推算，因此不含全局 {@code READ-n}、不随别人多读
     * 或少读而改变；同一人物在同一时刻取消后重开也会得到不同序号，旧引用无法命中新实例。</p>
     *
     * @param personPartyId 引用所属人物
     * @param docId         资料标识
     * @param version       固定版本
     * @param index         本人范围内的阅读序号，从 1 开始
     */
    public record ReadingRef(String personPartyId, String docId, long version, long index) {

        /** 校验字段。 */
        public ReadingRef {
            if (personPartyId == null || personPartyId.isBlank()) {
                throw new IllegalArgumentException("阅读引用的所属人物不能为空");
            }
            if (docId == null || docId.isBlank()) {
                throw new IllegalArgumentException("阅读引用的资料标识不能为空");
            }
            if (version < 1) {
                throw new IllegalArgumentException("阅读引用的版本必须从 1 开始：" + version);
            }
            if (index < 1) {
                throw new IllegalArgumentException("阅读引用的本人序号必须从 1 开始：" + index);
            }
        }
    }

    /**
     * 本人已完成的阅读经历：固定版本全文与公开来源，仅本人可见。
     *
     * <p>不含内部审计源、全局阅读序号、别人的经历或资料原件。</p>
     *
     * @param ref                    本人范围引用
     * @param title                  当时标题
     * @param body                   当时正文
     * @param publicSourceDescription 当时公开来源描述
     * @param publishedAt            该版本发布时刻
     * @param completedAt            完成时刻，等于阅读的固定到期
     */
    public record Experience(ReadingRef ref, String title, String body,
                             String publicSourceDescription, long publishedAt,
                             long completedAt) {

        /** 校验字段。 */
        public Experience {
            if (ref == null) {
                throw new IllegalArgumentException("经历缺少本人范围引用");
            }
            if (title == null || title.isBlank() || body == null || body.isBlank()) {
                throw new IllegalArgumentException("经历缺少标题或正文");
            }
            if (publicSourceDescription == null) {
                throw new IllegalArgumentException("经历缺少公开来源描述");
            }
            if (publishedAt < 0 || completedAt < 0) {
                throw new IllegalArgumentException("经历时点不能为负");
            }
        }
    }

    /**
     * 本人当前活动阅读（不含正文，到期前不交付全文）。
     *
     * @param ref   本人范围引用
     * @param dueAt 应完成时刻
     */
    public record Reading(ReadingRef ref, long dueAt) {

        /** 校验字段。 */
        public Reading {
            if (ref == null) {
                throw new IllegalArgumentException("活动阅读缺少本人范围引用");
            }
            if (dueAt <= 0) {
                throw new IllegalArgumentException("应完成时刻必须为正");
            }
        }
    }

    /**
     * 岗位动作权限状态。
     *
     * @param action   动作
     * @param scope    判定范围描述，只含本人可见组织/产品/账户
     * @param decision 许可或拒绝
     */
    public record Permission(RoleAccess.Action action, String scope, RoleAccess.Decision decision) {

        /** 校验字段。 */
        public Permission {
            Objects.requireNonNull(action, "动作");
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("权限范围不能为空");
            }
            Objects.requireNonNull(decision, "判定结果");
        }
    }

    /**
     * 视图的工具清单是否只包含阅读能力。
     *
     * @return 仅含阅读工具时返回 true
     */
    public boolean toolsAreReadOnly() {
        for (String tool : tools) {
            if (!TOOL_CATALOG.equals(tool) && !TOOL_START_READ.equals(tool) && !TOOL_CITE.equals(tool)) {
                return false;
            }
        }
        return true;
    }

    /** @return 不可修改的观察清单（防御性再冻结） */
    @Override
    public List<Experience> observations() {
        return Collections.unmodifiableList(observations);
    }
}
