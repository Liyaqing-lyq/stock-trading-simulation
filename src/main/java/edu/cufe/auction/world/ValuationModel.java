package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * D1 统一账户估值：同一截止上的现金、持仓、分红应收与公司应付。
 *
 * <p><b>能力边界</b>：纯计算，不产生资产、现金腿、世界事件或第二份账。所有输入都是同一世界快照的
 * 纯数据；"它们确实来自同一截止"由外层 Runtime 适配器保证，本类只能核对价格簿位置/时点一致、
 * 分红历史不晚于截止、证券批次不晚于截止。</p>
 *
 * <p><b>计价口径</b>：现金只取 {@link CashLedger.CashAccount#cash()} 一次（买单冻结已包含在余额里，
 * 不再相加）；持仓按 {@link SecuritiesLedger.Lot#remaining()} 乘价，含卖单冻结、{@code LOCKED}
 * 与 {@code TREASURY}；后者作为明确持有数量保留在组织明细中。分红投资者应收复用
 * {@link DividendModel#holderNetReceivable}，公司合同应付复用 {@link DividendModel#companyPayable}，
 * 不重新实现权利或减值。</p>
 *
 * <p><b>缺价</b>：任一持仓缺价时持仓与净资产都为空，并列出全部缺失标的，绝不输出部分净值、
 * 绝不用成本价或零替代。组织主体的完整净资产一律为空，状态为
 * {@link Status#UNSUPPORTED_COMPANY_SCOPE}，因为经营资产与其它义务尚未纳入。</p>
 */
public final class ValuationModel {

    /** 价格来源；缺价与"零价格"是两件事。 */
    public enum PriceSource {
        /** 截止前的真实成交。 */
        TRADE,
        /** 该标的从未成交时的开局参考估值。 */
        OPENING_REFERENCE,
        /** 已确认现金分红扣除每股股息后的除息参考价。 */
        EX_DIVIDEND_REFERENCE,
        /** 既无成交也无开局参考。 */
        MISSING
    }

    /** 账户估值状态。 */
    public enum Status {
        /** 所有持仓都有价格，且不是组织主体。 */
        COMPLETE,
        /** 至少一个持仓缺价。 */
        MISSING_PRICE,
        /** 组织主体：经营资产与其它义务未纳入，不给完整净资产。 */
        UNSUPPORTED_COMPANY_SCOPE
    }

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private ValuationModel() {
    }

    /**
     * 一个标的在截止时点的价格与来源。
     *
     * @param symbol    证券代码
     * @param price     价格；{@link PriceSource#MISSING} 时为 null
     * @param source    来源
     * @param asOfMillis 价格来源时点；缺价为 -1
     * @param ageMillis 陈旧毫秒（截止减来源时点）；缺价为 -1
     */
    public record Price(String symbol, BigDecimal price, PriceSource source,
                        long asOfMillis, long ageMillis) {

        /** 校验来源与空值、非负价格、2 位精度与陈旧度一致。 */
        public Price {
            requireText(symbol, "证券代码");
            Objects.requireNonNull(source, "价格来源");
            if (source == PriceSource.MISSING) {
                if (price != null || asOfMillis != -1L || ageMillis != -1L) {
                    throw new IllegalArgumentException("缺价必须同时为空值与 -1 时点：" + symbol);
                }
            } else {
                if (price == null) {
                    throw new IllegalArgumentException("有来源的价格不能为空：" + symbol);
                }
                price = money(price, "价格");
                if (price.signum() < 0) {
                    throw new IllegalArgumentException("价格不能为负：" + symbol);
                }
                if (asOfMillis < 0 || ageMillis < 0) {
                    throw new IllegalArgumentException("来源时点与陈旧度不能为负：" + symbol);
                }
                if (source == PriceSource.OPENING_REFERENCE && asOfMillis != 0) {
                    throw new IllegalArgumentException("开局参考价格的来源时点必须为零：" + symbol);
                }
            }
        }
    }

    /**
     * 同一截止的完整价格簿。
     *
     * @param position 该价格簿所属的世界提交位置
     * @param atMillis 该价格簿的截止模拟时点
     * @param prices   按证券代码索引的价格
     */
    public record PriceBook(long position, long atMillis, Map<String, Price> prices) {

        /** 校验位置、时点与集合深不可变。 */
        public PriceBook {
            if (position < 0 || atMillis < 0) {
                throw new IllegalArgumentException("价格簿位置与时点不能为负");
            }
            Map<String, Price> copy = new TreeMap<>(Objects.requireNonNull(prices, "价格表"));
            for (Map.Entry<String, Price> entry : copy.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().symbol())) {
                    throw new IllegalArgumentException("价格索引与代码不一致：" + entry.getKey());
                }
                Price price = entry.getValue();
                if (price.source() != PriceSource.MISSING
                        && (price.asOfMillis() > atMillis || price.ageMillis() != atMillis - price.asOfMillis())) {
                    throw new IllegalArgumentException("价格来源时点或陈旧度与截止不一致：" + entry.getKey());
                }
            }
            prices = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * 一条持仓明细：同一账户同一标的的全部批次已合并。
     *
     * @param accountId 持股账户
     * @param symbol    证券代码
     * @param quantity  数量合计，含冻结与锁定
     * @param price     使用的价格（可能是缺价）
     * @param value     市值；缺价时为 null
     */
    public record HoldingLine(String accountId, String symbol, long quantity,
                              Price price, BigDecimal value) {

        /** 校验数量、价格与缺价时的空值一致。 */
        public HoldingLine {
            requireText(accountId, "持股账户");
            requireText(symbol, "证券代码");
            if (quantity <= 0) {
                throw new IllegalArgumentException("持仓数量必须为正：" + accountId + "/" + symbol);
            }
            Objects.requireNonNull(price, "价格");
            if (price.source() == PriceSource.MISSING) {
                if (value != null) {
                    throw new IllegalArgumentException("缺价明细不能携带市值：" + symbol);
                }
            } else {
                value = money(Objects.requireNonNull(value, "市值"), "市值");
                if (value.signum() < 0) {
                    throw new IllegalArgumentException("市值不能为负：" + symbol);
                }
            }
        }
    }

    /**
     * 一名财产主体在同一截止的财产视图。
     *
     * @param ownerId             财产主体
     * @param position            世界提交位置
     * @param atMillis            截止模拟时点
     * @param status              估值状态
     * @param cash                现金合计（含冻结，只计一次）
     * @param holdings            持仓市值；缺价时为 null
     * @param dividendReceivable  投资者净应收
     * @param dividendPayable     该公司作为发行人的合同未付分红
     * @param netWorth            净资产；缺价或组织主体时为 null
     * @param missingSymbols      缺价标的，按代码排序
     * @param lines               持仓明细
     */
    public record Value(String ownerId, long position, long atMillis, Status status,
                        BigDecimal cash, BigDecimal holdings, BigDecimal dividendReceivable,
                        BigDecimal dividendPayable, BigDecimal netWorth,
                        Set<String> missingSymbols, List<HoldingLine> lines) {

        /** 校验字段、金额精度、状态与空值组合，并冻结集合。 */
        public Value {
            requireText(ownerId, "财产主体");
            Objects.requireNonNull(status, "估值状态");
            if (position < 0 || atMillis < 0) {
                throw new IllegalArgumentException("估值位置与时点不能为负");
            }
            cash = money(cash, "现金");
            dividendReceivable = money(dividendReceivable, "分红应收");
            dividendPayable = money(dividendPayable, "分红应付");
            if (cash.signum() < 0) {
                throw new IllegalArgumentException("现金不能为负：" + ownerId);
            }
            if (holdings != null) {
                holdings = money(holdings, "持仓市值");
            }
            if (netWorth != null) {
                netWorth = money(netWorth, "净资产");
            }
            if (status != Status.COMPLETE && netWorth != null) {
                throw new IllegalArgumentException("非完整估值不能给出净资产：" + ownerId);
            }
            if (status == Status.COMPLETE && (holdings == null || netWorth == null)) {
                throw new IllegalArgumentException("完整估值必须有持仓与净资产：" + ownerId);
            }
            Set<String> missing = new TreeSet<>(Objects.requireNonNull(missingSymbols, "缺价标的"));
            List<HoldingLine> lineCopy = new ArrayList<>(Objects.requireNonNull(lines, "持仓明细"));
            lineCopy.sort(Comparator.comparing(HoldingLine::accountId)
                    .thenComparing(HoldingLine::symbol));
            missingSymbols = Collections.unmodifiableSet(missing);
            lines = Collections.unmodifiableList(lineCopy);
        }
    }

    /**
     * 构建价格簿：截止前按（时点、成交号）取最后一笔真实成交，否则用开局参考。
     *
     * @param position     世界提交位置
     * @param cutoffMillis 截止模拟时点
     * @param exchange     交易子集；null 表示该快照未启用撮合
     * @param registry     开局登记，用于识别身份与初始参考
     * @param symbols      需要定价的证券代码
     * @return 同一截止的价格簿
     */
    public static PriceBook prices(long position, long cutoffMillis, WorldExchange.State exchange,
                                   OpeningRegistry registry, Collection<String> symbols) {
        return prices(position, cutoffMillis, exchange, registry, symbols, DividendModel.empty());
    }

    /**
     * 带已确认分红方案的除息参考价格簿。
     *
     * <p>先按原规则取截止前最后真实成交（同刻比成交号），没有成交才用明确开局参考；未知标的保持缺价。
     * 仅对 {@code EX_BOOKED} 或 {@code SETTLED} 且 {@code baseTime < exAt <= cutoff} 的方案扣减每股股息，
     * 同刻成交视为除息后成交不再扣减；扣减后为负价视为缺价。除息参考是独立来源，
     * 不改写成交链、证券账或任何现金；付款账户归属由估值入口核对。</p>
     *
     * @param position     世界提交位置
     * @param cutoffMillis 截止模拟时点
     * @param exchange     交易子集；null 表示未启用撮合
     * @param registry     开局登记
     * @param symbols      需要定价的证券代码
     * @param dividends    同一截止的分红状态；null 拒绝
     * @return 同一截止的价格簿
     * @throws IllegalArgumentException 位置/时间非法、D0 历史晚于截止、或方案映射不合法
     */
    public static PriceBook prices(long position, long cutoffMillis, WorldExchange.State exchange,
                                   OpeningRegistry registry, Collection<String> symbols,
                                   DividendModel.State dividends) {
        if (dividends == null) {
            throw new IllegalArgumentException("分红状态不能为空");
        }
        if (position < 0 || cutoffMillis < 0) {
            throw new IllegalArgumentException("价格簿位置与时点不能为负");
        }
        Objects.requireNonNull(registry, "开局登记");
        Objects.requireNonNull(symbols, "证券代码集合");
        if (dividends.lastPosition() > position || dividends.lastAtMillis() > cutoffMillis) {
            throw new IllegalArgumentException("分红历史晚于查询截止");
        }
        for (DividendModel.Scheme scheme : dividends.schemes().values()) {
            checkSchemeIssuerMapping(registry, scheme);
        }
        Map<String, Price> prices = new TreeMap<>();
        for (String symbol : symbols) {
            requireText(symbol, "证券代码");
            Price price = tradePrice(symbol, cutoffMillis, exchange);
            if (price == null) {
                OpeningRegistry.Security security = registry.security(symbol);
                price = security == null
                        ? new Price(symbol, null, PriceSource.MISSING, -1L, -1L)
                        : new Price(symbol, security.initialReferencePrice(),
                                PriceSource.OPENING_REFERENCE, 0L, cutoffMillis);
            }
            prices.put(symbol, withExDividend(price, symbol, cutoffMillis, dividends));
        }
        return new PriceBook(position, cutoffMillis, prices);
    }

    /**
     * 在原始价格上叠加已确认现金分红的除息参考。
     *
     * @param base         原始价格（成交或开局参考）
     * @param symbol       证券代码
     * @param cutoffMillis 查询截止
     * @param dividends    分红状态
     * @return 除息参考价、原价格或缺价
     */
    private static Price withExDividend(Price base, String symbol, long cutoffMillis,
                                        DividendModel.State dividends) {
        if (base.source() == PriceSource.MISSING) {
            return base;
        }
        BigDecimal deducted = ZERO;
        long latestEx = -1L;
        for (DividendModel.Scheme scheme : dividends.schemes().values()) {
            if (!scheme.symbol().equals(symbol)) {
                continue;
            }
            if (scheme.phase() != DividendModel.Phase.EX_BOOKED
                    && scheme.phase() != DividendModel.Phase.SETTLED) {
                continue;
            }
            long exAt = scheme.exAtMillis();
            if (base.asOfMillis() >= exAt || exAt > cutoffMillis) {
                continue;
            }
            deducted = deducted.add(scheme.perShare());
            latestEx = Math.max(latestEx, exAt);
        }
        if (deducted.signum() <= 0) {
            return base;
        }
        BigDecimal adjusted = base.price().subtract(deducted);
        if (adjusted.signum() < 0) {
            return new Price(symbol, null, PriceSource.MISSING, -1L, -1L);
        }
        return new Price(symbol, adjusted, PriceSource.EX_DIVIDEND_REFERENCE, latestEx,
                cutoffMillis - latestEx);
    }

    /**
     * 核对方案的发行财产主体与证券发行组织是同一登记组织。
     *
     * @param registry  开局登记
     * @param scheme    分红方案
     */
    private static void checkSchemeIssuerMapping(OpeningRegistry registry,
                                                 DividendModel.Scheme scheme) {
        OpeningRegistry.PropertyOwner issuer = registry.owner(scheme.issuerOwnerId());
        if (issuer == null || issuer.kind() != OpeningRegistry.OwnerKind.ORGANIZATION) {
            throw new IllegalArgumentException("发行财产主体不是已登记组织：" + scheme.schemeId());
        }
        OpeningRegistry.Security security = registry.security(scheme.symbol());
        if (security == null
                || !Objects.equals(security.issuerOrganizationId(), issuer.partyId())) {
            throw new IllegalArgumentException("证券发行组织与发行财产主体不一致：" + scheme.schemeId());
        }
    }

    /**
     * 读取一个标的的价格；簿中没有登记时返回缺价。
     *
     * @param book   价格簿
     * @param symbol 证券代码
     * @return 价格
     */
    public static Price priceOf(PriceBook book, String symbol) {
        Objects.requireNonNull(book, "价格簿");
        requireText(symbol, "证券代码");
        Price price = book.prices().get(symbol);
        return price != null ? price : new Price(symbol, null, PriceSource.MISSING, -1L, -1L);
    }

    /**
     * 计算一名财产主体在同一截止的财产视图。
     *
     * <p>先完成全部引用校验（未知主体、孤儿现金/持仓/债权账户、发行组织与付款账户对齐、
     * 未来取得批次、分红历史晚于截止），再计算金额；任何非法引用直接拒绝，不静默遗漏。</p>
     *
     * @param ownerId    财产主体
     * @param position   世界提交位置
     * @param atMillis   截止模拟时点
     * @param registry   开局登记
     * @param accounts   现金账户表
     * @param securities 证券台账
     * @param dividends  分红状态
     * @param book       同一截止的价格簿
     * @return 财产视图
     */
    public static Value value(String ownerId, long position, long atMillis, OpeningRegistry registry,
                              Map<String, CashLedger.CashAccount> accounts,
                              SecuritiesLedger.State securities, DividendModel.State dividends,
                              PriceBook book) {
        requireText(ownerId, "财产主体");
        if (position < 0 || atMillis < 0) {
            throw new IllegalArgumentException("估值位置与时点不能为负");
        }
        Objects.requireNonNull(registry, "开局登记");
        Objects.requireNonNull(accounts, "现金账户表");
        Objects.requireNonNull(securities, "证券台账");
        Objects.requireNonNull(dividends, "分红状态");
        Objects.requireNonNull(book, "价格簿");
        if (book.position() != position || book.atMillis() != atMillis) {
            throw new IllegalArgumentException("价格簿位置或截止与估值不一致");
        }

        OpeningRegistry.PropertyOwner owner = registry.owner(ownerId);
        if (owner == null) {
            throw new IllegalArgumentException("未知财产主体：" + ownerId);
        }
        checkReferences(registry, accounts, securities, dividends, position, atMillis);

        BigDecimal cash = ZERO;
        for (CashLedger.CashAccount account : accounts.values()) {
            if (account.ownerId().equals(ownerId)) {
                cash = cash.add(account.cash());
            }
        }

        Map<HoldingKey, Long> quantities = new TreeMap<>(Comparator.comparing(HoldingKey::accountId)
                .thenComparing(HoldingKey::symbol));
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            CashLedger.CashAccount account = accounts.get(lot.accountId());
            if (!account.ownerId().equals(ownerId)) {
                continue;
            }
            quantities.merge(new HoldingKey(lot.accountId(), lot.symbol()), lot.remaining(), Math::addExact);
        }

        List<HoldingLine> lines = new ArrayList<>();
        Set<String> missing = new TreeSet<>();
        BigDecimal holdings = ZERO;
        for (Map.Entry<HoldingKey, Long> entry : quantities.entrySet()) {
            String accountId = entry.getKey().accountId();
            String symbol = entry.getKey().symbol();
            Price price = priceOf(book, symbol);
            if (price.source() == PriceSource.MISSING) {
                missing.add(symbol);
                lines.add(new HoldingLine(accountId, symbol, entry.getValue(), price, null));
            } else {
                BigDecimal lineValue = price.price().multiply(BigDecimal.valueOf(entry.getValue()));
                holdings = holdings.add(lineValue);
                lines.add(new HoldingLine(accountId, symbol, entry.getValue(), price, lineValue));
            }
        }
        lines.sort(Comparator.comparing(HoldingLine::accountId).thenComparing(HoldingLine::symbol));

        BigDecimal receivable = ZERO;
        for (DividendModel.Claim claim : dividends.claims().values()) {
            CashLedger.CashAccount account = accounts.get(claim.accountId());
            if (account != null && account.ownerId().equals(ownerId)) {
                receivable = receivable.add(DividendModel.holderNetReceivable(claim));
            }
        }
        BigDecimal payable = ZERO;
        for (DividendModel.Scheme scheme : dividends.schemes().values()) {
            if (scheme.issuerOwnerId().equals(ownerId)) {
                payable = payable.add(DividendModel.companyPayable(dividends, scheme.schemeId()));
            }
        }

        boolean organization = owner.kind() == OpeningRegistry.OwnerKind.ORGANIZATION;
        boolean missingPrice = !missing.isEmpty();
        Status status = organization ? Status.UNSUPPORTED_COMPANY_SCOPE
                : missingPrice ? Status.MISSING_PRICE : Status.COMPLETE;
        BigDecimal holdingsValue = missingPrice ? null : money(holdings, "持仓市值");
        BigDecimal netWorth = status == Status.COMPLETE
                ? money(cash.add(holdingsValue).add(receivable).subtract(payable), "净资产")
                : null;
        return new Value(ownerId, position, atMillis, status, cash, holdingsValue,
                receivable, payable, netWorth, missing, lines);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private record HoldingKey(String accountId, String symbol) {
    }

    private static void checkReferences(OpeningRegistry registry,
                                        Map<String, CashLedger.CashAccount> accounts,
                                        SecuritiesLedger.State securities,
                                        DividendModel.State dividends,
                                        long position, long atMillis) {
        for (Map.Entry<String, CashLedger.CashAccount> entry : accounts.entrySet()) {
            if (registry.owner(entry.getValue().ownerId()) == null) {
                throw new IllegalArgumentException("现金账户属主未登记：" + entry.getKey());
            }
        }
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            if (!accounts.containsKey(lot.accountId())) {
                throw new IllegalArgumentException("持仓账户未在现金账户表登记：" + lot.accountId());
            }
            if (lot.acquiredAtMillis() > atMillis) {
                throw new IllegalArgumentException("存在晚于估值截止的取得批次：" + lot.lotId());
            }
        }
        for (DividendModel.Claim claim : dividends.claims().values()) {
            if (!accounts.containsKey(claim.accountId())) {
                throw new IllegalArgumentException("债权账户未在现金账户表登记：" + claim.accountId());
            }
        }
        for (DividendModel.Scheme scheme : dividends.schemes().values()) {
            checkSchemeIssuerMapping(registry, scheme);
            CashLedger.CashAccount company = accounts.get(scheme.companyAccountId());
            if (company == null || !company.ownerId().equals(scheme.issuerOwnerId())) {
                throw new IllegalArgumentException("公司付款账户不属于发行财产主体：" + scheme.schemeId());
            }
        }
        if (dividends.lastPosition() > position || dividends.lastAtMillis() > atMillis) {
            throw new IllegalArgumentException("分红历史晚于估值截止");
        }
    }

    private static Price tradePrice(String symbol, long cutoffMillis, WorldExchange.State exchange) {
        if (exchange == null) {
            return null;
        }
        WorldExchange.Trade best = null;
        for (WorldExchange.Trade trade : exchange.trades()) {
            if (!trade.symbol().equals(symbol) || trade.timeMillis() > cutoffMillis) {
                continue;
            }
            if (best == null || trade.timeMillis() > best.timeMillis()
                    || (trade.timeMillis() == best.timeMillis() && trade.tradeId() > best.tradeId())) {
                best = trade;
            }
        }
        if (best == null) {
            return null;
        }
        return new Price(symbol, best.price(), PriceSource.TRADE, best.timeMillis(),
                cutoffMillis - best.timeMillis());
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到分：" + value, failure);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
