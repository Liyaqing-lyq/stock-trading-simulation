package edu.cufe.auction.world.bank;

/**
 * 拒绝与校验原因码（与参考核算同前缀，稳定可断言的文本）。
 */
public final class Reasons {
    /** 原因码：AMOUNT_INVALID。 */
    public static final String AMOUNT_INVALID = "AMOUNT_INVALID";
    /** 原因码：AMOUNT_PRECISION。 */
    public static final String AMOUNT_PRECISION = "AMOUNT_PRECISION";
    /** 原因码：AMOUNT_OUT_OF_RANGE。 */
    public static final String AMOUNT_OUT_OF_RANGE = "AMOUNT_OUT_OF_RANGE";
    /** 原因码：AMOUNT_NOT_POSITIVE。 */
    public static final String AMOUNT_NOT_POSITIVE = "AMOUNT_NOT_POSITIVE";
    /** 原因码：COLLATERAL_INVALID。 */
    public static final String COLLATERAL_INVALID = "COLLATERAL_INVALID";
    /** 原因码：COLLATERAL_PRECISION。 */
    public static final String COLLATERAL_PRECISION = "COLLATERAL_PRECISION";
    /** 原因码：COLLATERAL_NEGATIVE。 */
    public static final String COLLATERAL_NEGATIVE = "COLLATERAL_NEGATIVE";
    /** 原因码：COLLATERAL_OUT_OF_RANGE。 */
    public static final String COLLATERAL_OUT_OF_RANGE = "COLLATERAL_OUT_OF_RANGE";

    /** 原因码：PROTOCOL_ERROR。 */
    public static final String PROTOCOL_ERROR = "PROTOCOL_ERROR";
    /** 原因码：COMMAND_ID_CONFLICT。 */
    public static final String COMMAND_ID_CONFLICT = "COMMAND_ID_CONFLICT";
    /** 原因码：UNKNOWN_COMMAND。 */
    public static final String UNKNOWN_COMMAND = "UNKNOWN_COMMAND";
    /** 原因码：CONFIG_CHANGED_REQUIRES_NEW_RUN。 */
    public static final String CONFIG_CHANGED_REQUIRES_NEW_RUN = "CONFIG_CHANGED_REQUIRES_NEW_RUN";
    /** 原因码：RUN_ALREADY_ACTIVE。 */
    public static final String RUN_ALREADY_ACTIVE = "RUN_ALREADY_ACTIVE";

    /** 原因码：PAYER_BALANCE_INSUFFICIENT。 */
    public static final String PAYER_BALANCE_INSUFFICIENT = "PAYER_BALANCE_INSUFFICIENT";
    /** 原因码：BORROWER_BALANCE_INSUFFICIENT。 */
    public static final String BORROWER_BALANCE_INSUFFICIENT = "BORROWER_BALANCE_INSUFFICIENT";
    /** 原因码：RESERVE_INSUFFICIENT。 */
    public static final String RESERVE_INSUFFICIENT = "RESERVE_INSUFFICIENT";
    /** 原因码：INSUFFICIENT_FUNDS。 */
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    /** 原因码：ACTOR_NOT_AUTHORIZED。 */
    public static final String ACTOR_NOT_AUTHORIZED = "ACTOR_NOT_AUTHORIZED";
    /** 原因码：NOT_IN_DEFAULT。 */
    public static final String NOT_IN_DEFAULT = "NOT_IN_DEFAULT";
    /** 原因码：FACILITY_CLOSED。 */
    public static final String FACILITY_CLOSED = "FACILITY_CLOSED";
    /** 原因码：CONFIG_INVALID。 */
    public static final String CONFIG_INVALID = "CONFIG_INVALID";
    /** 原因码：NO_ACTIVE_RUN。 */
    public static final String NO_ACTIVE_RUN = "NO_ACTIVE_RUN";
    /** 原因码：STATE_FINGERPRINT_MISMATCH。 */
    public static final String STATE_FINGERPRINT_MISMATCH = "STATE_FINGERPRINT_MISMATCH";
    /** 原因码：RUN_ALREADY_BOUND。 */
    public static final String RUN_ALREADY_BOUND = "RUN_ALREADY_BOUND";
    /** 原因码：RUN_BINDING_INVALID。 */
    public static final String RUN_BINDING_INVALID = "RUN_BINDING_INVALID";
    /** 原因码：REPAY_EXCEEDS_ONBOOK。 */
    public static final String REPAY_EXCEEDS_ONBOOK = "REPAY_EXCEEDS_ONBOOK";
    /** 原因码：REPAY_EXCEEDS_CONTRACT。 */
    public static final String REPAY_EXCEEDS_CONTRACT = "REPAY_EXCEEDS_CONTRACT";
    /** 原因码：EXCEEDS_CONTRACT。 */
    public static final String EXCEEDS_CONTRACT = "EXCEEDS_CONTRACT";
    /** 原因码：WRITTEN_OFF_EXCEEDED。 */
    public static final String WRITTEN_OFF_EXCEEDED = "WRITTEN_OFF_EXCEEDED";
    /** 原因码：INSUFFICIENT_PROVISION。 */
    public static final String INSUFFICIENT_PROVISION = "INSUFFICIENT_PROVISION";
    /** 原因码：ONBOOK_INSUFFICIENT。 */
    public static final String ONBOOK_INSUFFICIENT = "ONBOOK_INSUFFICIENT";
    /** 原因码：LOAN_BUCKET_MISMATCH。 */
    public static final String LOAN_BUCKET_MISMATCH = "LOAN_BUCKET_MISMATCH";
    /** 原因码：UNKNOWN_BORROWER。 */
    public static final String UNKNOWN_BORROWER = "UNKNOWN_BORROWER";
    /** 原因码：UNKNOWN_BANK。 */
    public static final String UNKNOWN_BANK = "UNKNOWN_BANK";
    /** 原因码：UNKNOWN_ACCOUNT。 */
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";

    /** 原因码：ORIGIN_UNKNOWN。 */
    public static final String ORIGIN_UNKNOWN = "ORIGIN_UNKNOWN";
    /** 原因码：ORIGIN_OWNER_MISMATCH。 */
    public static final String ORIGIN_OWNER_MISMATCH = "ORIGIN_OWNER_MISMATCH";
    /** 原因码：ORIGIN_BANK_MISMATCH。 */
    public static final String ORIGIN_BANK_MISMATCH = "ORIGIN_BANK_MISMATCH";
    /** 原因码：ORIGIN_AMOUNT_MISMATCH。 */
    public static final String ORIGIN_AMOUNT_MISMATCH = "ORIGIN_AMOUNT_MISMATCH";
    /** 原因码：ORIGIN_ALREADY_CONSUMED。 */
    public static final String ORIGIN_ALREADY_CONSUMED = "ORIGIN_ALREADY_CONSUMED";
    /** 原因码：ORIGIN_CASH_INSUFFICIENT。 */
    public static final String ORIGIN_CASH_INSUFFICIENT = "ORIGIN_CASH_INSUFFICIENT";

    /** 原因码：FACILITY_LIMIT。 */
    public static final String FACILITY_LIMIT = "FACILITY_LIMIT";
    /** 原因码：FACILITY_IN_DEFAULT。 */
    public static final String FACILITY_IN_DEFAULT = "FACILITY_IN_DEFAULT";
    /** 原因码：FACILITY_ALREADY_PLEDGED。 */
    public static final String FACILITY_ALREADY_PLEDGED = "FACILITY_ALREADY_PLEDGED";
    /** 原因码：FACILITY_COLLATERAL_SHORT。 */
    public static final String FACILITY_COLLATERAL_SHORT = "FACILITY_COLLATERAL_SHORT";
    /** 原因码：FACILITY_UNKNOWN_PROVIDER。 */
    public static final String FACILITY_UNKNOWN_PROVIDER = "FACILITY_UNKNOWN_PROVIDER";
    /** 原因码：PROVIDER_CAPACITY。 */
    public static final String PROVIDER_CAPACITY = "PROVIDER_CAPACITY";
    /** 原因码：PROVISION_EXCEEDS_GROSS。 */
    public static final String PROVISION_EXCEEDS_GROSS = "PROVISION_EXCEEDS_GROSS";
    /** 原因码：OVERPAY_FACILITY。 */
    public static final String OVERPAY_FACILITY = "OVERPAY_FACILITY";
    /** 原因码：OVERWAIVE_FACILITY。 */
    public static final String OVERWAIVE_FACILITY = "OVERWAIVE_FACILITY";

    /** 原因码：CREDIT_BLOCKED_BY_BREACH。 */
    public static final String CREDIT_BLOCKED_BY_BREACH = "CREDIT_BLOCKED_BY_BREACH";

    /** 原因码：RATE_INVALID。 */
    public static final String RATE_INVALID = "RATE_INVALID";
    /** 原因码：RATE_PRECISION。 */
    public static final String RATE_PRECISION = "RATE_PRECISION";
    /** 原因码：RATE_OUT_OF_RANGE。 */
    public static final String RATE_OUT_OF_RANGE = "RATE_OUT_OF_RANGE";
    /** 原因码：TENOR_INVALID。 */
    public static final String TENOR_INVALID = "TENOR_INVALID";
    /** 原因码：MATURITY_INVALID。 */
    public static final String MATURITY_INVALID = "MATURITY_INVALID";
    /** 原因码：TIME_REVERSED。 */
    public static final String TIME_REVERSED = "TIME_REVERSED";
    /** 原因码：FUTURE_ACCRUAL。 */
    public static final String FUTURE_ACCRUAL = "FUTURE_ACCRUAL";
    /** 原因码：NO_TERMS。 */
    public static final String NO_TERMS = "NO_TERMS";
    /** 原因码：TERMS_CONFLICT。 */
    public static final String TERMS_CONFLICT = "TERMS_CONFLICT";
    /** 原因码：INTEREST_OVERFLOW。 */
    public static final String INTEREST_OVERFLOW = "INTEREST_OVERFLOW";
    /** 原因码：INTEREST_EXCEEDS_ACCRUED。 */
    public static final String INTEREST_EXCEEDS_ACCRUED = "INTEREST_EXCEEDS_ACCRUED";
    /** 原因码：NOT_YET_MATURED。 */
    public static final String NOT_YET_MATURED = "NOT_YET_MATURED";
    /** 原因码：COMMITMENT_UNKNOWN。 */
    public static final String COMMITMENT_UNKNOWN = "COMMITMENT_UNKNOWN";
    /** 原因码：COMMITMENT_EXISTS。 */
    public static final String COMMITMENT_EXISTS = "COMMITMENT_EXISTS";
    /** 原因码：COMMITMENT_CLOSED。 */
    public static final String COMMITMENT_CLOSED = "COMMITMENT_CLOSED";
    /** 原因码：COMMITMENT_EXPIRED。 */
    public static final String COMMITMENT_EXPIRED = "COMMITMENT_EXPIRED";
    /** 原因码：COMMITMENT_LIMIT。 */
    public static final String COMMITMENT_LIMIT = "COMMITMENT_LIMIT";
    /** 原因码：COMMITMENT_BORROWER_MISMATCH。 */
    public static final String COMMITMENT_BORROWER_MISMATCH = "COMMITMENT_BORROWER_MISMATCH";
    /** 原因码：COMMITMENT_EXPIRY_INVALID。 */
    public static final String COMMITMENT_EXPIRY_INVALID = "COMMITMENT_EXPIRY_INVALID";
    /** 原因码：UNKNOWN_AUTHORIZED。 */
    public static final String UNKNOWN_AUTHORIZED = "UNKNOWN_AUTHORIZED";
    /** 原因码：INTEREST_PROFILE_INVALID。 */
    public static final String INTEREST_PROFILE_INVALID = "INTEREST_PROFILE_INVALID";
    /** 原因码：OFFER_UNKNOWN（本行未声明该存款 offer）。 */
    public static final String OFFER_UNKNOWN = "OFFER_UNKNOWN";
    /** 原因码：OFFER_MISSING（启用利息档案但该行未声明存款 offer）。 */
    public static final String OFFER_MISSING = "OFFER_MISSING";
    /** 原因码：OFFER_WITHOUT_PROFILE（旧档案不得声明存款 offer）。 */
    public static final String OFFER_WITHOUT_PROFILE = "OFFER_WITHOUT_PROFILE";
    /** 原因码：REPRICE_REQUIRES_OFFER（追加存款不得改价，须新 offer）。 */
    public static final String REPRICE_REQUIRES_OFFER = "REPRICE_REQUIRES_OFFER";
    /** 原因码：NON_ACCRUAL_ACCOUNT（该存款账户明确非应计，无应付利息）。 */
    public static final String NON_ACCRUAL_ACCOUNT = "NON_ACCRUAL_ACCOUNT";
    /** 原因码：LEGACY_COMMITMENT_DISABLED（档案声明只认明细承诺来源）。 */
    public static final String LEGACY_COMMITMENT_DISABLED = "LEGACY_COMMITMENT_DISABLED";
    /** 原因码：LEGACY_COMMITMENT_INVALID（来源归属声明非法）。 */
    public static final String LEGACY_COMMITMENT_INVALID = "LEGACY_COMMITMENT_INVALID";
    /** 原因码：TRANCHE_UNKNOWN（贷款期次不存在）。 */
    public static final String TRANCHE_UNKNOWN = "TRANCHE_UNKNOWN";
    /** 原因码：LOAN_SETTLED（该合同本息已清，旧到期任务不得再计息）。 */
    public static final String LOAN_SETTLED = "LOAN_SETTLED";
    /** 原因码：TRANCHE_BIND_FAILED（提款结果里找不到唯一新期次，无法绑定）。 */
    public static final String TRANCHE_BIND_FAILED = "TRANCHE_BIND_FAILED";
    /** 原因码：LOAN_TRANCHE_UNRESOLVED（旧台账无期次定位且无法唯一解析）。 */
    public static final String LOAN_TRANCHE_UNRESOLVED = "LOAN_TRANCHE_UNRESOLVED";
    /** 原因码：LOAN_OBLIGATION_MISMATCH（期次 onbook+writtenOff≠contract）。 */
    public static final String LOAN_OBLIGATION_MISMATCH = "LOAN_OBLIGATION_MISMATCH";

    /** 原因码：FACILITY_PROFILE_INVALID（B3/B4 条款声明非法）。 */
    public static final String FACILITY_PROFILE_INVALID = "FACILITY_PROFILE_INVALID";
    /** 原因码：FACILITY_UNKNOWN_AUTHORIZATION（该行无此授权）。 */
    public static final String FACILITY_UNKNOWN_AUTHORIZATION = "FACILITY_UNKNOWN_AUTHORIZATION";
    /** 原因码：FACILITY_AUTHORIZATION_EXPIRED（授权期满只禁新提款）。 */
    public static final String FACILITY_AUTHORIZATION_EXPIRED = "FACILITY_AUTHORIZATION_EXPIRED";
    /** 原因码：FACILITY_AUTHORIZATION_DUPLICATE（授权号重复）。 */
    public static final String FACILITY_AUTHORIZATION_DUPLICATE =
            "FACILITY_AUTHORIZATION_DUPLICATE";
    /** 原因码：FACILITY_COLLATERAL_POLICY_INVALID（押品政策非法）。 */
    public static final String FACILITY_COLLATERAL_POLICY_INVALID =
            "FACILITY_COLLATERAL_POLICY_INVALID";
    /** 原因码：FACILITY_COLLATERAL_SELF_ASSESSED（命令自报押品价值）。 */
    public static final String FACILITY_COLLATERAL_SELF_ASSESSED =
            "FACILITY_COLLATERAL_SELF_ASSESSED";
    /** 原因码：FACILITY_INTEREST_OUTSTANDING（利息未清，不得关闭/解除）。 */
    public static final String FACILITY_INTEREST_OUTSTANDING = "FACILITY_INTEREST_OUTSTANDING";
    /** 原因码：FACILITY_NOT_SETTLED（本金或利息未清）。 */
    public static final String FACILITY_NOT_SETTLED = "FACILITY_NOT_SETTLED";
    /** 原因码：FACILITY_MATURITY_INVALID（设施到期早于授权时点）。 */
    public static final String FACILITY_MATURITY_INVALID = "FACILITY_MATURITY_INVALID";
    /** 原因码：FACILITY_OVER_DRAW（超出授权总额）。 */
    public static final String FACILITY_OVER_DRAW = "FACILITY_OVER_DRAW";
    /** 原因码：COLLATERAL_UNKNOWN（档案未登记该押品）。 */
    public static final String COLLATERAL_UNKNOWN = "COLLATERAL_UNKNOWN";
    /** 原因码：COLLATERAL_DUPLICATE（档案重复登记押品）。 */
    public static final String COLLATERAL_DUPLICATE = "COLLATERAL_DUPLICATE";
    /** 原因码：COLLATERAL_OWNER_MISMATCH（押品不属于该银行）。 */
    public static final String COLLATERAL_OWNER_MISMATCH = "COLLATERAL_OWNER_MISMATCH";
    /** 原因码：COLLATERAL_ENCUMBERED（已被其他设施占用）。 */
    public static final String COLLATERAL_ENCUMBERED = "COLLATERAL_ENCUMBERED";
    /** 原因码：COLLATERAL_NOT_PLEDGED（该押品未质押给本设施）。 */
    public static final String COLLATERAL_NOT_PLEDGED = "COLLATERAL_NOT_PLEDGED";
    /** 原因码：COLLATERAL_DISPOSED（押品已处置，不得重复）。 */
    public static final String COLLATERAL_DISPOSED = "COLLATERAL_DISPOSED";
    /** 原因码：COLLATERAL_SHORTFALL（可用押品不足）。 */
    public static final String COLLATERAL_SHORTFALL = "COLLATERAL_SHORTFALL";
    /** 原因码：CLAIM_UNKNOWN（转让债权不存在）。 */
    public static final String CLAIM_UNKNOWN = "CLAIM_UNKNOWN";
    /** 原因码：CLAIM_HOLDER_MISMATCH（处置权只属当前持有人）。 */
    public static final String CLAIM_HOLDER_MISMATCH = "CLAIM_HOLDER_MISMATCH";
    /** 原因码：CLAIM_ALREADY_SETTLED（该债权已全额结算）。 */
    public static final String CLAIM_ALREADY_SETTLED = "CLAIM_ALREADY_SETTLED";
    /** 原因码：CLAIM_EXCEEDS_NOMINAL（超过未结算名义）。 */
    public static final String CLAIM_EXCEEDS_NOMINAL = "CLAIM_EXCEEDS_NOMINAL";
    /** 原因码：CLAIM_TIERS_INVALID（债权阶梯声明非法）。 */
    public static final String CLAIM_TIERS_INVALID = "CLAIM_TIERS_INVALID";
    /** 原因码：ASSIGN_SELF（自买自卖）。 */
    public static final String ASSIGN_SELF = "ASSIGN_SELF";
    /** 原因码：ASSIGN_BUYER_UNKNOWN（买方无现金账户）。 */
    public static final String ASSIGN_BUYER_UNKNOWN = "ASSIGN_BUYER_UNKNOWN";
    /** 原因码：RESOLUTION_NOT_OPEN（处置流程未开启）。 */
    public static final String RESOLUTION_NOT_OPEN = "RESOLUTION_NOT_OPEN";
    /** 原因码：RESOLUTION_ALREADY_OPEN（重复开启）。 */
    public static final String RESOLUTION_ALREADY_OPEN = "RESOLUTION_ALREADY_OPEN";
    /** 原因码：RESOLUTION_ACTIVE（处置期内拒新增信用）。 */
    public static final String RESOLUTION_ACTIVE = "RESOLUTION_ACTIVE";
    /** 原因码：RESOLUTION_CONSENT_MISMATCH（consents 与剩余负债不符）。 */
    public static final String RESOLUTION_CONSENT_MISMATCH = "RESOLUTION_CONSENT_MISMATCH";
    /** 原因码：RESOLUTION_RESIDUAL_DEBT（仍有有效债务，股东无残余）。 */
    public static final String RESOLUTION_RESIDUAL_DEBT = "RESOLUTION_RESIDUAL_DEBT";
    /** 原因码：COLLATERAL_OVER_BOOK（估值超过真实账面）。 */
    public static final String COLLATERAL_OVER_BOOK = "COLLATERAL_OVER_BOOK";
    /** 原因码：COLLATERAL_ASSET_TAKEN（同一真实资产被两份声明重复绑定）。 */
    public static final String COLLATERAL_ASSET_TAKEN = "COLLATERAL_ASSET_TAKEN";
    /** 原因码：COLLATERAL_ASSET_UNKNOWN（声明资产在开局账簿中不存在）。 */
    public static final String COLLATERAL_ASSET_UNKNOWN = "COLLATERAL_ASSET_UNKNOWN";
    /** 原因码：COLLATERAL_EXHAUSTED（底层资产已耗尽）。 */
    public static final String COLLATERAL_EXHAUSTED = "COLLATERAL_EXHAUSTED";
    /** 原因码：COLLATERAL_PLEDGED（已质押资产不得普通转让）。 */
    public static final String COLLATERAL_PLEDGED = "COLLATERAL_PLEDGED";
    /** 原因码：PURCHASE_UNKNOWN（无该买方许可）。 */
    public static final String PURCHASE_UNKNOWN = "PURCHASE_UNKNOWN";
    /** 原因码：PURCHASE_SELF（买方与卖方同一主体）。 */
    public static final String PURCHASE_SELF = "PURCHASE_SELF";
    /** 原因码：PURCHASE_EXPIRED（许可已过期）。 */
    public static final String PURCHASE_EXPIRED = "PURCHASE_EXPIRED";
    /** 原因码：PURCHASE_CONSUMED（许可已用）。 */
    public static final String PURCHASE_CONSUMED = "PURCHASE_CONSUMED";
    /** 原因码：PURCHASE_MISMATCH（卖方/对象/价格与许可不符）。 */
    public static final String PURCHASE_MISMATCH = "PURCHASE_MISMATCH";
    /** 原因码：PURCHASE_SUBJECT_MISMATCH（对象类型不匹配）。 */
    public static final String PURCHASE_SUBJECT_MISMATCH = "PURCHASE_SUBJECT_MISMATCH";
    /** 原因码：CONSENT_UNKNOWN（无该解除许可）。 */
    public static final String CONSENT_UNKNOWN = "CONSENT_UNKNOWN";
    /** 原因码：CONSENT_EXPIRED（许可已过期）。 */
    public static final String CONSENT_EXPIRED = "CONSENT_EXPIRED";
    /** 原因码：CONSENT_USED（许可已使用）。 */
    public static final String CONSENT_USED = "CONSENT_USED";
    /** 原因码：CONSENT_MISMATCH（账户/金额/银行与许可不符）。 */
    public static final String CONSENT_MISMATCH = "CONSENT_MISMATCH";
    /** 原因码：CONSENT_CREDITOR_INVALID（非真实权利人或债务自认）。 */
    public static final String CONSENT_CREDITOR_INVALID = "CONSENT_CREDITOR_INVALID";
    /** 原因码：PROVIDER_CAPITAL_MISMATCH（开局资本声明不自洽）。 */
    public static final String PROVIDER_CAPITAL_MISMATCH = "PROVIDER_CAPITAL_MISMATCH";
    /** 原因码：PROVIDER_CAPITAL_MISSING（未声明提供者开局事实）。 */
    public static final String PROVIDER_CAPITAL_MISSING = "PROVIDER_CAPITAL_MISSING";
    /** 原因码：PROVIDER_CAPITAL_BROKEN（实际资产负债与冻结初始资本不符）。 */
    public static final String PROVIDER_CAPITAL_BROKEN = "PROVIDER_CAPITAL_BROKEN";
    /** 原因码：FACILITY_MATURED（设施已到期，禁止新提款）。 */
    public static final String FACILITY_MATURED = "FACILITY_MATURED";
    /** 原因码：TIER_COVERAGE_MISSING（清算阶梯未覆盖真实负债科目）。 */
    public static final String TIER_COVERAGE_MISSING = "TIER_COVERAGE_MISSING";
    /** 原因码：INTEREST_OUTSTANDING（应付利息未清，不能直接分配）。 */
    public static final String INTEREST_OUTSTANDING = "INTEREST_OUTSTANDING";
    /** 原因码：INVALID_STATE_TRANSITION（候选状态不自洽，整批拒绝）。 */
    public static final String INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION";
    /** 原因码：TRANSFER_INVALID（资产转移记录字段非法）。 */
    public static final String TRANSFER_INVALID = "TRANSFER_INVALID";
    /** 原因码：TRANSFER_DUPLICATE（同一资产已被转移过，不能再次转移）。 */
    public static final String TRANSFER_DUPLICATE = "TRANSFER_DUPLICATE";
    /** 原因码：TRANSFER_MISSING（已处置资产缺少真实的归属转移记录）。 */
    public static final String TRANSFER_MISSING = "TRANSFER_MISSING";
    /** 原因码：FUND_INVALID（资金桶文本不是精确的 CASH|acct 或 DEPOSIT|bank|holder）。 */
    public static final String FUND_INVALID = "FUND_INVALID";
    /** 原因码：FUND_OWNER_MISMATCH（资金桶的真实归属与档案声明不一致）。 */
    public static final String FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";
    /** 原因码：FUND_CUSTODY_UNSUPPORTED（托管受益权尚未实现，不伪装成经营人存款）。 */
    public static final String FUND_CUSTODY_UNSUPPORTED = "FUND_CUSTODY_UNSUPPORTED";
    /** 原因码：PAYMENT_SAME_BUCKET（同一资金桶自付自收，属无效操作）。 */
    public static final String PAYMENT_SAME_BUCKET = "PAYMENT_SAME_BUCKET";
    /** 原因码：PAYMENT_FREEZE_REQUIRED（未提供受信冻结快照，支付一律拒绝）。 */
    public static final String PAYMENT_FREEZE_REQUIRED = "PAYMENT_FREEZE_REQUIRED";
    /** 原因码：PAYMENT_FREEZE_INVALID（冻结快照含负值或非规范桶键）。 */
    public static final String PAYMENT_FREEZE_INVALID = "PAYMENT_FREEZE_INVALID";
    /** 原因码：PAYMENT_FREEZE_EXCEEDS_BALANCE（冻结超过该桶余额，快照不可信）。 */
    public static final String PAYMENT_FREEZE_EXCEEDS_BALANCE = "PAYMENT_FREEZE_EXCEEDS_BALANCE";
    /** 原因码：SETTLEMENT_UNSUPPORTED（结算/成交凭据必须来自真实撮合桥，纯接缝一律拒绝）。 */
    public static final String SETTLEMENT_UNSUPPORTED = "SETTLEMENT_UNSUPPORTED";
    /** 原因码：INTERNAL_COMMAND_NOT_CALLABLE（内部派生腿没有可公开构造的命令）。 */
    public static final String INTERNAL_COMMAND_NOT_CALLABLE = "INTERNAL_COMMAND_NOT_CALLABLE";
    /** 原因码：CUSTODY_FUND_REQUIRED（托管入口只处理至少一侧为托管受益权的付款）。 */
    public static final String CUSTODY_FUND_REQUIRED = "CUSTODY_FUND_REQUIRED";
    /** 原因码：CUSTODY_PROFILE_INVALID（托管档案缺结算行、负债科目或不唯一）。 */
    public static final String CUSTODY_PROFILE_INVALID = "CUSTODY_PROFILE_INVALID";
    /** 原因码：CUSTODY_UNKNOWN_CUSTODIAN（托管 key 未在档案声明）。 */
    public static final String CUSTODY_UNKNOWN_CUSTODIAN = "CUSTODY_UNKNOWN_CUSTODIAN";
    /** 原因码：CUSTODY_UNKNOWN_CLIENT（客户 key 未在档案声明或现金账户不符）。 */
    public static final String CUSTODY_UNKNOWN_CLIENT = "CUSTODY_UNKNOWN_CLIENT";
    /** 原因码：CUSTODY_POOL_ACCRUAL（托管池不是明确非应计，拒绝静默降息或计息）。 */
    public static final String CUSTODY_POOL_ACCRUAL = "CUSTODY_POOL_ACCRUAL";
    /** 原因码：CUSTODY_POOL_MISMATCH（池存款资产与客户明细负债合计不相等）。 */
    public static final String CUSTODY_POOL_MISMATCH = "CUSTODY_POOL_MISMATCH";
    /** 原因码：CUSTODY_DETAIL_INVALID（客户明细金额/归属非法）。 */
    public static final String CUSTODY_DETAIL_INVALID = "CUSTODY_DETAIL_INVALID";
    /** 原因码：CUSTODY_ORIGIN_MISSING（既有非空池缺少来源声明）。 */
    public static final String CUSTODY_ORIGIN_MISSING = "CUSTODY_ORIGIN_MISSING";
    /** 原因码：CUSTODY_OPENING_NOT_EMPTY（空池开局但池存款非零）。 */
    public static final String CUSTODY_OPENING_NOT_EMPTY = "CUSTODY_OPENING_NOT_EMPTY";
    /** 原因码：CUSTODY_POOL_FREEZE（池层冻结无法归属到具体客户，整笔拒绝）。 */
    public static final String CUSTODY_POOL_FREEZE = "CUSTODY_POOL_FREEZE";
    /** 原因码：CUSTODY_LEG_CONFLICT（内部派生腿标识已被占用，拒绝伪造/复用）。 */
    public static final String CUSTODY_LEG_CONFLICT = "CUSTODY_LEG_CONFLICT";
    /** 原因码：CUSTODY_BANK_MISMATCH（托管人的池科目在非声明结算行上有余额/条款）。 */
    public static final String CUSTODY_BANK_MISMATCH = "CUSTODY_BANK_MISMATCH";

    private Reasons() {
    }
}
