package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M4a.1 任职与四岗权限纯核：确定性的访问判定，不含 I/O、时钟或余额写入。
 *
 * <p><b>能力边界</b>：本类只回答「某人物在某一时刻，能否对某组织/某产品/某账户做某动作」。
 * 它不执行订单、不改现金或持仓、不写世界日志、不裁剪快照、不调用模型。四岗
 * （{@code RESEARCHER}、{@code PORTFOLIO_MANAGER}、{@code RISK_OFFICER}、{@code EXECUTION_TRADER}）
 * 由此获得的是<b>判定</b>，不是已运行的角色工具；真正的动作执行与版本复核属于后续运行时单元。</p>
 *
 * <p><b>资产口径</b>：产品资产来自 {@link OpeningRegistry} 中已登记的 {@code PRODUCT} 财产主体，
 * 本类只保存「账户 → 财产主体」引用（{@link AccountBinding}），从不复制现金、余额或持仓。
 * 产品与组织自有财产分别解析，不从同名字符串猜身份；产品财产主体不得伪造人物/组织映射。</p>
 *
 * <p><b>授权口径</b>：角色权限只覆盖 {@link ProductMandate} 明确列出的账户。同一机构另一个未授权产品、
 * 雇主自有账户、别机构产品、未列出的同产品账户、别人的个人账户都不能顺带读写。个人账户只允许
 * 对应 {@code PERSON} 财产主体绑定的人物经 {@link #canReadPersonalAccount} 查看。</p>
 *
 * <p><b>任职口径</b>：任职区间为左闭右开 {@code [startsAt, endsAt)}，可空 {@code revokedAt} 单独保存，
 * 撤职不覆盖原期限、人物、组织、角色或 ID。有效结束时刻 = {@code min(endsAt, revokedAt)}，
 * 重叠判定使用该有效结束时刻，因此撤职当刻可由新人继任。首版明确不设兼职例外：同人物在同一组织
 * 不能在重叠区间兼任不同岗位，同一组织同一岗位也不能有两条重叠任职。历史 ID 永不复用。</p>
 *
 * <p><b>拒绝口径</b>：{@link #authorize} 对一切语义拒绝都返回 {@link Decision#NOT_AUTHORIZED}，
 * 不区分「不存在」与「无权」，避免向普通角色泄露未知账户或秘密任职的存在性。参数为空、
 * {@code now} 为负等调用方错误才抛出 {@link IllegalArgumentException}。</p>
 *
 * <p><b>复现口径</b>：所有时刻都是调用方显式传入的模拟毫秒，本类不读系统时钟；
 * 集合在构造时深拷贝并排序，{@link #toJson(State)} 输出稳定，可被 {@link #fromJson(String)} 还原为相等状态。
 * 但 {@code fromJson} 只保证状态内部一致：恢复时必须用 {@link #validateAgainst} 对照冻结开局重放，
 * 不能直接相信快照文件。</p>
 */
public final class RoleAccess {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private RoleAccess() {
    }

    /** 机构四岗与媒体采编两岗；媒体岗位由媒体纯核使用，旧运行档案不启用。 */
    public enum Role {
        /** 研究员：写研究，不能动账。 */
        RESEARCHER,
        /** 基金经理：设目标仓位，不能直接下单。 */
        PORTFOLIO_MANAGER,
        /** 风控：按授权发出风险暂停，不能越过程序硬限制。 */
        RISK_OFFICER,
        /** 执行交易员：在已授权产品账户内下单与撤单，不能改投资方向。 */
        EXECUTION_TRADER,
        /** 记者：提出选题并提交组织草稿，不能批准或发布。 */
        JOURNALIST,
        /** 编辑：实际读完本次草稿后批准/退回并发布，不能自己写稿。 */
        EDITOR
    }

    /** 需要判定的动作；与四岗及媒体两岗的对应关系见 {@link #rolePermits}。 */
    public enum Action {
        /** 读取本组织获授权的产品账户。 */
        READ_PRODUCT_ACCOUNT,
        /** 读取本组织档案（不等于读取账户，更不等于交易权）。 */
        READ_ORGANIZATION_FILE,
        /** 写研究报告。 */
        WRITE_RESEARCH,
        /** 设置产品目标仓位。 */
        SET_PORTFOLIO_TARGET,
        /** 发出风险暂停。 */
        ISSUE_RISK_HALT,
        /** 提交订单。 */
        EXECUTE_ORDER,
        /** 撤销订单。 */
        CANCEL_ORDER,
        /** 提出媒体选题并提交组织草稿（记者）。 */
        WRITE_STORY,
        /** 审阅组织草稿：批准或退回（编辑）。 */
        REVIEW_STORY,
        /** 发布已批准草稿（编辑）。 */
        PUBLISH_STORY
    }

    /** 判定结果；对外只有许可与拒绝两种，拒绝不区分原因以免泄露存在性。 */
    public enum Decision {
        /** 许可。 */
        ALLOW,
        /** 拒绝（含未知账户、未知人物、跨机构、已撤职、已过期等全部语义拒绝）。 */
        NOT_AUTHORIZED
    }

    /**
     * 判定范围：组织、可选产品、可选账户。
     *
     * <p>动作与范围必须严格匹配：组织级动作不得携带产品/账户，产品级动作必须且只能携带产品，
     * 账户级动作必须携带产品与账户。不匹配直接判为拒绝，不做宽松兜底。</p>
     *
     * @param organizationPartyId 组织标识，必填
     * @param productOwnerId      产品财产主体标识；账户级与产品级动作必填
     * @param accountId           账户标识；仅账户级动作必填
     */
    public record Target(String organizationPartyId, String productOwnerId, String accountId) {

        /** 校验组织必填、账户依赖产品。 */
        public Target {
            requireText(organizationPartyId, "判定范围的组织");
            if (accountId != null) {
                requireText(productOwnerId, "账户范围对应的产品");
            }
            if (accountId != null && accountId.isBlank()) {
                throw new IllegalArgumentException("判定范围的账户不能为空白");
            }
            if (productOwnerId != null && productOwnerId.isBlank()) {
                throw new IllegalArgumentException("判定范围的产品不能为空白");
            }
        }

        /**
         * 组织级范围。
         *
         * @param organizationPartyId 组织
         * @return 只含组织的范围
         */
        public static Target organization(String organizationPartyId) {
            return new Target(organizationPartyId, null, null);
        }

        /**
         * 产品级范围。
         *
         * @param organizationPartyId 组织
         * @param productOwnerId      产品财产主体
         * @return 含组织与产品的范围
         */
        public static Target product(String organizationPartyId, String productOwnerId) {
            return new Target(organizationPartyId, productOwnerId, null);
        }

        /**
         * 账户级范围。
         *
         * @param organizationPartyId 组织
         * @param productOwnerId      产品财产主体
         * @param accountId           产品账户
         * @return 含组织、产品与账户的范围
         */
        public static Target account(String organizationPartyId, String productOwnerId, String accountId) {
            return new Target(organizationPartyId, productOwnerId, accountId);
        }
    }

    /**
     * 账户引用：只记账户与财产主体的绑定，不记余额。
     *
     * @param accountId 账户标识
     * @param ownerId   已登记的财产主体标识
     */
    public record AccountBinding(String accountId, String ownerId) {

        /** 校验非空。 */
        public AccountBinding {
            requireText(accountId, "账户引用");
            requireText(ownerId, "账户引用的财产主体");
        }
    }

    /**
     * 个人账户绑定：只用于「本人可读自己账户」这一条判定，不含任何金额。
     *
     * @param accountId     个人账户标识
     * @param personPartyId 该账户所属人物
     */
    public record PersonAccount(String accountId, String personPartyId) {

        /** 校验非空。 */
        public PersonAccount {
            requireText(accountId, "个人账户");
            requireText(personPartyId, "个人账户所属人物");
        }
    }

    /**
     * 产品管理授权：一个产品由唯一组织管理，并只开放明确列出的账户。
     *
     * @param organizationPartyId 管理组织
     * @param productOwnerId      产品财产主体
     * @param accountIds          该产品明确授权的账户，不可为空、不可通配
     */
    public record ProductMandate(String organizationPartyId, String productOwnerId, List<String> accountIds) {

        /** 规范化并校验账户清单。 */
        public ProductMandate {
            requireText(organizationPartyId, "产品管理组织");
            requireText(productOwnerId, "产品财产主体");
            List<String> raw = new ArrayList<>(Objects.requireNonNull(accountIds, "产品授权账户不能为空"));
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("产品授权必须显式列出账户，不能靠 owner 通配：" + productOwnerId);
            }
            Set<String> distinct = new LinkedHashSet<>();
            for (String accountId : raw) {
                requireText(accountId, "产品授权账户");
                if (!distinct.add(accountId)) {
                    throw new IllegalArgumentException("产品授权账户重复：" + productOwnerId + " -> " + accountId);
                }
            }
            List<String> ordered = new ArrayList<>(distinct);
            ordered.sort(Comparator.naturalOrder());
            accountIds = List.copyOf(ordered);
        }
    }

    /**
     * 一条任职记录：人物—组织—岗位—有效期—可选撤销时刻。
     *
     * @param id                   任职标识，历史唯一
     * @param personPartyId        任职人物
     * @param organizationPartyId  任职组织
     * @param role                 岗位
     * @param startsAt             起始时刻（含）
     * @param endsAt               预定结束时刻（不含）
     * @param revokedAt            实际撤销时刻；未撤销为空
     */
    public record Appointment(String id, String personPartyId, String organizationPartyId, Role role,
                              long startsAt, long endsAt, Long revokedAt) {

        /** 校验标识、区间与撤销时刻。 */
        public Appointment {
            requireText(id, "任职标识");
            requireText(personPartyId, "任职人物");
            requireText(organizationPartyId, "任职组织");
            if (role == null) {
                throw new IllegalArgumentException("任职岗位不能为空");
            }
            if (startsAt < 0) {
                throw new IllegalArgumentException("任职起始时刻不能为负：" + startsAt);
            }
            if (startsAt >= endsAt) {
                throw new IllegalArgumentException("任职区间必须为非空左闭右开区间：" + id);
            }
            if (revokedAt != null && (revokedAt < startsAt || revokedAt >= endsAt)) {
                throw new IllegalArgumentException("撤销时刻必须落在任职期间内：" + id);
            }
        }

        /**
         * 有效结束时刻：撤销早于预定结束则取撤销时刻。
         *
         * @return 该任职实际失去权限的时刻
         */
        public long effectiveEnd() {
            return revokedAt == null ? endsAt : Math.min(endsAt, revokedAt);
        }

        /**
         * 判定某时刻是否仍在任职有效期内（含起始、不含有效结束）。
         *
         * @param now 模拟毫秒
         * @return 仍在任返回 true
         */
        public boolean activeAt(long now) {
            return now >= startsAt && now < effectiveEnd();
        }
    }

    /**
     * 授权状态：身份证与授权元数据，不含现金、持仓、日志或角色记忆。
     *
     * <p>构造期完成全部内部一致性校验（主键唯一、账户可解析、账户归属正确、区间合法、
     * 无重叠任职），因此 {@link #fromJson(String)} 还原出的篡改状态会被拒绝。资格种类
     * （人物/组织/产品）只在 {@link #open} 时对照 {@link OpeningRegistry} 校验，持久化状态
     * 的真实性由该次开局来源负责。</p>
     *
     * @param accounts      账户引用，按账户标识排序
     * @param personAccounts 个人账户绑定，按账户标识排序
     * @param mandates      产品管理授权，按产品排序
     * @param appointments  任职记录，按任职标识排序
     */
    public record State(List<AccountBinding> accounts, List<PersonAccount> personAccounts,
                        List<ProductMandate> mandates, List<Appointment> appointments) {

        /** 深拷贝、排序并校验全部不变量。 */
        public State {
            List<AccountBinding> accountList = sorted(accounts, Comparator.comparing(AccountBinding::accountId));
            Set<String> accountIds = new LinkedHashSet<>();
            for (AccountBinding binding : accountList) {
                if (!accountIds.add(binding.accountId())) {
                    throw new IllegalArgumentException("重复账户引用：" + binding.accountId());
                }
            }

            List<ProductMandate> mandateList = sorted(mandates,
                    Comparator.comparing(ProductMandate::productOwnerId));
            Set<String> products = new LinkedHashSet<>();
            Set<String> mandatedAccounts = new LinkedHashSet<>();
            for (ProductMandate mandate : mandateList) {
                if (!products.add(mandate.productOwnerId())) {
                    throw new IllegalArgumentException("同一产品只能有一个管理组织："
                            + mandate.productOwnerId());
                }
                for (String accountId : mandate.accountIds()) {
                    AccountBinding binding = findBinding(accountList, accountId);
                    if (binding == null) {
                        throw new IllegalArgumentException("产品授权引用了未登记账户："
                                + mandate.productOwnerId() + " -> " + accountId);
                    }
                    if (!binding.ownerId().equals(mandate.productOwnerId())) {
                        throw new IllegalArgumentException("产品授权账户不属于该产品："
                                + mandate.productOwnerId() + " -> " + accountId
                                + "（属主 " + binding.ownerId() + "）");
                    }
                    mandatedAccounts.add(accountId);
                }
            }

            List<PersonAccount> personalList = sorted(personAccounts,
                    Comparator.comparing(PersonAccount::accountId));
            Set<String> personalAccounts = new LinkedHashSet<>();
            for (PersonAccount personal : personalList) {
                if (!personalAccounts.add(personal.accountId())) {
                    throw new IllegalArgumentException("重复个人账户绑定：" + personal.accountId());
                }
                if (!accountIds.contains(personal.accountId())) {
                    throw new IllegalArgumentException("个人账户未登记账户引用：" + personal.accountId());
                }
                if (mandatedAccounts.contains(personal.accountId())) {
                    throw new IllegalArgumentException("产品授权账户不能走个人通道："
                            + personal.accountId());
                }
            }

            List<Appointment> appointmentList = sorted(appointments, Comparator.comparing(Appointment::id));
            Set<String> ids = new LinkedHashSet<>();
            for (Appointment appointment : appointmentList) {
                if (!ids.add(appointment.id())) {
                    throw new IllegalArgumentException("任职标识重复使用：" + appointment.id());
                }
            }
            for (int i = 0; i < appointmentList.size(); i++) {
                for (int j = i + 1; j < appointmentList.size(); j++) {
                    Appointment left = appointmentList.get(i);
                    Appointment right = appointmentList.get(j);
                    if (!overlap(left, right)) {
                        continue;
                    }
                    if (left.organizationPartyId().equals(right.organizationPartyId())
                            && left.role() == right.role()) {
                        throw new IllegalArgumentException("同机构同岗位任职区间重叠："
                                + left.id() + " / " + right.id());
                    }
                    if (left.personPartyId().equals(right.personPartyId())
                            && left.organizationPartyId().equals(right.organizationPartyId())) {
                        throw new IllegalArgumentException("同人物同机构不能兼任不同岗位："
                                + left.id() + " / " + right.id());
                    }
                }
            }

            accounts = List.copyOf(accountList);
            personAccounts = List.copyOf(personalList);
            mandates = List.copyOf(mandateList);
            appointments = List.copyOf(appointmentList);
        }
    }

    /**
     * 开局：从已登记开局清单解析账户引用与产品管理授权。
     *
     * <p>只读取现金开局账户的 {@code accountId} 与 {@code ownerId}，不复制也不保存现金余额。
     * 产品财产主体必须为 {@code PRODUCT}；管理组织必须是已登记 {@code ORGANIZATION}；
     * 每个授权账户必须存在且归该产品；每个产品首版只允许一个管理组织。</p>
     *
     * @param registry     开局登记（身份与财产主体种类）
     * @param cashOpenings 开局现金账户清单；只取账户与属主引用
     * @param mandates     产品管理授权
     * @return 初始授权状态（无任何任职）
     * @throws IllegalArgumentException 身份种类、账户归属或授权冲突
     */
    public static State open(OpeningRegistry registry, List<OpeningAccount> cashOpenings,
                             List<ProductMandate> mandates) {
        if (registry == null) {
            throw new IllegalArgumentException("开局登记不能为空");
        }
        List<OpeningAccount> openings = new ArrayList<>(
                Objects.requireNonNull(cashOpenings, "开局现金账户不能为空"));
        List<AccountBinding> bindings = new ArrayList<>();
        List<PersonAccount> personal = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OpeningAccount opening : openings) {
            if (!seen.add(opening.accountId())) {
                throw new IllegalArgumentException("重复开局现金账户：" + opening.accountId());
            }
            PropertyOwner owner = registry.owner(opening.ownerId());
            if (owner == null) {
                throw new IllegalArgumentException("开局账户属主未登记为财产主体："
                        + opening.accountId() + " -> " + opening.ownerId());
            }
            bindings.add(new AccountBinding(opening.accountId(), owner.ownerId()));
            if (owner.kind() == OwnerKind.PERSON) {
                personal.add(new PersonAccount(opening.accountId(), owner.partyId()));
            }
        }

        List<ProductMandate> requested = new ArrayList<>(
                Objects.requireNonNull(mandates, "产品管理授权不能为空"));
        for (ProductMandate mandate : requested) {
            Party organization = registryParty(registry, mandate.organizationPartyId());
            if (organization == null || organization.kind() != PartyKind.ORGANIZATION) {
                throw new IllegalArgumentException("管理组织未登记或不是组织："
                        + mandate.organizationPartyId());
            }
            PropertyOwner product = registry.owner(mandate.productOwnerId());
            if (product == null) {
                throw new IllegalArgumentException("产品财产主体未登记：" + mandate.productOwnerId());
            }
            if (product.kind() != OwnerKind.PRODUCT) {
                throw new IllegalArgumentException("产品财产主体必须是 PRODUCT，不能拿"
                        + product.kind() + " 冒充：" + mandate.productOwnerId());
            }
        }

        State state = new State(bindings, personal, requested, List.of());
        validateAgainst(registry, openings, state);
        return state;
    }

    /**
     * 恢复边界校验：把授权状态逐项对照冻结开局，拒绝被写入或篡改过的快照。
     *
     * <p><b>为什么需要</b>：{@link #fromJson(String)} 只保证状态内部一致，无法证明它来自开局；
     * 只信快照会把「产品账户绑给个人」「组织自有财产当产品」「未知人物任职」永久带进运行时。
     * 加载或每批动作前用同一 {@link OpeningRegistry} 调本方法重放，才能把 {@link State} 与开局重新对齐。</p>
     *
     * <p><b>精确口径</b>（全部为「等于」，不是「包含」）：</p>
     * <ul>
     *   <li>账户引用全集 = 开局账户全集：不多、不少、不换属主；</li>
     *   <li>个人账户绑定全集 = 开局中所有 {@code PERSON} 财产主体账户及其对应人物，多一个或少一个都拒绝；</li>
     *   <li>每条 mandate 的管理组织必须是已登记组织、产品主体必须是 {@code PRODUCT}、所列账户必须存在且属该产品；</li>
     *   <li>每条任职的人物必须是已登记人物、组织必须是已登记组织。</li>
     * </ul>
     *
     * <p><b>刻意不做的检查</b>：不要求每个产品都有 mandate（首版允许未授权产品存在）；不把同机构
     * 其余账户隐式补进 mandate；不复制期初余额——本方法只读 {@code accountId} 与 {@code ownerId}，
     * 状态里依旧没有任何金额。输入列表顺序不影响结论。</p>
     *
     * @param registry        冻结的开局登记（身份与财产主体种类）
     * @param openingAccounts 冻结的开局现金账户清单；只取账户与属主引用
     * @param state           待校验的授权状态
     * @throws IllegalArgumentException 任一不变量不成立；这是受信管理边界的失败，不是对外拒绝
     */
    public static void validateAgainst(OpeningRegistry registry, List<OpeningAccount> openingAccounts,
                                       State state) {
        if (registry == null) {
            throw new IllegalArgumentException("开局登记不能为空");
        }
        requireState(state);
        List<OpeningAccount> openings = new ArrayList<>(
                Objects.requireNonNull(openingAccounts, "开局现金账户不能为空"));

        Map<String, OpeningAccount> openingByAccount = new LinkedHashMap<>();
        for (OpeningAccount opening : openings) {
            if (openingByAccount.putIfAbsent(opening.accountId(), opening) != null) {
                throw new IllegalArgumentException("重复开局现金账户：" + opening.accountId());
            }
            PropertyOwner owner = registry.owner(opening.ownerId());
            if (owner == null) {
                throw new IllegalArgumentException("开局账户属主未登记为财产主体："
                        + opening.accountId() + " -> " + opening.ownerId());
            }
        }

        if (state.accounts().size() != openingByAccount.size()) {
            throw new IllegalArgumentException("账户引用与开局账户数量不一致：状态 "
                    + state.accounts().size() + "，开局 " + openingByAccount.size());
        }
        for (AccountBinding binding : state.accounts()) {
            OpeningAccount opening = openingByAccount.get(binding.accountId());
            if (opening == null) {
                throw new IllegalArgumentException("授权状态包含开局清单之外的账户："
                        + binding.accountId());
            }
            if (!opening.ownerId().equals(binding.ownerId())) {
                throw new IllegalArgumentException("账户属主与开局不一致：" + binding.accountId()
                        + " 状态 " + binding.ownerId() + "，开局 " + opening.ownerId());
            }
        }
        for (OpeningAccount opening : openings) {
            if (findBinding(state.accounts(), opening.accountId()) == null) {
                throw new IllegalArgumentException("授权状态缺少开局账户：" + opening.accountId());
            }
        }

        Map<String, String> expectedPersonal = new LinkedHashMap<>();
        for (OpeningAccount opening : openings) {
            PropertyOwner owner = registry.owner(opening.ownerId());
            if (owner.kind() == OwnerKind.PERSON) {
                expectedPersonal.put(opening.accountId(), owner.partyId());
            }
        }
        if (state.personAccounts().size() != expectedPersonal.size()) {
            throw new IllegalArgumentException("个人账户绑定与开局不一致：状态 "
                    + state.personAccounts().size() + " 条，开局应为 " + expectedPersonal.size() + " 条");
        }
        for (PersonAccount personal : state.personAccounts()) {
            String expectedPerson = expectedPersonal.get(personal.accountId());
            if (expectedPerson == null) {
                throw new IllegalArgumentException("个人通道绑定了非 PERSON 财产主体的账户："
                        + personal.accountId());
            }
            if (!expectedPerson.equals(personal.personPartyId())) {
                throw new IllegalArgumentException("个人账户绑定的人物与财产主体不一致："
                        + personal.accountId() + " 状态 " + personal.personPartyId()
                        + "，开局 " + expectedPerson);
            }
        }

        for (ProductMandate mandate : state.mandates()) {
            Party manager = registryParty(registry, mandate.organizationPartyId());
            if (manager == null || manager.kind() != PartyKind.ORGANIZATION) {
                throw new IllegalArgumentException("产品管理组织未登记或不是组织："
                        + mandate.organizationPartyId());
            }
            PropertyOwner product = registry.owner(mandate.productOwnerId());
            if (product == null) {
                throw new IllegalArgumentException("产品财产主体未登记：" + mandate.productOwnerId());
            }
            if (product.kind() != OwnerKind.PRODUCT) {
                throw new IllegalArgumentException("产品财产主体不是 PRODUCT，不能把"
                        + product.kind() + " 当产品授权：" + mandate.productOwnerId());
            }
            for (String accountId : mandate.accountIds()) {
                OpeningAccount opening = openingByAccount.get(accountId);
                if (opening == null) {
                    throw new IllegalArgumentException("产品授权账户不在开局清单：" + accountId);
                }
                if (!opening.ownerId().equals(mandate.productOwnerId())) {
                    throw new IllegalArgumentException("产品授权账户不属于该产品："
                            + mandate.productOwnerId() + " -> " + accountId
                            + "（开局属主 " + opening.ownerId() + "）");
                }
            }
        }

        for (Appointment appointment : state.appointments()) {
            Party person = registryParty(registry, appointment.personPartyId());
            if (person == null || person.kind() != PartyKind.PERSON) {
                throw new IllegalArgumentException("任职人物未登记或不是人物："
                        + appointment.id() + " -> " + appointment.personPartyId());
            }
            Party organization = registryParty(registry, appointment.organizationPartyId());
            if (organization == null || organization.kind() != PartyKind.ORGANIZATION) {
                throw new IllegalArgumentException("任职组织未登记或不是组织："
                        + appointment.id() + " -> " + appointment.organizationPartyId());
            }
        }
    }

    /**
     * 建立任职。这是受信管理夹具，不开放给任何角色自行扩权。
     *
     * <p>起点不得早于调用时刻 {@code now}（允许 {@code startsAt == now}），区间必须非空；
     * 人物必须登记为 {@code PERSON}，组织必须登记为 {@code ORGANIZATION}；任职标识历史唯一；
     * 同机构同岗位重叠、同人物同机构兼不同岗都被拒绝；无重叠的未来任职可以预排。</p>
     *
     * @param registry          开局登记（校验身份种类）
     * @param state             当前状态
     * @param appointmentId     任职标识
     * @param personPartyId     任职人物
     * @param organizationPartyId 任职组织
     * @param role              岗位
     * @param startsAt          起始时刻（含）
     * @param endsAt            预定结束时刻（不含）
     * @param now               调用时刻
     * @return 追加任职后的新状态
     * @throws IllegalArgumentException 身份、区间、ID 或重叠冲突
     */
    public static State appoint(OpeningRegistry registry, State state, String appointmentId,
                                String personPartyId, String organizationPartyId, Role role,
                                long startsAt, long endsAt, long now) {
        requireState(state);
        if (registry == null) {
            throw new IllegalArgumentException("开局登记不能为空");
        }
        requireText(appointmentId, "任职标识");
        requireText(personPartyId, "任职人物");
        requireText(organizationPartyId, "任职组织");
        if (role == null) {
            throw new IllegalArgumentException("任职岗位不能为空");
        }
        requireNonNegative(now, "调用时刻");
        if (startsAt < now) {
            throw new IllegalArgumentException("任职起点不得早于调用时刻：" + startsAt + " < " + now);
        }
        Appointment candidate = new Appointment(appointmentId, personPartyId, organizationPartyId,
                role, startsAt, endsAt, null);

        Party person = registryParty(registry, personPartyId);
        if (person == null || person.kind() != PartyKind.PERSON) {
            throw new IllegalArgumentException("任职人物未登记或不是人物：" + personPartyId);
        }
        Party organization = registryParty(registry, organizationPartyId);
        if (organization == null || organization.kind() != PartyKind.ORGANIZATION) {
            throw new IllegalArgumentException("任职组织未登记或不是组织：" + organizationPartyId);
        }
        for (Appointment existing : state.appointments()) {
            if (existing.id().equals(appointmentId)) {
                throw new IllegalArgumentException("任职标识已使用，不得复用：" + appointmentId);
            }
            if (!overlap(candidate, existing)) {
                continue;
            }
            if (existing.organizationPartyId().equals(organizationPartyId) && existing.role() == role) {
                throw new IllegalArgumentException("同机构同岗位任职区间重叠："
                        + appointmentId + " 与 " + existing.id());
            }
            if (existing.personPartyId().equals(personPartyId)
                    && existing.organizationPartyId().equals(organizationPartyId)) {
                throw new IllegalArgumentException("同人物同机构不能兼任不同岗位："
                        + appointmentId + " 与 " + existing.id());
            }
        }

        List<Appointment> updated = new ArrayList<>(state.appointments());
        updated.add(candidate);
        return new State(state.accounts(), state.personAccounts(), state.mandates(), updated);
    }

    /**
     * 撤销任职：记录撤销时刻并立即失去新访问，不删除记录、不覆盖原期限与身份字段。
     *
     * <p>撤销时刻不得早于任职开始（不允许提前伪造离任），也不得晚于或等于预定结束
     * （已到期任职不再撤销，直接按到期失效处理）；重复撤销与未知标识明确拒绝。</p>
     *
     * @param state         当前状态
     * @param appointmentId 任职标识
     * @param now           撤销时刻
     * @return 记录撤销时刻后的新状态
     * @throws IllegalArgumentException 未知、已撤销或已到期
     */
    public static State revoke(State state, String appointmentId, long now) {
        requireState(state);
        requireText(appointmentId, "任职标识");
        requireNonNegative(now, "撤销时刻");
        List<Appointment> updated = new ArrayList<>(state.appointments().size());
        boolean found = false;
        for (Appointment appointment : state.appointments()) {
            if (!appointment.id().equals(appointmentId)) {
                updated.add(appointment);
                continue;
            }
            if (appointment.revokedAt() != null) {
                throw new IllegalArgumentException("任职已撤销，不能重复撤销：" + appointmentId);
            }
            if (now < appointment.startsAt()) {
                throw new IllegalArgumentException("撤销时刻不能早于任职开始：" + appointmentId);
            }
            if (now >= appointment.endsAt()) {
                throw new IllegalArgumentException("任职已到期，不能撤销：" + appointmentId);
            }
            updated.add(new Appointment(appointment.id(), appointment.personPartyId(),
                    appointment.organizationPartyId(), appointment.role(),
                    appointment.startsAt(), appointment.endsAt(), now));
            found = true;
        }
        if (!found) {
            throw new IllegalArgumentException("未登记的任职标识：" + appointmentId);
        }
        return new State(state.accounts(), state.personAccounts(), state.mandates(), updated);
    }

    /**
     * 纯判定：某人物在 {@code now} 能否以某组织的身份对指定范围执行某动作。
     *
     * <p>判定顺序：范围与动作匹配 → 该组织内有有效任职 → 岗位允许该动作 → 产品/账户在
     * 本组织的管理授权内且账户归该产品。四岗都可读本组织获授权的产品账户与组织档案；
     * 经理不能下单，研究员不能改目标，风控不能越过程序硬限制，交易员不能改投资方向。
     * 一切语义拒绝返回 {@link Decision#NOT_AUTHORIZED}，不区分原因。</p>
     *
     * @param state            当前授权状态
     * @param personPartyId    可信人物标识
     * @param action           待判定动作
     * @param target           判定范围
     * @param now              判定时刻，不可为负
     * @return 许可或拒绝
     * @throws IllegalArgumentException 参数为空或时刻为负
     */
    public static Decision authorize(State state, String personPartyId, Action action,
                                     Target target, long now) {
        requireState(state);
        requireText(personPartyId, "人物标识");
        Objects.requireNonNull(action, "动作");
        Objects.requireNonNull(target, "判定范围");
        requireNonNegative(now, "判定时刻");

        if (!scopeMatches(action, target)) {
            return Decision.NOT_AUTHORIZED;
        }
        Appointment active = activeAppointment(state, personPartyId, target.organizationPartyId(), now);
        if (active == null || !rolePermits(active.role(), action)) {
            return Decision.NOT_AUTHORIZED;
        }
        if (isAccountAction(action)) {
            ProductMandate mandate = mandate(state, target.organizationPartyId(), target.productOwnerId());
            if (mandate == null || !mandate.accountIds().contains(target.accountId())) {
                return Decision.NOT_AUTHORIZED;
            }
            AccountBinding binding = findBinding(state.accounts(), target.accountId());
            if (binding == null || !binding.ownerId().equals(mandate.productOwnerId())) {
                return Decision.NOT_AUTHORIZED;
            }
            return Decision.ALLOW;
        }
        if (isProductAction(action)) {
            return mandate(state, target.organizationPartyId(), target.productOwnerId()) == null
                    ? Decision.NOT_AUTHORIZED : Decision.ALLOW;
        }
        return Decision.ALLOW;
    }

    /**
     * 个人账户查看判定：只允许该账户绑定的本人查看。
     *
     * <p>与任职无关：个人账户不因受雇于某机构而开放给雇主或其他同事，产品账户也不能
     * 通过本方法绕过角色授权。</p>
     *
     * @param state         当前状态
     * @param personPartyId 可信人物标识
     * @param accountId     个人账户标识
     * @return 许可或拒绝；未知账户与无权账户同为拒绝
     * @throws IllegalArgumentException 参数为空
     */
    public static Decision canReadPersonalAccount(State state, String personPartyId, String accountId) {
        requireState(state);
        requireText(personPartyId, "人物标识");
        requireText(accountId, "账户标识");
        for (PersonAccount personal : state.personAccounts()) {
            if (personal.accountId().equals(accountId) && personal.personPartyId().equals(personPartyId)) {
                return Decision.ALLOW;
            }
        }
        return Decision.NOT_AUTHORIZED;
    }

    /**
     * 序列化授权状态；集合顺序在构造期已固定，因此同一状态输出稳定。
     *
     * @param state 授权状态
     * @return JSON 文本
     * @throws IllegalArgumentException 状态为空
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("授权状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原授权状态；构造期校验仍生效，篡改后的重叠任职、非法账户引用会被拒绝。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException JSON 为空或内容不满足不变量
     */
    public static State fromJson(String json) {
        requireText(json, "授权状态 JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("授权状态 JSON 无法还原", failure);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static boolean isAccountAction(Action action) {
        return action == Action.READ_PRODUCT_ACCOUNT
                || action == Action.EXECUTE_ORDER
                || action == Action.CANCEL_ORDER;
    }

    private static boolean isProductAction(Action action) {
        return action == Action.SET_PORTFOLIO_TARGET || action == Action.ISSUE_RISK_HALT;
    }

    private static boolean scopeMatches(Action action, Target target) {
        if (isAccountAction(action)) {
            return target.accountId() != null;
        }
        if (isProductAction(action)) {
            return target.productOwnerId() != null && target.accountId() == null;
        }
        return target.productOwnerId() == null && target.accountId() == null;
    }

    private static boolean rolePermits(Role role, Action action) {
        switch (role) {
            case RESEARCHER:
                return action == Action.READ_PRODUCT_ACCOUNT
                        || action == Action.READ_ORGANIZATION_FILE
                        || action == Action.WRITE_RESEARCH;
            case PORTFOLIO_MANAGER:
                return action == Action.READ_PRODUCT_ACCOUNT
                        || action == Action.READ_ORGANIZATION_FILE
                        || action == Action.SET_PORTFOLIO_TARGET;
            case RISK_OFFICER:
                return action == Action.READ_PRODUCT_ACCOUNT
                        || action == Action.READ_ORGANIZATION_FILE
                        || action == Action.ISSUE_RISK_HALT;
            case EXECUTION_TRADER:
                return action == Action.READ_PRODUCT_ACCOUNT
                        || action == Action.READ_ORGANIZATION_FILE
                        || action == Action.EXECUTE_ORDER
                        || action == Action.CANCEL_ORDER;
            case JOURNALIST:
                return action == Action.READ_ORGANIZATION_FILE
                        || action == Action.WRITE_STORY;
            case EDITOR:
                return action == Action.READ_ORGANIZATION_FILE
                        || action == Action.REVIEW_STORY
                        || action == Action.PUBLISH_STORY;
            default:
                return false;
        }
    }

    private static Appointment activeAppointment(State state, String personPartyId,
                                                 String organizationPartyId, long now) {
        Appointment found = null;
        for (Appointment appointment : state.appointments()) {
            if (!appointment.personPartyId().equals(personPartyId)
                    || !appointment.organizationPartyId().equals(organizationPartyId)
                    || !appointment.activeAt(now)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = appointment;
        }
        return found;
    }

    private static ProductMandate mandate(State state, String organizationPartyId, String productOwnerId) {
        for (ProductMandate mandate : state.mandates()) {
            if (mandate.organizationPartyId().equals(organizationPartyId)
                    && mandate.productOwnerId().equals(productOwnerId)) {
                return mandate;
            }
        }
        return null;
    }

    private static AccountBinding findBinding(List<AccountBinding> bindings, String accountId) {
        for (AccountBinding binding : bindings) {
            if (binding.accountId().equals(accountId)) {
                return binding;
            }
        }
        return null;
    }

    private static Party registryParty(OpeningRegistry registry, String partyId) {
        for (Party party : registry.parties()) {
            if (party.partyId().equals(partyId)) {
                return party;
            }
        }
        return null;
    }

    private static boolean overlap(Appointment left, Appointment right) {
        return left.startsAt() < right.effectiveEnd() && right.startsAt() < left.effectiveEnd();
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> order) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source, "集合不能为空"));
        copy.sort(order);
        return copy;
    }

    private static void requireState(State state) {
        if (state == null) {
            throw new IllegalArgumentException("授权状态不能为空");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + "不能为负：" + value);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
