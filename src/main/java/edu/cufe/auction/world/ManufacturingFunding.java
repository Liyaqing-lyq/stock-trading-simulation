package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.Fund;

import java.util.Objects;

/**
 * 制造经营五段资金腿的<b>冻结路由配置</b>（{@code manufacturing-funding} 模块）。
 *
 * <p><b>为什么要单独冻结</b>：采购到货、自动回款、期末关期这三段钱不是由某个人当场选的，
 * 而是由更早的业务事实（采购单、交付、期间）确定性派生的。系统派生不能再问一次“用哪个桶”，
 * 否则同一业务事实可以在重开/重演时换桶付款，账就不可复现。因此这四段路由在开局时一次性
 * 冻结进首个提交，随运行画像（{@link BranchProfile}）一起进入子分支；换一套配置重开同一
 * 日志会在写盘前被拒绝。</p>
 *
 * <p><b>只接受现金桶</b>：本配置里的四个 token 必须都是 {@code CASH|<账户>}，且分别指向
 * {@link ManufacturingModel.Opening} 冻结的公司、供应商、客户与人力账户。开户档案里
 * 供应商/客户/人力是聚合主体，没有冻结的存款科目政策；因此“对方可以用自己的存款桶”
 * 这类选择不是本配置能表达的，也不会被静默替换成别的桶。</p>
 *
 * <p><b>公司出款仍可显式选桶</b>：本配置的 {@link #companyFund()} 只用于<b>收款</b>
 * （客户回款进公司经营账户）与期末关期的固定出款；单笔对外付款（投产人工、费用账单）的
 * 出款桶由真实人物在命令里显式给出，执行帧再按冻结的真实主体/账户映射复核。</p>
 *
 * @param policy       路由政策标签；当前只接受 {@link #POLICY_FROZEN_MAPPING_V1}
 * @param companyFund  公司经营现金桶 token（收款与关期出款）
 * @param supplierFund 供应商现金桶 token（到货付款收款方）
 * @param customerFund 客户现金桶 token（回款出款方）
 * @param opsFund      人力与场地现金桶 token（人工与费用收款方）
 */
public record ManufacturingFunding(String policy, String companyFund, String supplierFund,
                                   String customerFund, String opsFund) {

    /** 唯一被接受的路由政策：账户映射在开局冻结，运行期不得改写。 */
    public static final String POLICY_FROZEN_MAPPING_V1 = "FROZEN_MAPPING_V1";

    /** 校验政策、四个 token 的形式与互不相同。 */
    public ManufacturingFunding {
        if (!POLICY_FROZEN_MAPPING_V1.equals(policy)) {
            throw new IllegalArgumentException("未知制造资金路由政策：" + policy);
        }
        companyFund = cashToken(companyFund, "公司经营现金桶");
        supplierFund = cashToken(supplierFund, "供应商现金桶");
        customerFund = cashToken(customerFund, "客户现金桶");
        opsFund = cashToken(opsFund, "人力与场地现金桶");
        if (java.util.Set.of(companyFund, supplierFund, customerFund, opsFund).size() != 4) {
            throw new IllegalArgumentException("制造资金四段路由必须互不相同："
                    + companyFund + " / " + supplierFund + " / " + customerFund + " / " + opsFund);
        }
    }

    /**
     * 由冻结经营账户绑定派生默认路由：四段都使用开局指定账户各自的现金桶。
     *
     * <p>这是<b>显式派生</b>而不是默认推断：调用方仍然必须把结果传进运行构造器并随开局冻结，
     * 没启用该模块的运行不接受这份配置。</p>
     *
     * @param opening 制造经营账户绑定
     * @return 四段现金路由
     */
    public static ManufacturingFunding cash(ManufacturingModel.Opening opening) {
        Objects.requireNonNull(opening, "经营账户绑定");
        return new ManufacturingFunding(POLICY_FROZEN_MAPPING_V1,
                cashToken(opening.companyAccountId()), cashToken(opening.supplierAccountId()),
                cashToken(opening.customerAccountId()), cashToken(opening.opsAccountId()));
    }

    /**
     * 现金桶 token。
     *
     * @param accountId 现金账户标识
     * @return {@code CASH|账户}
     */
    public static String cashToken(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("现金账户标识不能为空");
        }
        if (accountId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("现金账户标识不得包含分隔符：" + accountId);
        }
        return "CASH|" + accountId;
    }

    /**
     * token 必须是合法现金桶并精确等于给定账户。
     *
     * @param token     待校验 token
     * @param name      字段中文名（用于拒绝文本）
     * @return 规范化 token
     */
    private static String cashToken(String token, String name) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        Fund parsed;
        try {
            parsed = Fund.parse(token);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + "不是合法资金桶：" + token, invalid);
        }
        if (!(parsed instanceof Fund.Cash cash)) {
            throw new IllegalArgumentException(name + "必须是现金桶，收到 " + token);
        }
        return "CASH|" + cash.accountId();
    }

    /**
     * 校验四个 token 与冻结经营账户绑定逐一对上。
     *
     * <p>这是开局与恢复都要过的成对检查：路由换到别的账户、把对手方换成公司自己的账户、
     * 或与 {@code ManufacturingModel.Opening} 不一致，都在这里拒绝。</p>
     *
     * @param opening 制造经营账户绑定
     * @throws IllegalArgumentException 任一段与绑定不一致
     */
    public void requireMatches(ManufacturingModel.Opening opening) {
        Objects.requireNonNull(opening, "经营账户绑定");
        requireSame(companyFund, cashToken(opening.companyAccountId()), "公司经营现金桶");
        requireSame(supplierFund, cashToken(opening.supplierAccountId()), "供应商现金桶");
        requireSame(customerFund, cashToken(opening.customerAccountId()), "客户现金桶");
        requireSame(opsFund, cashToken(opening.opsAccountId()), "人力与场地现金桶");
    }

    private static void requireSame(String actual, String expected, String name) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(name + "与经营账户绑定不一致：" + actual
                    + " / " + expected);
        }
    }
}
