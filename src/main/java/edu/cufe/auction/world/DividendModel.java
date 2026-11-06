package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 现金分红的权利、应收与清偿元数据纯核（D0）。
 *
 * <p><b>能力边界</b>：这里只计算"谁、按多少股、应得多少、公司还欠多少、投资者账面净应收多少"。
 * 不持有现金、不持有实时持仓、不取价格、不做估值与 TWR、不做董事会表决、不写盘、不发通知。
 * 现金真实出款、同批逐笔核对与故障恢复由后续运行桥完成；本类的清偿动作只登记宿主已经完成的
 * 现金事实引用，<b>不能</b>凭一次成功返回值创造资金。</p>
 *
 * <p><b>登记与确认分离</b>：{@code REGISTERED} 只固定受益人归属，claims 为空，公司应付与投资者应收
 * 均为 0.00；{@code bookEx}（除息批）才一次性生成 claims，此后 {@code companyPayable} 等于全部合同未付额。
 * 同一方案的登记、除息各只能发生一次，历史只增不改。</p>
 *
 * <p><b>金额口径</b>：金额一律 {@link BigDecimal} 且规范到 2 位小数（不舍入），数量 {@code long}。
 * 面值 = 每股金额 × 登记股数，使用精确乘法，不使用 {@code double}，也不先把金额折算成"分"的 long。</p>
 *
 * <p><b>减值与债务</b>：投资者减值只降低 {@code holderNetReceivable}，不改变合同面值、已付金额或公司
 * 未偿债务；真实整批付款确认时清除对应减值并在清偿记录保留转回额，供后续投资损益核算。
 * 债务减免属于另一类规则事件，本类不提供。</p>
 *
 * <p><b>幂等</b>：每个受理事件有稳定 {@code eventId}、严格递增的世界位置与非倒退的模拟时点。
 * 相同事件 ID 与完整原始请求的重放返回原回报且不追加；相同 ID 不同内容拒绝并保留完整旧状态。</p>
 */
public final class DividendModel {

    /** 方案阶段；{@code SETTLED} 与 {@code CANCELLED} 为终态。 */
    public enum Phase {
        /** 已提案，尚未授权。 */
        PROPOSED,
        /** 已记录宿主授权引用，尚未登记。 */
        AUTHORIZED,
        /** 已按登记截止冻结受益人快照，尚未确认义务。 */
        REGISTERED,
        /** 已确认义务并生成全部 claims。 */
        EX_BOOKED,
        /** 全部权益已整批清偿。 */
        SETTLED,
        /** 登记前撤销。 */
        CANCELLED
    }

    /** 提案被受理。 */
    public static final String REASON_OK = "OK";
    /** 相同事件被重放，返回原回报。 */
    public static final String REASON_REPLAYED = "REPLAYED";
    /** 事件 ID 已用于不同请求。 */
    public static final String REASON_EVENT_ID_CONFLICT = "EVENT_ID_CONFLICT";
    /** 世界位置未严格递增。 */
    public static final String REASON_POSITION_NOT_INCREASING = "POSITION_NOT_INCREASING";
    /** 模拟时点倒退。 */
    public static final String REASON_TIME_BACKWARDS = "TIME_BACKWARDS";
    /** 参数缺失或为空。 */
    public static final String REASON_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /** 方案不存在。 */
    public static final String REASON_UNKNOWN_SCHEME = "UNKNOWN_SCHEME";
    /** 方案标识已被占用。 */
    public static final String REASON_SCHEME_EXISTS = "SCHEME_EXISTS";
    /** 当前阶段不允许该动作。 */
    public static final String REASON_INVALID_PHASE = "INVALID_PHASE";
    /** 时点组合不合法或动作不在规定截止。 */
    public static final String REASON_INVALID_TIMELINE = "INVALID_TIMELINE";
    /** 金额非正、非 2 位或缺失。 */
    public static final String REASON_INVALID_AMOUNT = "INVALID_AMOUNT";
    /** 每股金额无法精确表示为 2 位小数。 */
    public static final String REASON_PER_SHARE_PRECISION = "PER_SHARE_PRECISION";
    /** 登记时没有任何合格持有人。 */
    public static final String REASON_NO_HOLDERS = "NO_HOLDERS";
    /** 方案不存在或已被占用。 */
    public static final String REASON_UNKNOWN_CLAIM = "UNKNOWN_CLAIM";
    /** 清偿金额与明细合计不一致。 */
    public static final String REASON_PAYMENT_AMOUNT_MISMATCH = "PAYMENT_AMOUNT_MISMATCH";
    /** 首轮清偿必须覆盖全部未付权益并足额。 */
    public static final String REASON_PAYMENT_NOT_FULL = "PAYMENT_NOT_FULL";
    /** 单笔付款超过该 claim 未付面值。 */
    public static final String REASON_PAYMENT_EXCEEDS_UNPAID = "PAYMENT_EXCEEDS_UNPAID";
    /** 减值累计超过未付面值。 */
    public static final String REASON_IMPAIRMENT_EXCEEDS_UNPAID = "IMPAIRMENT_EXCEEDS_UNPAID";
    /** 事件动作与其登记的动作不一致。 */
    private static final String ACTION_PROPOSE = "PROPOSE";
    private static final String ACTION_AUTHORIZE = "AUTHORIZE";
    private static final String ACTION_CANCEL = "CANCEL";
    private static final String ACTION_REGISTER = "REGISTER";
    private static final String ACTION_BOOK_EX = "BOOK_EX";
    private static final String ACTION_SETTLE = "SETTLE";
    private static final String ACTION_IMPAIR = "IMPAIR";

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private DividendModel() {
    }

    /**
     * 宿主事件信封：稳定事件标识、世界提交位置与模拟时点。
     *
     * @param eventId  运行内稳定事件标识，同时作为幂等键
     * @param position 世界提交位置，同一状态内必须严格递增
     * @param atMillis 模拟时点，不得早于前一事件
     */
    public record Event(String eventId, long position, long atMillis) {
    }

    /**
     * 分红提案的原始请求载体（<b>未经受理</b>）。
     *
     * <p>本记录不做任何校验：金额精度与时点组合由 {@link #propose} 统一返回业务拒绝，
     * 只有被受理后才会生成严格校验的 {@link Scheme}。</p>
     *
     * @param schemeId          方案标识
     * @param symbol            证券代码
     * @param issuerOwnerId     发行财产主体
     * @param companyAccountId  公司经营现金账户标识
     * @param perShare          每股股息金额，受理时规范到 2 位小数
     * @param recordAtMillis    登记截止模拟时点
     * @param exAtMillis        除息模拟时点
     * @param payAtMillis       付款模拟时点
     */
    public record SchemeRequest(String schemeId, String symbol, String issuerOwnerId,
                                String companyAccountId, BigDecimal perShare,
                                long recordAtMillis, long exAtMillis, long payAtMillis) {
    }

    /**
     * 已受理的分红方案。
     *
     * @param schemeId         方案标识
     * @param symbol           证券代码
     * @param issuerOwnerId    发行财产主体
     * @param companyAccountId 公司经营现金账户标识
     * @param perShare         每股股息，精确 2 位
     * @param recordAtMillis   登记截止模拟时点
     * @param exAtMillis       除息模拟时点
     * @param payAtMillis      付款模拟时点
     * @param phase            当前阶段
     * @param authorizationRef 宿主授权引用；{@code AUTHORIZED} 之后非空
     * @param cancelReason     撤销原因；仅 {@code CANCELLED} 时非空
     */
    public record Scheme(String schemeId, String symbol, String issuerOwnerId, String companyAccountId,
                         BigDecimal perShare, long recordAtMillis, long exAtMillis, long payAtMillis,
                         Phase phase, String authorizationRef, String cancelReason) {

        /** 校验已受理方案的金额、时点与阶段字段一致性。 */
        public Scheme {
            requireText(schemeId, "方案标识");
            requireText(symbol, "证券代码");
            requireText(issuerOwnerId, "发行财产主体");
            requireText(companyAccountId, "公司账户");
            perShare = money(perShare, "每股股息");
            if (perShare.signum() <= 0) {
                throw new IllegalArgumentException("每股股息必须为正：" + perShare);
            }
            if (recordAtMillis < 0 || exAtMillis < 0 || payAtMillis < 0) {
                throw new IllegalArgumentException("模拟时点不能为负：" + schemeId);
            }
            if (recordAtMillis >= exAtMillis || exAtMillis > payAtMillis) {
                throw new IllegalArgumentException("时点必须满足 recordAt < exAt <= payAt：" + schemeId);
            }
            Objects.requireNonNull(phase, "方案阶段");
            boolean authorized = phase == Phase.AUTHORIZED || phase == Phase.REGISTERED
                    || phase == Phase.EX_BOOKED || phase == Phase.SETTLED;
            if (authorized) {
                requireText(authorizationRef, "授权引用");
            } else if (phase == Phase.PROPOSED) {
                authorizationRef = null;
            } else if (authorizationRef != null && authorizationRef.isBlank()) {
                authorizationRef = null;
            }
            if (phase == Phase.CANCELLED) {
                requireText(cancelReason, "撤销原因");
            } else if (cancelReason != null && !cancelReason.isBlank()) {
                throw new IllegalArgumentException("非撤销方案不能携带撤销原因：" + schemeId);
            } else {
                cancelReason = null;
            }
        }
    }

    /**
     * 登记快照中的一条受益人权利。
     *
     * @param accountId 持股账户
     * @param quantity  登记股数，含冻结、锁定与当日买入不可卖批次
     * @param face      该账户的固定权利面值，精确 2 位
     */
    public record RightsEntry(String accountId, long quantity, BigDecimal face) {

        /** 校验账户、数量与面值。 */
        public RightsEntry {
            requireText(accountId, "登记账户");
            if (quantity <= 0) {
                throw new IllegalArgumentException("登记股数必须为正：" + accountId);
            }
            face = money(face, "权利面值");
            if (face.signum() <= 0) {
                throw new IllegalArgumentException("权利面值必须为正：" + accountId);
            }
        }
    }

    /**
     * 登记截止形成的不可变受益人快照；仅固定归属，不产生应付或应收。
     *
     * @param schemeId       方案标识
     * @param recordAtMillis 登记截止模拟时点
     * @param entries        受益人权利，按账户排序
     * @param totalQuantity  登记股数合计
     * @param totalAmount    固定权利面值合计
     */
    public record RightsSnapshot(String schemeId, long recordAtMillis, List<RightsEntry> entries,
                                 long totalQuantity, BigDecimal totalAmount) {

        /** 校验排序、唯一性、合计与面值精度。 */
        public RightsSnapshot {
            requireText(schemeId, "方案标识");
            if (recordAtMillis < 0) {
                throw new IllegalArgumentException("登记截止不能为负：" + schemeId);
            }
            List<RightsEntry> copy = new ArrayList<>(Objects.requireNonNull(entries, "权利明细"));
            copy.sort(Comparator.comparing(RightsEntry::accountId));
            Map<String, RightsEntry> byAccount = new LinkedHashMap<>();
            long quantity = 0L;
            BigDecimal amount = ZERO;
            for (RightsEntry entry : copy) {
                if (byAccount.putIfAbsent(entry.accountId(), entry) != null) {
                    throw new IllegalArgumentException("重复登记账户：" + entry.accountId());
                }
                quantity = Math.addExact(quantity, entry.quantity());
                amount = amount.add(entry.face());
            }
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("登记快照不能为空：" + schemeId);
            }
            totalAmount = money(amount, "权利面值合计");
            if (totalQuantity != quantity) {
                throw new IllegalArgumentException("登记股数合计与明细不一致：" + schemeId);
            }
            if (totalAmount.signum() <= 0) {
                throw new IllegalArgumentException("权利面值合计必须为正：" + schemeId);
            }
            entries = List.copyOf(copy);
        }
    }

    /**
     * 一名受益人的分红债权；同一记录派生公司应付与投资者净应收两个口径。
     *
     * @param claimId    无歧义派生标识
     * @param schemeId   所属方案
     * @param accountId  受益账户
     * @param symbol     证券代码
     * @param quantity   登记股数
     * @param face       合同面值，精确 2 位
     * @param paid       已由宿主现金证据证实的实收金额
     * @param impairment 投资者侧减值估值，不改变合同面值与公司应付
     */
    public record Claim(String claimId, String schemeId, String accountId, String symbol,
                        long quantity, BigDecimal face, BigDecimal paid, BigDecimal impairment) {

        /** 校验标识、金额与累计上限。 */
        public Claim {
            requireText(claimId, "债权标识");
            requireText(schemeId, "方案标识");
            requireText(accountId, "受益账户");
            requireText(symbol, "证券代码");
            if (quantity <= 0) {
                throw new IllegalArgumentException("债权股数必须为正：" + claimId);
            }
            face = money(face, "合同面值");
            paid = money(paid, "已付金额");
            impairment = money(impairment, "减值金额");
            if (face.signum() <= 0) {
                throw new IllegalArgumentException("合同面值必须为正：" + claimId);
            }
            if (paid.signum() < 0 || paid.compareTo(face) > 0) {
                throw new IllegalArgumentException("已付金额必须在 0 与面值之间：" + claimId);
            }
            if (impairment.signum() < 0 || impairment.compareTo(face.subtract(paid)) > 0) {
                throw new IllegalArgumentException("减值不得超过未付面值：" + claimId);
            }
        }
    }

    /**
     * 一次整批清偿记录；金额与明细来自宿主已完成的现金候选。
     *
     * @param settlementId        原事件标识
     * @param position            世界提交位置
     * @param schemeId            方案标识
     * @param atMillis            清偿模拟时点
     * @param cashEvidenceId      宿主现金证据标识，非空
     * @param amount              本次实际转出总额
     * @param perClaim            每条 claim 的实付金额
     * @param impairmentReleased  因真实回款而转回的减值金额
     */
    public record Payment(String settlementId, long position, String schemeId, long atMillis,
                          String cashEvidenceId, BigDecimal amount,
                          Map<String, BigDecimal> perClaim,
                          Map<String, BigDecimal> impairmentReleased) {

        /** 校验金额合计、明细与证据。 */
        public Payment {
            requireText(settlementId, "清偿事件标识");
            requireText(schemeId, "方案标识");
            requireText(cashEvidenceId, "现金证据");
            if (position <= 0) {
                throw new IllegalArgumentException("清偿位置必须为正：" + settlementId);
            }
            if (atMillis < 0) {
                throw new IllegalArgumentException("清偿时点不能为负：" + settlementId);
            }
            amount = money(amount, "清偿金额");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("清偿金额必须为正：" + settlementId);
            }
            Map<String, BigDecimal> claimCopy = new TreeMap<>(
                    Objects.requireNonNull(perClaim, "清偿明细"));
            Map<String, BigDecimal> releaseCopy = new TreeMap<>(
                    Objects.requireNonNull(impairmentReleased, "减值转回明细"));
            BigDecimal sum = ZERO;
            for (Map.Entry<String, BigDecimal> entry : claimCopy.entrySet()) {
                requireText(entry.getKey(), "清偿明细键");
                BigDecimal value = money(entry.getValue(), "清偿明细金额");
                if (value.signum() <= 0) {
                    throw new IllegalArgumentException("清偿明细金额必须为正：" + entry.getKey());
                }
                entry.setValue(value);
                sum = sum.add(value);
            }
            if (claimCopy.isEmpty()) {
                throw new IllegalArgumentException("清偿明细不能为空：" + settlementId);
            }
            if (sum.compareTo(amount) != 0) {
                throw new IllegalArgumentException("清偿金额与明细合计不一致：" + settlementId);
            }
            for (Map.Entry<String, BigDecimal> entry : releaseCopy.entrySet()) {
                if (!claimCopy.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("转回明细引用了未清偿债权：" + entry.getKey());
                }
                BigDecimal released = money(entry.getValue(), "减值转回金额");
                if (released.signum() < 0) {
                    throw new IllegalArgumentException("减值转回不能为负：" + entry.getKey());
                }
                entry.setValue(released);
            }
            perClaim = Collections.unmodifiableMap(claimCopy);
            impairmentReleased = Collections.unmodifiableMap(releaseCopy);
        }
    }

    /**
     * 一次投资者侧减值；带明确证据，只改账面净应收。
     *
     * @param impairmentId 原事件标识
     * @param position     世界提交位置
     * @param claimId      债权标识
     * @param atMillis     减值模拟时点
     * @param amount       本次新增减值金额
     * @param evidenceRef  减值证据标识
     */
    public record Impairment(String impairmentId, long position, String claimId, long atMillis,
                             BigDecimal amount, String evidenceRef) {

        /** 校验金额、位置与证据。 */
        public Impairment {
            requireText(impairmentId, "减值事件标识");
            requireText(claimId, "债权标识");
            requireText(evidenceRef, "减值证据");
            if (position <= 0) {
                throw new IllegalArgumentException("减值位置必须为正：" + impairmentId);
            }
            if (atMillis < 0) {
                throw new IllegalArgumentException("减值时点不能为负：" + impairmentId);
            }
            amount = money(amount, "减值金额");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("减值金额必须为正：" + impairmentId);
            }
        }
    }

    /**
     * 已接受事件的回报记录；用于恢复后重放原请求并返回原回报。
     *
     * @param eventId     事件标识
     * @param position    世界提交位置
     * @param atMillis    模拟时点
     * @param action      动作
     * @param reason      原回报原因
     * @param requestJson 规范化后的完整原始请求
     */
    public record Receipt(String eventId, long position, long atMillis, String action,
                          String reason, String requestJson) {

        /** 校验字段非空。 */
        public Receipt {
            requireText(eventId, "事件标识");
            requireText(action, "事件动作");
            requireText(reason, "回报原因");
            requireText(requestJson, "原始请求");
            if (position <= 0) {
                throw new IllegalArgumentException("事件位置必须为正：" + eventId);
            }
            if (atMillis < 0) {
                throw new IllegalArgumentException("事件时点不能为负：" + eventId);
            }
        }
    }

    /**
     * 不可变分红子集状态；构造期完成引用、阶段、金额、历史与证据的完整校验。
     *
     * @param schemes    方案，按标识索引
     * @param snapshots  登记快照，按方案标识索引
     * @param claims     债权，按无歧义标识索引
     * @param payments   清偿历史，按位置排序
     * @param impairments 减值历史，按位置排序
     * @param receipts   已接受事件回报，按标识索引
     * @param lastPosition 最后已接受事件位置
     * @param lastAtMillis 最后已接受事件时点
     */
    public record State(Map<String, Scheme> schemes, Map<String, RightsSnapshot> snapshots,
                        Map<String, Claim> claims, List<Payment> payments,
                        List<Impairment> impairments, Map<String, Receipt> receipts,
                        long lastPosition, long lastAtMillis) {

        /** 规范化集合并执行全量一致性校验。 */
        public State {
            Map<String, Scheme> schemeCopy = new TreeMap<>(
                    Objects.requireNonNull(schemes, "方案表"));
            Map<String, RightsSnapshot> snapshotCopy = new TreeMap<>(
                    Objects.requireNonNull(snapshots, "快照表"));
            Map<String, Claim> claimCopy = new TreeMap<>(Objects.requireNonNull(claims, "债权表"));
            List<Payment> paymentList = new ArrayList<>(Objects.requireNonNull(payments, "清偿历史"));
            paymentList.sort(Comparator.comparingLong(Payment::position));
            List<Impairment> impairmentList =
                    new ArrayList<>(Objects.requireNonNull(impairments, "减值历史"));
            impairmentList.sort(Comparator.comparingLong(Impairment::position));
            Map<String, Receipt> receiptCopy = new TreeMap<>(
                    Objects.requireNonNull(receipts, "事件回报"));
            checkState(schemeCopy, snapshotCopy, claimCopy, paymentList, impairmentList,
                    receiptCopy, lastPosition, lastAtMillis);
            schemes = Collections.unmodifiableMap(schemeCopy);
            snapshots = Collections.unmodifiableMap(snapshotCopy);
            claims = Collections.unmodifiableMap(claimCopy);
            payments = List.copyOf(paymentList);
            impairments = List.copyOf(impairmentList);
            receipts = Collections.unmodifiableMap(receiptCopy);
        }
    }

    /**
     * 动作结果；业务拒绝时携带完整旧状态。
     *
     * @param accepted 是否接受
     * @param reason   结果原因
     * @param eventId  事件标识
     * @param position 世界位置；重放时为原位置
     * @param state    结果状态
     */
    public record Result(boolean accepted, String reason, String eventId, long position, State state) {
    }

    /**
     * 空状态。
     *
     * @return 无方案、无历史的状态
     */
    public static State empty() {
        return new State(Map.of(), Map.of(), Map.of(), List.of(), List.of(), Map.of(), 0L, 0L);
    }

    /**
     * 受理分红提案；金额精度与时点组合在此统一拒绝。
     *
     * @param state   当前状态
     * @param event   宿主事件
     * @param request 原始提案请求
     * @return 受理结果；拒绝时保留旧状态
     */
    public static Result propose(State state, Event event, SchemeRequest request) {
        requireState(state);
        requireEvent(event);
        String json = json(request);
        Result replay = replay(state, event, ACTION_PROPOSE, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        if (request == null) {
            return reject(state, event, REASON_INVALID_ARGUMENT);
        }
        if (blank(request.schemeId()) || blank(request.symbol())
                || blank(request.issuerOwnerId()) || blank(request.companyAccountId())) {
            return reject(state, event, REASON_INVALID_ARGUMENT);
        }
        if (state.schemes().containsKey(request.schemeId())) {
            return reject(state, event, REASON_SCHEME_EXISTS);
        }
        BigDecimal perShare;
        try {
            perShare = money(request.perShare(), "每股股息");
        } catch (IllegalArgumentException failure) {
            return reject(state, event, REASON_PER_SHARE_PRECISION);
        }
        if (perShare.signum() <= 0) {
            return reject(state, event, REASON_INVALID_AMOUNT);
        }
        if (request.recordAtMillis() < 0 || request.exAtMillis() < 0 || request.payAtMillis() < 0
                || request.recordAtMillis() >= request.exAtMillis()
                || request.exAtMillis() > request.payAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        if (event.atMillis() > request.recordAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        Scheme scheme = new Scheme(request.schemeId(), request.symbol(), request.issuerOwnerId(),
                request.companyAccountId(), perShare, request.recordAtMillis(),
                request.exAtMillis(), request.payAtMillis(), Phase.PROPOSED, null, null);
        Map<String, Scheme> schemes = new TreeMap<>(state.schemes());
        schemes.put(scheme.schemeId(), scheme);
        return accept(state, event, ACTION_PROPOSE, json, schemes, state.snapshots(),
                state.claims(), state.payments(), state.impairments());
    }

    /**
     * 记录宿主授权引用；不验证董事会真实性与表决内容。
     *
     * @param state            当前状态
     * @param event            宿主事件
     * @param schemeId         方案标识
     * @param authorizationRef 宿主授权引用
     * @return 结果；拒绝时保留旧状态
     */
    public static Result authorize(State state, Event event, String schemeId, String authorizationRef) {
        requireState(state);
        requireEvent(event);
        String json = json(new AuthorizeRequest(schemeId, authorizationRef));
        Result replay = replay(state, event, ACTION_AUTHORIZE, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        Scheme scheme = schemeOf(state, schemeId);
        if (scheme == null) {
            return reject(state, event, REASON_UNKNOWN_SCHEME);
        }
        if (scheme.phase() != Phase.PROPOSED) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        if (blank(authorizationRef)) {
            return reject(state, event, REASON_INVALID_ARGUMENT);
        }
        if (event.atMillis() > scheme.recordAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        Scheme updated = new Scheme(scheme.schemeId(), scheme.symbol(), scheme.issuerOwnerId(),
                scheme.companyAccountId(), scheme.perShare(), scheme.recordAtMillis(),
                scheme.exAtMillis(), scheme.payAtMillis(), Phase.AUTHORIZED, authorizationRef, null);
        Map<String, Scheme> schemes = new TreeMap<>(state.schemes());
        schemes.put(schemeId, updated);
        return accept(state, event, ACTION_AUTHORIZE, json, schemes, state.snapshots(),
                state.claims(), state.payments(), state.impairments());
    }

    /**
     * 登记前撤销方案；登记后只能走新方案标识。
     *
     * @param state    当前状态
     * @param event    宿主事件
     * @param schemeId 方案标识
     * @param reason   撤销原因
     * @return 结果；拒绝时保留旧状态
     */
    public static Result cancel(State state, Event event, String schemeId, String reason) {
        requireState(state);
        requireEvent(event);
        String json = json(new CancelRequest(schemeId, reason));
        Result replay = replay(state, event, ACTION_CANCEL, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        Scheme scheme = schemeOf(state, schemeId);
        if (scheme == null) {
            return reject(state, event, REASON_UNKNOWN_SCHEME);
        }
        if (scheme.phase() != Phase.PROPOSED && scheme.phase() != Phase.AUTHORIZED) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        if (blank(reason)) {
            return reject(state, event, REASON_INVALID_ARGUMENT);
        }
        if (event.atMillis() > scheme.recordAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        Scheme updated = new Scheme(scheme.schemeId(), scheme.symbol(), scheme.issuerOwnerId(),
                scheme.companyAccountId(), scheme.perShare(), scheme.recordAtMillis(),
                scheme.exAtMillis(), scheme.payAtMillis(), Phase.CANCELLED,
                scheme.authorizationRef(), reason);
        Map<String, Scheme> schemes = new TreeMap<>(state.schemes());
        schemes.put(schemeId, updated);
        return accept(state, event, ACTION_CANCEL, json, schemes, state.snapshots(),
                state.claims(), state.payments(), state.impairments());
    }

    /**
     * 在登记截止按当时实际剩余库存固定受益人；只固定归属，不产生应付或应收。
     *
     * <p>注入的证券账是受信世界值，不写入事件回报；同一事件重放时先返回原回报，
     * 后来注入的账不能改变既有权利。计入 {@code FLOAT}、{@code LOCKED}、被卖单冻结与当日买入
     * 尚不可卖的批次，排除 {@code TREASURY}。</p>
     *
     * @param state      当前状态
     * @param event      宿主事件；模拟时点必须恰为登记截止
     * @param schemeId   方案标识
     * @param securities 当前证券账
     * @return 结果；拒绝时保留旧状态
     */
    public static Result register(State state, Event event, String schemeId,
                                  SecuritiesLedger.State securities) {
        requireState(state);
        requireEvent(event);
        String json = json(new RegisterRequest(schemeId));
        Result replay = replay(state, event, ACTION_REGISTER, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        Scheme scheme = schemeOf(state, schemeId);
        if (scheme == null) {
            return reject(state, event, REASON_UNKNOWN_SCHEME);
        }
        if (scheme.phase() != Phase.AUTHORIZED) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        if (event.atMillis() != scheme.recordAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        Objects.requireNonNull(securities, "证券账");
        Map<String, Long> quantities = new TreeMap<>();
        long total = 0L;
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            if (!lot.symbol().equals(scheme.symbol())
                    || lot.classification() == OpeningRegistry.ShareClass.TREASURY) {
                continue;
            }
            quantities.merge(lot.accountId(), lot.remaining(), Math::addExact);
            try {
                total = Math.addExact(total, lot.remaining());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("登记股数合计溢出：" + schemeId, overflow);
            }
        }
        if (total == 0L) {
            return reject(state, event, REASON_NO_HOLDERS);
        }
        List<RightsEntry> entries = new ArrayList<>();
        BigDecimal totalAmount = ZERO;
        for (Map.Entry<String, Long> entry : quantities.entrySet()) {
            BigDecimal face = scheme.perShare().multiply(BigDecimal.valueOf(entry.getValue()));
            entries.add(new RightsEntry(entry.getKey(), entry.getValue(), face));
            totalAmount = totalAmount.add(face);
        }
        RightsSnapshot snapshot = new RightsSnapshot(schemeId, scheme.recordAtMillis(),
                entries, total, totalAmount);
        Map<String, Scheme> schemes = new TreeMap<>(state.schemes());
        schemes.put(schemeId, new Scheme(scheme.schemeId(), scheme.symbol(), scheme.issuerOwnerId(),
                scheme.companyAccountId(), scheme.perShare(), scheme.recordAtMillis(),
                scheme.exAtMillis(), scheme.payAtMillis(), Phase.REGISTERED,
                scheme.authorizationRef(), null));
        Map<String, RightsSnapshot> snapshots = new TreeMap<>(state.snapshots());
        snapshots.put(schemeId, snapshot);
        return accept(state, event, ACTION_REGISTER, json, schemes, snapshots,
                state.claims(), state.payments(), state.impairments());
    }

    /**
     * 除息批：按登记快照一次性生成全部债权，此后公司应付才等于全部合同未付额。
     *
     * @param state    当前状态
     * @param event    宿主事件；模拟时点必须恰为除息截止
     * @param schemeId 方案标识
     * @return 结果；拒绝时保留旧状态
     */
    public static Result bookEx(State state, Event event, String schemeId) {
        requireState(state);
        requireEvent(event);
        String json = json(new BookExRequest(schemeId));
        Result replay = replay(state, event, ACTION_BOOK_EX, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        Scheme scheme = schemeOf(state, schemeId);
        if (scheme == null) {
            return reject(state, event, REASON_UNKNOWN_SCHEME);
        }
        if (scheme.phase() != Phase.REGISTERED) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        if (event.atMillis() != scheme.exAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        RightsSnapshot snapshot = state.snapshots().get(schemeId);
        if (snapshot == null) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        Map<String, Claim> claims = new TreeMap<>(state.claims());
        for (RightsEntry entry : snapshot.entries()) {
            String claimId = claimId(schemeId, entry.accountId());
            claims.put(claimId, new Claim(claimId, schemeId, entry.accountId(), scheme.symbol(),
                    entry.quantity(), entry.face(), ZERO, ZERO));
        }
        Map<String, Scheme> schemes = new TreeMap<>(state.schemes());
        schemes.put(schemeId, new Scheme(scheme.schemeId(), scheme.symbol(), scheme.issuerOwnerId(),
                scheme.companyAccountId(), scheme.perShare(), scheme.recordAtMillis(),
                scheme.exAtMillis(), scheme.payAtMillis(), Phase.EX_BOOKED,
                scheme.authorizationRef(), null));
        return accept(state, event, ACTION_BOOK_EX, json, schemes, state.snapshots(),
                claims, state.payments(), state.impairments());
    }

    /**
     * 整批全额清偿：只登记宿主已经完成的现金事实，不产生资金。
     *
     * <p>明细必须覆盖该方案全部未付债权且每条足额，金额与明细合计一致；真实回款同时清除
     * 对应减值并在清偿记录保留转回额。</p>
     *
     * @param state          当前状态
     * @param event          宿主事件
     * @param schemeId       方案标识
     * @param cashEvidenceId 宿主现金证据标识
     * @param amount         实际转出总额
     * @param perClaim       每条债权的实付金额
     * @return 结果；拒绝时保留旧状态
     */
    public static Result settle(State state, Event event, String schemeId, String cashEvidenceId,
                                BigDecimal amount, Map<String, BigDecimal> perClaim) {
        requireState(state);
        requireEvent(event);
        String json = json(new SettleRequest(schemeId, cashEvidenceId, amount, perClaim));
        Result replay = replay(state, event, ACTION_SETTLE, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        Scheme scheme = schemeOf(state, schemeId);
        if (scheme == null) {
            return reject(state, event, REASON_UNKNOWN_SCHEME);
        }
        if (scheme.phase() != Phase.EX_BOOKED) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        if (event.atMillis() < scheme.payAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        if (blank(cashEvidenceId) || amount == null || perClaim == null) {
            return reject(state, event, REASON_INVALID_ARGUMENT);
        }
        BigDecimal paidTotal;
        try {
            paidTotal = money(amount, "清偿金额");
        } catch (IllegalArgumentException failure) {
            return reject(state, event, REASON_INVALID_AMOUNT);
        }
        if (paidTotal.signum() <= 0) {
            return reject(state, event, REASON_INVALID_AMOUNT);
        }
        List<Claim> schemeClaims = claimsOf(state, schemeId);
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        BigDecimal sum = ZERO;
        for (Claim claim : schemeClaims) {
            BigDecimal value = perClaim.get(claim.claimId());
            if (value == null) {
                return reject(state, event, REASON_PAYMENT_NOT_FULL);
            }
            BigDecimal paid;
            try {
                paid = money(value, "清偿明细");
            } catch (IllegalArgumentException failure) {
                return reject(state, event, REASON_INVALID_AMOUNT);
            }
            if (paid.signum() <= 0) {
                return reject(state, event, REASON_INVALID_AMOUNT);
            }
            if (paid.compareTo(faceUnpaid(claim)) > 0) {
                return reject(state, event, REASON_PAYMENT_EXCEEDS_UNPAID);
            }
            if (paid.compareTo(faceUnpaid(claim)) != 0) {
                return reject(state, event, REASON_PAYMENT_NOT_FULL);
            }
            normalized.put(claim.claimId(), paid);
            sum = sum.add(paid);
        }
        if (normalized.size() != perClaim.size()) {
            return reject(state, event, REASON_UNKNOWN_CLAIM);
        }
        if (sum.compareTo(paidTotal) != 0) {
            return reject(state, event, REASON_PAYMENT_AMOUNT_MISMATCH);
        }
        Map<String, Claim> claims = new TreeMap<>(state.claims());
        Map<String, BigDecimal> released = new TreeMap<>();
        for (Claim claim : schemeClaims) {
            released.put(claim.claimId(), claim.impairment());
            claims.put(claim.claimId(), new Claim(claim.claimId(), claim.schemeId(),
                    claim.accountId(), claim.symbol(), claim.quantity(), claim.face(),
                    claim.face(), ZERO));
        }
        Payment payment = new Payment(event.eventId(), event.position(), schemeId, event.atMillis(),
                cashEvidenceId, paidTotal, normalized, released);
        List<Payment> payments = new ArrayList<>(state.payments());
        payments.add(payment);
        Map<String, Scheme> schemes = new TreeMap<>(state.schemes());
        schemes.put(schemeId, new Scheme(scheme.schemeId(), scheme.symbol(), scheme.issuerOwnerId(),
                scheme.companyAccountId(), scheme.perShare(), scheme.recordAtMillis(),
                scheme.exAtMillis(), scheme.payAtMillis(), Phase.SETTLED,
                scheme.authorizationRef(), null));
        return accept(state, event, ACTION_SETTLE, json, schemes, state.snapshots(),
                claims, payments, state.impairments());
    }

    /**
     * 登记投资者侧减值；只改账面净应收，不改变合同面值、已付或公司未偿债务。
     *
     * @param state       当前状态
     * @param event       宿主事件
     * @param claimId     债权标识
     * @param amount      本次新增减值金额
     * @param evidenceRef 减值证据标识
     * @return 结果；拒绝时保留旧状态
     */
    public static Result impair(State state, Event event, String claimId, BigDecimal amount,
                                String evidenceRef) {
        requireState(state);
        requireEvent(event);
        String json = json(new ImpairRequest(claimId, amount, evidenceRef));
        Result replay = replay(state, event, ACTION_IMPAIR, json);
        if (replay != null) {
            return replay;
        }
        String guard = guard(state, event);
        if (guard != null) {
            return reject(state, event, guard);
        }
        Claim claim = state.claims().get(claimId);
        if (claim == null) {
            return reject(state, event, REASON_UNKNOWN_CLAIM);
        }
        Scheme scheme = schemeOf(state, claim.schemeId());
        if (scheme == null || scheme.phase() != Phase.EX_BOOKED) {
            return reject(state, event, REASON_INVALID_PHASE);
        }
        if (event.atMillis() < scheme.exAtMillis()) {
            return reject(state, event, REASON_INVALID_TIMELINE);
        }
        if (blank(evidenceRef)) {
            return reject(state, event, REASON_INVALID_ARGUMENT);
        }
        BigDecimal value;
        try {
            value = money(amount, "减值金额");
        } catch (IllegalArgumentException failure) {
            return reject(state, event, REASON_INVALID_AMOUNT);
        }
        if (value.signum() <= 0) {
            return reject(state, event, REASON_INVALID_AMOUNT);
        }
        if (claim.impairment().add(value).compareTo(faceUnpaid(claim)) > 0) {
            return reject(state, event, REASON_IMPAIRMENT_EXCEEDS_UNPAID);
        }
        Map<String, Claim> claims = new TreeMap<>(state.claims());
        claims.put(claimId, new Claim(claim.claimId(), claim.schemeId(), claim.accountId(),
                claim.symbol(), claim.quantity(), claim.face(), claim.paid(),
                claim.impairment().add(value)));
        Impairment impairment = new Impairment(event.eventId(), event.position(), claimId,
                event.atMillis(), value, evidenceRef);
        List<Impairment> impairments = new ArrayList<>(state.impairments());
        impairments.add(impairment);
        return accept(state, event, ACTION_IMPAIR, json, state.schemes(), state.snapshots(),
                claims, state.payments(), impairments);
    }

    // ------------------------------------------------------------------
    // 公开查询
    // ------------------------------------------------------------------

    /**
     * 读取方案。
     *
     * @param state    状态
     * @param schemeId 方案标识
     * @return 方案；不存在返回 null
     */
    public static Scheme scheme(State state, String schemeId) {
        requireState(state);
        return schemeOf(state, schemeId);
    }

    /**
     * 读取登记快照。
     *
     * @param state    状态
     * @param schemeId 方案标识
     * @return 快照；未登记返回 null
     */
    public static RightsSnapshot snapshot(State state, String schemeId) {
        requireState(state);
        return state.snapshots().get(schemeId);
    }

    /**
     * 读取某方案的全部债权，按标识排序。
     *
     * @param state    状态
     * @param schemeId 方案标识
     * @return 债权列表；未确认时为空
     */
    public static List<Claim> claims(State state, String schemeId) {
        requireState(state);
        return List.copyOf(claimsOf(state, schemeId));
    }

    /**
     * 读取单条债权。
     *
     * @param state   状态
     * @param claimId 债权标识
     * @return 债权；不存在返回 null
     */
    public static Claim claim(State state, String claimId) {
        requireState(state);
        return state.claims().get(claimId);
    }

    /**
     * 已固定的权利面值合计；登记后恒定，不因付款变化。
     *
     * @param state    状态
     * @param schemeId 方案标识
     * @return 面值合计；未登记返回 0.00
     */
    public static BigDecimal declaredTotal(State state, String schemeId) {
        requireState(state);
        RightsSnapshot snapshot = state.snapshots().get(schemeId);
        return snapshot == null ? ZERO : snapshot.totalAmount();
    }

    /**
     * 公司应付：已确认的合同未付额合计；登记阶段仍为 0.00。
     *
     * @param state    状态
     * @param schemeId 方案标识
     * @return 应付金额，精确 2 位
     */
    public static BigDecimal companyPayable(State state, String schemeId) {
        requireState(state);
        BigDecimal total = ZERO;
        for (Claim claim : claimsOf(state, schemeId)) {
            total = total.add(faceUnpaid(claim));
        }
        return total;
    }

    /**
     * 合同未付面值 = 面值 − 已付；不受投资者减值影响。
     *
     * @param claim 债权
     * @return 未付金额
     */
    public static BigDecimal faceUnpaid(Claim claim) {
        Objects.requireNonNull(claim, "债权");
        return claim.face().subtract(claim.paid());
    }

    /**
     * 投资者账面净应收 = 未付面值 − 投资者侧减值。
     *
     * @param claim 债权
     * @return 净应收金额
     */
    public static BigDecimal holderNetReceivable(Claim claim) {
        Objects.requireNonNull(claim, "债权");
        return faceUnpaid(claim).subtract(claim.impairment());
    }

    /**
     * 序列化为可复现 JSON；集合已排序，映射按键排序。
     *
     * @param state 状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("分红状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原状态；构造期校验仍生效，拒绝伪造已付或篡改权益。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException 内容不合法
     */
    public static State fromJson(String json) {
        if (blank(json)) {
            throw new IllegalArgumentException("分红状态 JSON 不能为空");
        }
        try {
            State state = MAPPER.readValue(json, State.class);
            if (state == null) {
                throw new IllegalArgumentException("分红状态不能为空");
            }
            return state;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("分红状态 JSON 不合法", failure);
        }
    }

    // ------------------------------------------------------------------
    // 内部：事件处理
    // ------------------------------------------------------------------

    private record AuthorizeRequest(String schemeId, String authorizationRef) {
    }

    private record CancelRequest(String schemeId, String reason) {
    }

    private record RegisterRequest(String schemeId) {
    }

    private record BookExRequest(String schemeId) {
    }

    private record SettleRequest(String schemeId, String cashEvidenceId, BigDecimal amount,
                                 Map<String, BigDecimal> perClaim) {
    }

    private record ImpairRequest(String claimId, BigDecimal amount, String evidenceRef) {
    }

    private static Result replay(State state, Event event, String action, String requestJson) {
        Receipt receipt = state.receipts().get(event.eventId());
        if (receipt == null) {
            return null;
        }
        if (receipt.position() != event.position() || receipt.atMillis() != event.atMillis()
                || !receipt.action().equals(action) || !receipt.requestJson().equals(requestJson)) {
            return new Result(false, REASON_EVENT_ID_CONFLICT, event.eventId(), event.position(), state);
        }
        return new Result(true, receipt.reason(), event.eventId(), receipt.position(), state);
    }

    private static String guard(State state, Event event) {
        if (event.position() <= state.lastPosition()) {
            return REASON_POSITION_NOT_INCREASING;
        }
        if (event.atMillis() < state.lastAtMillis()) {
            return REASON_TIME_BACKWARDS;
        }
        return null;
    }

    private static Result accept(State base, Event event, String action, String requestJson,
                                 Map<String, Scheme> schemes, Map<String, RightsSnapshot> snapshots,
                                 Map<String, Claim> claims, List<Payment> payments,
                                 List<Impairment> impairments) {
        Map<String, Receipt> receipts = new TreeMap<>(base.receipts());
        receipts.put(event.eventId(), new Receipt(event.eventId(), event.position(),
                event.atMillis(), action, REASON_OK, requestJson));
        State next = new State(schemes, snapshots, claims, payments, impairments, receipts,
                event.position(), event.atMillis());
        return new Result(true, REASON_OK, event.eventId(), event.position(), next);
    }

    private static Result reject(State state, Event event, String reason) {
        return new Result(false, reason, event.eventId(), event.position(), state);
    }

    private static Scheme schemeOf(State state, String schemeId) {
        if (blank(schemeId)) {
            return null;
        }
        return state.schemes().get(schemeId);
    }

    private static List<Claim> claimsOf(State state, String schemeId) {
        return claimsOf(state.claims(), schemeId);
    }

    private static List<Claim> claimsOf(Map<String, Claim> claims, String schemeId) {
        List<Claim> result = new ArrayList<>();
        if (blank(schemeId)) {
            return result;
        }
        for (Claim claim : claims.values()) {
            if (claim.schemeId().equals(schemeId)) {
                result.add(claim);
            }
        }
        result.sort(Comparator.comparing(Claim::claimId));
        return result;
    }

    // ------------------------------------------------------------------
    // 内部：状态校验
    // ------------------------------------------------------------------

    private static void checkState(Map<String, Scheme> schemes, Map<String, RightsSnapshot> snapshots,
                                   Map<String, Claim> claims, List<Payment> payments,
                                   List<Impairment> impairments, Map<String, Receipt> receipts,
                                   long lastPosition, long lastAtMillis) {
        List<Receipt> ordered = new ArrayList<>(receipts.values());
        ordered.sort(Comparator.comparingLong(Receipt::position));
        long previousPosition = 0L;
        long previousAt = 0L;
        for (Map.Entry<String, Receipt> entry : receipts.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().eventId())) {
                throw new IllegalArgumentException("事件回报键与标识不一致：" + entry.getKey());
            }
        }
        for (Receipt receipt : ordered) {
            if (!REASON_OK.equals(receipt.reason())) {
                throw new IllegalArgumentException("已接受事件的回报原因不合法：" + receipt.eventId());
            }
            if (receipt.position() <= previousPosition) {
                throw new IllegalArgumentException("事件位置必须严格递增：" + receipt.eventId());
            }
            if (receipt.atMillis() < previousAt) {
                throw new IllegalArgumentException("事件时点不得倒退：" + receipt.eventId());
            }
            previousPosition = receipt.position();
            previousAt = receipt.atMillis();
        }
        if (lastPosition != previousPosition || lastAtMillis != previousAt) {
            throw new IllegalArgumentException("最后位置或时点与事件回报不一致");
        }

        for (Map.Entry<String, Scheme> entry : schemes.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().schemeId())) {
                throw new IllegalArgumentException("方案索引与标识不一致：" + entry.getKey());
            }
        }
        for (Map.Entry<String, RightsSnapshot> entry : snapshots.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().schemeId())) {
                throw new IllegalArgumentException("快照索引与标识不一致：" + entry.getKey());
            }
            if (!schemes.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("登记快照引用未知方案：" + entry.getKey());
            }
        }
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().claimId())) {
                throw new IllegalArgumentException("债权索引与标识不一致：" + entry.getKey());
            }
        }

        for (Scheme scheme : schemes.values()) {
            RightsSnapshot snapshot = snapshots.get(scheme.schemeId());
            int rank = rank(scheme.phase());
            boolean registered = rank >= rank(Phase.REGISTERED);
            if (registered != (snapshot != null)) {
                throw new IllegalArgumentException("方案阶段与登记快照不一致：" + scheme.schemeId());
            }
            if (snapshot != null && snapshot.recordAtMillis() != scheme.recordAtMillis()) {
                throw new IllegalArgumentException("快照登记截止与方案不一致：" + scheme.schemeId());
            }
            if (snapshot != null) {
                for (RightsEntry entry : snapshot.entries()) {
                    if (entry.face().compareTo(scheme.perShare().multiply(BigDecimal.valueOf(entry.quantity()))) != 0) {
                        throw new IllegalArgumentException("登记面值与每股金额乘股数不一致：" + scheme.schemeId());
                    }
                }
            }
            List<Claim> schemeClaims = claimsOf(claims, scheme.schemeId());
            boolean booked = rank >= rank(Phase.EX_BOOKED);
            if (booked && schemeClaims.isEmpty()) {
                throw new IllegalArgumentException("已确认方案缺少债权：" + scheme.schemeId());
            }
            if (!booked && !schemeClaims.isEmpty()) {
                throw new IllegalArgumentException("未确认方案不得携带债权：" + scheme.schemeId());
            }
            if (booked) {
                checkClaimsAgainstSnapshot(scheme, snapshot, schemeClaims);
            }
            if (scheme.phase() == Phase.SETTLED) {
                for (Claim claim : schemeClaims) {
                    if (claim.paid().compareTo(claim.face()) != 0 || claim.impairment().signum() != 0) {
                        throw new IllegalArgumentException("已清偿方案仍存在未结债权：" + claim.claimId());
                    }
                }
            }
        }
        long expectedClaims = 0L;
        for (Scheme scheme : schemes.values()) {
            if (rank(scheme.phase()) >= rank(Phase.EX_BOOKED)) {
                RightsSnapshot snapshot = snapshots.get(scheme.schemeId());
                expectedClaims = Math.addExact(expectedClaims, snapshot.entries().size());
            }
        }
        if (claims.size() != expectedClaims) {
            throw new IllegalArgumentException("债权数量与登记快照不一致");
        }

        checkHistory(claims, payments, impairments, receipts);
        checkReceipts(schemes, claims, receipts);
    }

    private static void checkClaimsAgainstSnapshot(Scheme scheme, RightsSnapshot snapshot,
                                                   List<Claim> schemeClaims) {
        if (snapshot == null) {
            throw new IllegalArgumentException("缺少登记快照：" + scheme.schemeId());
        }
        Map<String, RightsEntry> entries = new LinkedHashMap<>();
        for (RightsEntry entry : snapshot.entries()) {
            entries.put(entry.accountId(), entry);
        }
        if (entries.size() != schemeClaims.size()) {
            throw new IllegalArgumentException("债权与登记账户数量不一致：" + scheme.schemeId());
        }
        BigDecimal face = ZERO;
        for (Claim claim : schemeClaims) {
            RightsEntry entry = entries.get(claim.accountId());
            if (entry == null) {
                throw new IllegalArgumentException("债权缺少登记账户：" + claim.claimId());
            }
            if (!claim.claimId().equals(claimId(claim.schemeId(), claim.accountId()))) {
                throw new IllegalArgumentException("债权标识不是规范派生：" + claim.claimId());
            }
            if (!claim.symbol().equals(scheme.symbol())) {
                throw new IllegalArgumentException("债权证券与方案不一致：" + claim.claimId());
            }
            if (claim.quantity() != entry.quantity()) {
                throw new IllegalArgumentException("债权股数与登记不一致：" + claim.claimId());
            }
            if (claim.face().compareTo(entry.face()) != 0) {
                throw new IllegalArgumentException("债权面值与登记不一致：" + claim.claimId());
            }
            BigDecimal expected = scheme.perShare().multiply(BigDecimal.valueOf(entry.quantity()));
            if (claim.face().compareTo(expected) != 0) {
                throw new IllegalArgumentException("面值与每股金额乘股数不一致：" + claim.claimId());
            }
            face = face.add(claim.face());
        }
        if (face.compareTo(snapshot.totalAmount()) != 0) {
            throw new IllegalArgumentException("债权面值合计与快照不一致：" + scheme.schemeId());
        }
    }

    private static void checkHistory(Map<String, Claim> claims, List<Payment> payments,
                                     List<Impairment> impairments, Map<String, Receipt> receipts) {
        Map<String, BigDecimal> paid = new TreeMap<>();
        Map<String, BigDecimal> impairment = new TreeMap<>();
        for (Claim claim : claims.values()) {
            paid.put(claim.claimId(), ZERO);
            impairment.put(claim.claimId(), ZERO);
        }
        Map<Long, Object> ordered = new TreeMap<>();
        for (Payment payment : payments) {
            if (ordered.put(payment.position(), payment) != null) {
                throw new IllegalArgumentException("清偿位置重复：" + payment.position());
            }
        }
        for (Impairment entry : impairments) {
            if (ordered.put(entry.position(), entry) != null) {
                throw new IllegalArgumentException("清偿与减值位置重复：" + entry.position());
            }
        }
        for (Object record : ordered.values()) {
            if (record instanceof Payment payment) {
                checkPaymentRecord(payment, claims, paid, impairment, receipts);
            } else {
                checkImpairmentRecord((Impairment) record, claims, paid, impairment, receipts);
            }
        }
        long paymentReceipts = receipts.values().stream().filter(receipt -> ACTION_SETTLE.equals(receipt.action())).count();
        long impairmentReceipts = receipts.values().stream().filter(receipt -> ACTION_IMPAIR.equals(receipt.action())).count();
        if (paymentReceipts != payments.size() || impairmentReceipts != impairments.size()) {
            throw new IllegalArgumentException("付款或减值回报缺少对应金额历史");
        }
        for (Claim claim : claims.values()) {
            if (paid.get(claim.claimId()).compareTo(claim.paid()) != 0
                    || impairment.get(claim.claimId()).compareTo(claim.impairment()) != 0) {
                throw new IllegalArgumentException("债权累计与历史事件不一致：" + claim.claimId());
            }
        }
    }

    private static void checkPaymentRecord(Payment payment, Map<String, Claim> claims,
                                           Map<String, BigDecimal> paid,
                                           Map<String, BigDecimal> impairment,
                                           Map<String, Receipt> receipts) {
        Receipt receipt = receipts.get(payment.settlementId());
        if (receipt == null || !ACTION_SETTLE.equals(receipt.action())
                || receipt.position() != payment.position()
                || receipt.atMillis() != payment.atMillis()) {
            throw new IllegalArgumentException("清偿记录缺少对应事件回报：" + payment.settlementId());
        }
        SettleRequest request = read(receipt.requestJson(), SettleRequest.class);
        if (!payment.schemeId().equals(request.schemeId())
                || !payment.cashEvidenceId().equals(request.cashEvidenceId())
                || payment.amount().compareTo(money(request.amount(), "原始清偿金额")) != 0
                || request.perClaim() == null || !payment.perClaim().keySet().equals(request.perClaim().keySet())) {
            throw new IllegalArgumentException("清偿记录与原始请求不一致：" + payment.settlementId());
        }
        for (Map.Entry<String, BigDecimal> entry : payment.perClaim().entrySet()) {
            if (entry.getValue().compareTo(money(request.perClaim().get(entry.getKey()), "原始清偿明细")) != 0) {
                throw new IllegalArgumentException("清偿明细与原始请求不一致：" + payment.settlementId());
            }
        }
        List<Claim> schemeClaims = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.schemeId().equals(payment.schemeId())) {
                schemeClaims.add(claim);
            }
        }
        if (schemeClaims.size() != payment.perClaim().size()) {
            throw new IllegalArgumentException("清偿未覆盖全部债权：" + payment.settlementId());
        }
        for (Claim claim : schemeClaims) {
            BigDecimal amount = payment.perClaim().get(claim.claimId());
            if (amount == null) {
                throw new IllegalArgumentException("清偿漏掉债权：" + claim.claimId());
            }
            BigDecimal nextPaid = paid.get(claim.claimId()).add(amount);
            if (nextPaid.compareTo(claim.face()) != 0) {
                throw new IllegalArgumentException("首轮清偿必须足额：" + claim.claimId());
            }
            BigDecimal released = payment.impairmentReleased().getOrDefault(claim.claimId(), ZERO);
            BigDecimal current = impairment.get(claim.claimId());
            if (released.compareTo(current) != 0) {
                throw new IllegalArgumentException("减值转回额与已计提不一致：" + claim.claimId());
            }
            paid.put(claim.claimId(), nextPaid);
            impairment.put(claim.claimId(), current.subtract(released));
        }
    }

    private static void checkImpairmentRecord(Impairment entry, Map<String, Claim> claims,
                                              Map<String, BigDecimal> paid,
                                              Map<String, BigDecimal> impairment,
                                              Map<String, Receipt> receipts) {
        Receipt receipt = receipts.get(entry.impairmentId());
        if (receipt == null || !ACTION_IMPAIR.equals(receipt.action())
                || receipt.position() != entry.position()
                || receipt.atMillis() != entry.atMillis()) {
            throw new IllegalArgumentException("减值记录缺少对应事件回报：" + entry.impairmentId());
        }
        ImpairRequest request = read(receipt.requestJson(), ImpairRequest.class);
        if (!entry.claimId().equals(request.claimId()) || !entry.evidenceRef().equals(request.evidenceRef())
                || entry.amount().compareTo(money(request.amount(), "原始减值金额")) != 0) {
            throw new IllegalArgumentException("减值记录与原始请求不一致：" + entry.impairmentId());
        }
        Claim claim = claims.get(entry.claimId());
        if (claim == null) {
            throw new IllegalArgumentException("减值引用未知债权：" + entry.claimId());
        }
        BigDecimal next = impairment.get(entry.claimId()).add(entry.amount());
        if (next.compareTo(claim.face().subtract(paid.get(entry.claimId()))) > 0) {
            throw new IllegalArgumentException("减值累计超过未付面值：" + entry.claimId());
        }
        impairment.put(entry.claimId(), next);
    }

    private static void checkReceipts(Map<String, Scheme> schemes, Map<String, Claim> claims,
                                      Map<String, Receipt> receipts) {
        Map<String, List<Receipt>> byScheme = new TreeMap<>();
        for (Receipt receipt : receipts.values()) {
            String schemeId = receiptSchemeId(receipt.action(), receipt.requestJson(), claims);
            if (!schemes.containsKey(schemeId)) {
                throw new IllegalArgumentException("事件回报引用未知方案：" + receipt.eventId());
            }
            byScheme.computeIfAbsent(schemeId, key -> new ArrayList<>()).add(receipt);
        }
        for (Scheme scheme : schemes.values()) {
            List<Receipt> list = byScheme.getOrDefault(scheme.schemeId(), List.of());
            list = new ArrayList<>(list);
            list.sort(Comparator.comparingLong(Receipt::position));
            Phase phase = null;
            String authorization = null;
            String cancellation = null;
            for (Receipt receipt : list) {
                switch (receipt.action()) {
                    case ACTION_PROPOSE -> {
                        SchemeRequest request = read(receipt.requestJson(), SchemeRequest.class);
                        if (phase != null || receipt.atMillis() > scheme.recordAtMillis()
                                || !scheme.symbol().equals(request.symbol())
                                || !scheme.issuerOwnerId().equals(request.issuerOwnerId())
                                || !scheme.companyAccountId().equals(request.companyAccountId())
                                || scheme.perShare().compareTo(money(request.perShare(), "原始每股金额")) != 0
                                || request.recordAtMillis() != scheme.recordAtMillis()
                                || request.exAtMillis() != scheme.exAtMillis()
                                || request.payAtMillis() != scheme.payAtMillis()) {
                            throw new IllegalArgumentException("方案与原始提案或阶段不一致：" + scheme.schemeId());
                        }
                        phase = Phase.PROPOSED;
                    }
                    case ACTION_AUTHORIZE -> {
                        AuthorizeRequest request = read(receipt.requestJson(), AuthorizeRequest.class);
                        if (phase != Phase.PROPOSED || receipt.atMillis() > scheme.recordAtMillis()) {
                            throw new IllegalArgumentException("授权的原始阶段或时点不合法：" + scheme.schemeId());
                        }
                        requireText(request.authorizationRef(), "原始授权引用");
                        authorization = request.authorizationRef();
                        phase = Phase.AUTHORIZED;
                    }
                    case ACTION_CANCEL -> {
                        CancelRequest request = read(receipt.requestJson(), CancelRequest.class);
                        if ((phase != Phase.PROPOSED && phase != Phase.AUTHORIZED)
                                || receipt.atMillis() > scheme.recordAtMillis()) {
                            throw new IllegalArgumentException("撤销的原始阶段或时点不合法：" + scheme.schemeId());
                        }
                        requireText(request.reason(), "原始撤销原因");
                        cancellation = request.reason();
                        phase = Phase.CANCELLED;
                    }
                    case ACTION_REGISTER -> {
                        if (phase != Phase.AUTHORIZED || receipt.atMillis() != scheme.recordAtMillis()) {
                            throw new IllegalArgumentException("登记的原始阶段或时点不合法：" + scheme.schemeId());
                        }
                        phase = Phase.REGISTERED;
                    }
                    case ACTION_BOOK_EX -> {
                        if (phase != Phase.REGISTERED || receipt.atMillis() != scheme.exAtMillis()) {
                            throw new IllegalArgumentException("除息的原始阶段或时点不合法：" + scheme.schemeId());
                        }
                        phase = Phase.EX_BOOKED;
                    }
                    case ACTION_SETTLE -> {
                        if (phase != Phase.EX_BOOKED || receipt.atMillis() < scheme.payAtMillis()) {
                            throw new IllegalArgumentException("付款的原始阶段或时点不合法：" + scheme.schemeId());
                        }
                        phase = Phase.SETTLED;
                    }
                    case ACTION_IMPAIR -> {
                        if (phase != Phase.EX_BOOKED) {
                            throw new IllegalArgumentException("减值的原始阶段不合法：" + scheme.schemeId());
                        }
                    }
                    default -> throw new IllegalArgumentException("未知分红动作：" + receipt.action());
                }
            }
            if (phase != scheme.phase() || !Objects.equals(authorization, scheme.authorizationRef())
                    || !Objects.equals(cancellation, scheme.cancelReason())) {
                throw new IllegalArgumentException("方案终态与完整已接受事件链不一致：" + scheme.schemeId());
            }
        }
    }
    private static String receiptSchemeId(String action, String requestJson,
                                          Map<String, Claim> claims) {
        switch (action) {
            case ACTION_PROPOSE -> {
                return read(requestJson, SchemeRequest.class).schemeId();
            }
            case ACTION_AUTHORIZE -> {
                return read(requestJson, AuthorizeRequest.class).schemeId();
            }
            case ACTION_CANCEL -> {
                return read(requestJson, CancelRequest.class).schemeId();
            }
            case ACTION_REGISTER -> {
                return read(requestJson, RegisterRequest.class).schemeId();
            }
            case ACTION_BOOK_EX -> {
                return read(requestJson, BookExRequest.class).schemeId();
            }
            case ACTION_SETTLE -> {
                return read(requestJson, SettleRequest.class).schemeId();
            }
            case ACTION_IMPAIR -> {
                String claimId = read(requestJson, ImpairRequest.class).claimId();
                Claim claim = claims.get(claimId);
                if (claim == null) {
                    throw new IllegalArgumentException("减值回报引用未知债权：" + claimId);
                }
                return claim.schemeId();
            }
            default -> throw new IllegalArgumentException("未知事件动作：" + action);
        }
    }

    private static int rank(Phase phase) {
        return switch (phase) {
            case PROPOSED -> 1;
            case AUTHORIZED -> 2;
            case REGISTERED -> 3;
            case EX_BOOKED -> 4;
            case SETTLED -> 5;
            case CANCELLED -> 0;
        };
    }

    /**
     * 无歧义债权标识：长度前缀，避免方案标识含分隔符时与账户别名碰撞。
     *
     * @param schemeId  方案标识
     * @param accountId 受益账户
     * @return 规范标识
     */
    private static String claimId(String schemeId, String accountId) {
        return schemeId.length() + ":" + schemeId + ":" + accountId;
    }

    // ------------------------------------------------------------------
    // 内部：通用校验
    // ------------------------------------------------------------------

    private static <T> T read(String json, Class<T> type) {
        try {
            T value = MAPPER.readValue(json, type);
            if (value == null || !json(value).equals(json)) {
                throw new IllegalArgumentException("原始请求不是完整规范内容");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("事件回报的原始请求无法解析", failure);
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("请求无法规范化", failure);
        }
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "分红状态");
    }

    private static void requireEvent(Event event) {
        Objects.requireNonNull(event, "宿主事件");
        requireText(event.eventId(), "事件标识");
        if (event.position() <= 0) {
            throw new IllegalArgumentException("事件位置必须为正：" + event.eventId());
        }
        if (event.atMillis() < 0) {
            throw new IllegalArgumentException("事件时点不能为负：" + event.eventId());
        }
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
