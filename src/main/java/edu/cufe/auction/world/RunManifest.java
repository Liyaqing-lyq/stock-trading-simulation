package edu.cufe.auction.world;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 扩展世界的运行清单：把运行、规则、模块、资料与模型版本在开局第一个提交里固定下来。
 *
 * <p>当前启用八种明确组合：纯现金、现金 + 开局登记、现金 + 开局登记 + 证券清算夹具、
 * 现金 + 开局登记 + 证券台账 + 世界订单撮合、现金 + 登记 + 证券 + 撮合 + 制造经营、
 * 现金 + 登记 + 证券 + 撮合 + 制造 + 董事会治理、在此基础上再加任职与资料阅读的
 * {@code informed-stock-company-experiment-v1}，以及再加四岗交易工作流的
 * {@code role-trading-experiment-v1}。模块表与规则档案一一对应（现金
 * {@code {cash:1, scheduler:1}}、登记再加 {@code registry:1}、证券再加 {@code securities:1}、
 * 撮合再加 {@code matching:1}、制造再加 {@code manufacturing:1}、治理再加 {@code governance:1}、
 * 任职与阅读再加 {@code roles:1, information:1}、四岗交易再加 {@code trading:1}），
 * 模型版本必须是 {@link #MODEL_NONE}。任何未启用的能力在构造期就被拒绝，避免运行到一半才发现
 * 缺少实现。</p>
 *
 * @param runId              运行标识；同一日志文件只能对应同一运行
 * @param seed               调度公平顺序种子
 * @param rulesVersion       规则档案版本
 * @param moduleVersions     已启用模块及版本
 * @param dataVersions       资料版本表；现金子集为空表（诚实标空，不填假值）
 * @param modelConfigVersion 模型配置版本；现金子集为 {@link #MODEL_NONE}
 */
public record RunManifest(String runId, long seed, String rulesVersion,
                          Map<String, Integer> moduleVersions,
                          Map<String, String> dataVersions,
                          String modelConfigVersion) {

    /** 现金子集规则档案版本（旧组合，保持完全兼容）。 */
    public static final String RULES_CASH_WORLD_V1 = "cash-world-v1";

    /** 现金 + 开局登记规则档案版本（M2.2 新增的明确组合）。 */
    public static final String RULES_CASH_REGISTRY_WORLD_V1 = "cash-registry-world-v1";

    /** 现金 + 开局登记 + 证券清算夹具（M2.3 新增的明确组合，仍不含撮合与角色交易授权）。 */
    public static final String RULES_SECURITIES_CLEARING_EXPERIMENT_V1 =
            "securities-clearing-experiment-v1";

    /** 现金 + 开局登记 + 证券台账 + 世界订单撮合（M2.4b 新增的明确组合）。 */
    public static final String RULES_SECURITIES_MATCHING_EXPERIMENT_V1 =
            "securities-matching-experiment-v1";

    /** 现金 + 登记 + 证券 + 撮合 + 制造经营（M3a.2 新增的明确组合，model=none）。 */
    public static final String RULES_STOCK_COMPANY_EXPERIMENT_V1 = "stock-company-experiment-v1";

    /** 现金 + 登记 + 证券 + 撮合 + 制造 + 治理（M3b.2 新增的明确组合，model=none）。 */
    public static final String RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1 =
            "governed-stock-company-experiment-v1";

    /** 在治理档案之上再加任职授权与资料阅读（M4a.2/M4b.2 新增的明确组合，model=none）。 */
    public static final String RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1 =
            "informed-stock-company-experiment-v1";

    /** 在任职与阅读档案之上再加四岗交易工作流（M4c 新增的明确组合，model=none）。 */
    public static final String RULES_ROLE_TRADING_EXPERIMENT_V1 = "role-trading-experiment-v1";

    /** 在四岗交易档案之上再加决定屏障与尝试（M4d 新增的明确组合，scripted 模型）。 */
    public static final String RULES_DECISION_EXPERIMENT_V1 = "decision-experiment-v1";

    /**
     * 命令意图档案：在群组平台全链之上启用 {@code command-intent} 模块的明确组合。
     *
     * <p>先用最需要的一条闭：制造排程把种类写进命令正文，因此新档可以鉴别 PURCHASE 与
     * PRODUCTION 这类同形动作；其余外部排程入口的身份字段（taskId/到期/工时）在其域命令里
     * 已有或已在既有校验中绑定，见 {@code contract-command-intent.md}。旧档继续原格式，
     * 缺失的身份信息不伪造。</p>
     */
    public static final String RULES_COMMAND_INTENT_EXPERIMENT_V1 =
            "command-intent-experiment-v1";

    /** 在决定档案之上再加现金分红世界桥（M3d D3b 新增的明确组合，scripted 模型）。 */
    public static final String RULES_DIVIDEND_WORLD_EXPERIMENT_V1 = "dividend-world-experiment-v1";

    /** B2b：在分红档案之上接入真实模型持久派发（schema10，live 模型配置）。 */
    public static final String RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1 =
            "live-decision-world-experiment-v1";

    /** M4f：在 live 决定档案之上接入已有经历的有界回忆（schema11，仍为 live 模型配置）。 */
    public static final String RULES_COGNITION_RECALL_EXPERIMENT_V1 =
            "cognition-recall-experiment-v1";

    /** M4g.2：在回忆档案之上接入主观信念、来源可靠性与慢习惯（schema12，仍为 live 模型配置）。 */
    public static final String RULES_COGNITION_BELIEF_EXPERIMENT_V1 =
            "cognition-belief-experiment-v1";

    /** M4h.2：在信念档案之上接入双人采编运行桥与组织工时预算（schema13）。 */
    public static final String RULES_MEDIA_EDITORIAL_EXPERIMENT_V1 =
            "media-editorial-experiment-v1";

    /** M4i.2a：在媒体档案之上接入双边采访预约协调运行桥（schema14）。 */
    public static final String RULES_INTERVIEW_COORDINATION_EXPERIMENT_V1 =
            "interview-coordination-experiment-v1";

    /** schema15：在采访预约之上启用逐轮访谈与真实阅读。 */

    /** schema16：在逐轮访谈之上启用匿名私有证据与身份共享。 */
    public static final String RULES_MEDIA_EVIDENCE_EXPERIMENT_V1 =
            "media-evidence-experiment-v1";
    /** schema17：在证据档之上启用一对一消息（社交）。 */
    public static final String RULES_SOCIAL_EXPERIMENT_V1 = "social-messaging-experiment-v1";
    /** schema18：在社交全链之上接入按公司的工程经营与董事会（不迁移旧档）。 */
    public static final String RULES_ENGINEERING_WORLD_EXPERIMENT_V1 =
            "engineering-world-experiment-v1";
    /** schema20：在工程全链之上接入群组、平台发布与呈现（不迁移旧档）。 */
    public static final String RULES_GROUPS_PLATFORM_EXPERIMENT_V1 = "groups-platform-experiment-v1";
    public static final String RULES_INTERVIEW_DIALOGUE_EXPERIMENT_V1 =
            "interview-dialogue-experiment-v1";

    /** schema19：在登记档案之上启用显式银行与托管模块（B5 真实开局/恢复，EXP）。 */
    public static final String RULES_BANKING_WORLD_EXPERIMENT_V1 = "banking-world-experiment-v1";

    /**
     * schema19：在银行与托管之上再加真实任职与资料阅读（B6 银行人物动作，EXP）。
     *
     * <p>版本号仍是 19：角色语义由<b>模块集合</b>冻结，不由 schema 推断；因此同一 schema 下
     * 只有该档案才允许出现银行人物动作、贷款台账与 BEGIN/FINISH 任务。</p>
     */
    public static final String RULES_BANKING_ROLES_EXPERIMENT_V1 = "banking-roles-experiment-v1";

    /**
     * schema19：在银行角色档之上把证券订单的资金腿接到现金/存款桶（B7a 资金桶结算，EXP）。
     *
     * <p>能力由<b>模块集合</b>显式冻结：{@code cash, scheduler, registry, roles, information,
     * banking, custody, securities, matching, funding}，各 1。{@code funding} 与 {@code groups}
     * 互不隐含；本档不含 {@code manufacturing/governance/trading}，因此不声称经营付款、四岗
     * 交易或群平台能力。schema 仍是 19（唯一由 {@code banking} 模块决定）。</p>
     */
    public static final String RULES_BANKING_FUNDS_EXPERIMENT_V1 = "banking-funds-experiment-v1";

    /**
     * schema24：群组 20 档全部模块与银行三模块的并集组合档（EXP，不迁移旧档）。
     *
     * <p>本档案是<b>一次定版</b>的组合：模块表 = 群组 20 档 23 个模块 + {@code banking/custody/
     * funding}（共 26）。托管<b>模块</b>存在不等于托管<b>成交能力</b>启用：本档不开 CUSTODY
     * 资金桶、不实现 B7b 客户受益权成交。任何加模块都必须新开组合版本，不得在本档追加。</p>
     */
    public static final String RULES_COMPOSED_BANK_WORLD_V1 = "composed-v1";

    /**
     * schema24：组合档加上 {@code command-intent} 模块（27 模块）的显式新版本。
     *
     * <p>按组合合同「加模块必须新开版本」的约定，本档不是往 {@code composed-v1} 追加：
     * {@code composed-v1} 的 26 模块表逐字不变，旧运行仍按原契约读取。本档在同样的银行 + 群组
     * 世界里把外部排程命令正文升级为完整原意信封（命令/待办/种类/阶段/到期/工时），恢复时按
     * 原命令重演真实排程入口，不再由当前待办反推时点。</p>
     */
    public static final String RULES_COMPOSED_BANK_WORLD_V2 = "composed-v2";

    /**
     * schema24：组合档再加 {@code custody-fill} 模块（{@code composed-v2} 原集合 + 1）的显式新版本。
     *
     * <p>客户托管受益权成交（B7b）由<b>显式模块</b> {@code custody-fill} 冻结，不由
     * {@code custody} 模块、schema 号或档案名反推。{@code composed-v1} 与 {@code composed-v2}
     * 的模块表、旧 JSON 与“拒绝 CUSTODY 资金桶”的行为逐字不变：它们的档案里可能已有被拒绝的
     * CUSTODY 订单或付款尝试，因此不能把“以前不会有成功托管订单”当作重演不变的依据。</p>
     *
     * <p>本档继承 {@code composed-v2} 的完整原意（命令意图信封、默认周期治理、规范序列化与
     * 分支子世界身份）；区别只有真实托管三账可用于订单资金腿。schema 结构不变，不重编号。</p>
     */
    public static final String RULES_COMPOSED_BANK_WORLD_V3 = "composed-v3";

    /**
     * schema19：在 B7a 资金桶档之上把<b>客户托管受益权</b>接入证券成交（B7b，EXP）。
     *
     * <p>模块集合 = B7a 十项 + {@code custody-fill}，各 1。{@code custody-fill} 只表示
     * “订单/付款可以用 {@code CUSTODY|托管人|客户} 桶结算”，客户明细仍是
     * {@link edu.cufe.auction.world.bank.CustodyBook} 一份，随 {@code BankRun.custodyJson}
     * 同批提交。它不打开经营付款、四岗交易、治理或群平台；schema 仍由 {@code banking}
     * 模块决定为 19。</p>
     */
    public static final String RULES_BANKING_CUSTODY_EXPERIMENT_V1 =
            "banking-custody-experiment-v1";

    /** live 决定档案唯一允许的模型配置版本；与真实 HTTP 提供方严格成对。 */
    public static final String LIVE_DECISIONS_V1 = "live-decisions-v1";

    /** 未接入模型时使用的模型配置版本。 */
    public static final String MODEL_NONE = "none";

    /** 决定档案唯一允许的模型配置版本：具体 scripted 提供方，不接网络。 */
    public static final String SCRIPTED_DECISIONS_V1 = "scripted-decisions-v1";

    /**
     * 是否启用开局登记模块。
     *
     * @return 模块表包含 registry 时返回 true
     */
    public boolean hasRegistry() {
        return moduleVersions.containsKey("registry");
    }

    /**
     * 是否启用证券清算夹具模块。
     *
     * @return 模块表包含 securities 时返回 true
     */
    public boolean hasSecurities() {
        return moduleVersions.containsKey("securities");
    }

    /**
     * 是否启用世界订单撮合模块。
     *
     * @return 模块表包含 matching 时返回 true
     */
    public boolean hasMatching() {
        return moduleVersions.containsKey("matching");
    }

    /**
     * 是否启用制造经营模块。
     *
     * @return 模块表包含 manufacturing 时返回 true
     */
    public boolean hasManufacturing() {
        return moduleVersions.containsKey("manufacturing");
    }

    /**
     * 是否启用董事会治理模块。
     *
     * @return 模块表包含 governance 时返回 true
     */
    public boolean hasGovernance() {
        return moduleVersions.containsKey("governance");
    }

    /**
     * 是否启用任职授权模块。
     *
     * @return 模块表包含 roles 时返回 true
     */
    public boolean hasRoles() {
        return moduleVersions.containsKey("roles");
    }

    /**
     * 是否启用资料阅读模块。
     *
     * @return 模块表包含 information 时返回 true
     */
    public boolean hasInformation() {
        return moduleVersions.containsKey("information");
    }

    /**
     * 是否启用四岗交易模块。
     *
     * @return 模块表包含 trading 时返回 true
     */
    public boolean hasTrading() {
        return moduleVersions.containsKey("trading");
    }

    /**
     * 是否启用决定屏障与尝试模块。
     *
     * @return 模块表包含 decisions 时返回 true
     */
    public boolean hasDecisions() {
        return moduleVersions.containsKey("decisions");
    }

    /**
     * 是否启用现金分红世界桥模块。
     *
     * @return 模块表包含 dividend 时返回 true
     */
    public boolean hasDividend() {
        return moduleVersions.containsKey("dividend");
    }

    /**
     * 是否启用真实模型持久派发。
     *
     * @return 模块表包含 live-decisions 时返回 true
     */
    public boolean hasLiveDecisions() {
        return moduleVersions.containsKey("live-decisions");
    }

    /**
     * 是否启用已有经历的有界回忆。
     *
     * @return 模块表包含 cognition-recall 时返回 true
     */
    public boolean hasCognitionRecall() {
        return moduleVersions.containsKey("cognition-recall");
    }

    /**
     * 是否启用主观信念与慢习惯子集。
     *
     * @return 模块表包含 belief 时返回 true
     */
    public boolean hasBelief() {
        return moduleVersions.containsKey("belief");
    }

    /**
     * 是否启用媒体采编运行桥子集。
     *
     * @return 模块表包含 media 时返回 true
     */
    public boolean hasMedia() {
        return moduleVersions.containsKey("media");
    }

    /**
     * 是否启用双边采访预约协调桥子集（schema14）。
     *
     * <p>只有在该模块存在时才允许出现采访子集状态、采访任务种类与采访角色工具；旧 1–13 档案
     * 永远返回 false，因此不会因为新种类进入旧校验路径。</p>
     *
     * @return 模块表包含 interview 时返回 true
     */
    public boolean hasInterviews() {
        return moduleVersions.containsKey("interview");
    }

    /**
     * 是否启用匿名私有证据子集（schema16）。
     *
     * <p>只有该模块存在时才允许出现证据状态、共享任务与 v4 工具合同；旧 1–15 档案永远返回 false。</p>
     *
     * @return 模块表包含 evidence 时返回 true
     */
    public boolean hasEvidence() {
        return moduleVersions.containsKey("evidence");
    }

    /**
     * 是否启用一对一消息（社交）子集（schema17）。
     *
     * <p>只有该模块存在时才允许出现社交状态、社交任务与 v5 工具合同；旧 ≤16 档案永远返回 false。</p>
     *
     * @return 模块表包含 social 时返回 true
     */
    public boolean hasSocial() {
        return moduleVersions.containsKey("social");
    }

    /**
     * 是否启用 schema18 按公司的工程经营与董事会子集。
     *
     * <p>只有该模块存在时才允许出现工程状态、工程董事会与 schema18 开局键；旧 ≤17 档案永远返回
     * false，因此旧校验路径与旧档字节不受影响。</p>
     *
     * @return 模块表包含 engineering 时返回 true
     */
    public boolean hasEngineering() {
        return moduleVersions.containsKey("engineering");
    }

    /** @return 是否启用 schema15 逐轮访谈 */
    public boolean hasDialogue() {
        return moduleVersions.containsKey("dialogue");
    }

    /**
     * 是否启用 schema20 群组模块。
     *
     * <p>只认冻结模块表，不按版本号推断；群组与平台发布必须同时启用，另一模块缺失时构造期拒绝。
     * 旧 ≤19 档案永远返回 false，因此旧校验路径与旧档字节不受影响。</p>
     *
     * @return 模块表包含 groups 时返回 true
     */
    public boolean hasGroups() {
        return moduleVersions.containsKey("groups");
    }

    /**
     * 是否启用命令意图模块（新规则档）。
     *
     * <p>启用后制造命令正文必须携带种类，恢复时按原命令种类重演；旧档没有该模块，
     * 继续按原格式读取，同形歧义作为已知旧契约限制保留。</p>
     *
     * @return 模块表包含 {@code command-intent} 时返回 true
     */
    public boolean hasCommandIntent() {
        return moduleVersions.containsKey("command-intent");
    }

    /**
     * 是否启用 schema20 平台发布模块。
     *
     * <p>与 {@link #hasGroups()} 严格成对；不因为档案版本更高就假设银行（19）或群组存在。</p>
     *
     * @return 模块表包含 publication 时返回 true
     */
    public boolean hasPublication() {
        return moduleVersions.containsKey("publication");
    }

    /**
     * 是否启用银行账与贷款台账模块。
     *
     * @return 模块表包含 banking 时返回 true
     */
    public boolean hasBank() {
        return moduleVersions.containsKey("banking");
    }

    /**
     * 是否启用真实任职的银行人物动作。
     *
     * <p>资金桶档与组合档都是“银行角色 + 证券/撮合/资金模块”，因此具备银行人物动作入口
     * （存款/取现/支付/借贷仍受真实任职与主体约束）。只看规则档案名，不看 schema：同是 19 的
     * {@code banking-world-experiment-v1} 没有任职，必须返回 false。</p>
     *
     * @return 规则档案是银行角色实验档、B7a 资金桶档或组合档时返回 true
     */
    public boolean hasBankRoles() {
        return RULES_BANKING_ROLES_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_BANKING_FUNDS_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_BANKING_CUSTODY_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V1.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion);
    }

    /**
     * 是否启用托管明细模块。
     *
     * <p>本档可以存在托管<b>模块</b>，但客户受益权成交能力未启用；见 {@link #hasFunding()} 的说明。</p>
     *
     * @return 模块表包含 custody 时返回 true
     */
    public boolean hasCustody() {
        return moduleVersions.containsKey("custody");
    }

    /**
     * 是否启用组合档（{@code composed-v1} 与带意图信封的 {@code composed-v2}）。
     *
     * @return 规则档案为任一组合档时返回 true
     */
    public boolean hasComposedBankWorld() {
        return RULES_COMPOSED_BANK_WORLD_V1.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion);
    }

    /**
     * 是否启用「默认定期治理」：额外治理公司开局自动排第 1 期期末采样，之后由实际采样任务派生。
     *
     * <p>只对 {@code composed-v2} 为真：{@code composed-v1} 与更旧档案的行为、JSON 与待办集合
     * 一律不变，不因为升级组合版本就给旧运行补任务。</p>
     *
     * @return 规则档案是带意图信封的组合档时返回 true
     */
    public boolean hasDefaultPeriodicGovernance() {
        return RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion);
    }

    /**
     * 是否启用额外公司治理容器（目前仅组合档的 north）。
     *
     * <p>只认冻结模块表，不由档案名或 schema 推断；旧档案一律 false，状态里也不会出现该组件。</p>
     *
     * @return 模块表包含 company-governance 时返回 true
     */
    public boolean hasCompanyGovernance() {
        return moduleVersions.containsKey("company-governance");
    }

    /**
     * 是否启用证券订单的资金桶结算模块（B7a）。
     *
     * <p>只看模块表，不由规则档案名或 schema 反推：旧 1–19 档一律返回 false，
     * 因此旧档订单不会进入资金桶校验/结算路径；{@code groups} 与 {@code funding} 也互不隐含。</p>
     *
     * @return 模块表包含 funding 时返回 true
     */
    public boolean hasFunding() {
        return moduleVersions.containsKey("funding");
    }

    /**
     * 是否启用<b>客户托管受益权成交</b>模块（B7b）。
     *
     * <p>只看模块表，不由规则档案名、schema 或 {@link #hasCustody()} 反推。
     * {@code hasCustody()} 在 B5/B6/B7a/组合档已为 true（那是“托管明细账存在”），
     * 本方法只认 B7b 的 {@code custody-fill}：旧档因此继续拒绝 {@code CUSTODY} 资金桶，
     * 行为逐字不变。{@code composed-v3} 是显式把该模块加入的新组合；{@code composed-v1/v2}
     * 的模块表里没有它，继续按旧契约拒绝。</p>
     *
     * @return 模块表包含 custody-fill 时返回 true
     */
    public boolean hasCustodyFill() {
        return moduleVersions.containsKey("custody-fill");
    }

    /** 校验并冻结清单；不可变、可稳定序列化。 */
    public RunManifest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("运行标识不能为空");
        }
        if (!RULES_CASH_WORLD_V1.equals(rulesVersion)
                && !RULES_CASH_REGISTRY_WORLD_V1.equals(rulesVersion)
                && !RULES_SECURITIES_CLEARING_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_SECURITIES_MATCHING_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_ROLE_TRADING_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_DIVIDEND_WORLD_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_COGNITION_RECALL_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_COGNITION_BELIEF_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_MEDIA_EDITORIAL_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_INTERVIEW_COORDINATION_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_INTERVIEW_DIALOGUE_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_MEDIA_EVIDENCE_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_SOCIAL_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_BANKING_WORLD_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_BANKING_ROLES_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_BANKING_FUNDS_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_COMPOSED_BANK_WORLD_V1.equals(rulesVersion)
                && !RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                && !RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion)
                && !RULES_BANKING_CUSTODY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_ENGINEERING_WORLD_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_GROUPS_PLATFORM_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_COMMAND_INTENT_EXPERIMENT_V1.equals(rulesVersion)) {
            throw new IllegalArgumentException("当前只支持规则档案 " + RULES_CASH_WORLD_V1
                    + "、" + RULES_CASH_REGISTRY_WORLD_V1 + "、"
                    + RULES_SECURITIES_CLEARING_EXPERIMENT_V1 + "、"
                    + RULES_SECURITIES_MATCHING_EXPERIMENT_V1 + "、"
                    + RULES_STOCK_COMPANY_EXPERIMENT_V1 + "、"
                    + RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1 + "、"
                    + RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1 + "、"
                    + RULES_ROLE_TRADING_EXPERIMENT_V1 + " 或 "
                    + RULES_DECISION_EXPERIMENT_V1 + "，收到 " + rulesVersion);
        }
        Map<String, Integer> modules = new TreeMap<>();
        if (moduleVersions != null) {
            moduleVersions.forEach((name, version) -> {
                if (name == null || name.isBlank() || version == null || version <= 0) {
                    throw new IllegalArgumentException("模块名或版本非法：" + name + "=" + version);
                }
                modules.put(name, version);
            });
        }
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("cash", 1);
        expected.put("scheduler", 1);
        boolean withIntent = RULES_COMMAND_INTENT_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion);
        boolean withBankCustody = RULES_BANKING_CUSTODY_EXPERIMENT_V1.equals(rulesVersion);
        boolean withFunds = RULES_BANKING_FUNDS_EXPERIMENT_V1.equals(rulesVersion)
                || withBankCustody
                || RULES_COMPOSED_BANK_WORLD_V1.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion);
        boolean withBankWorld = RULES_BANKING_WORLD_EXPERIMENT_V1.equals(rulesVersion);
        boolean withBankRoles = RULES_BANKING_ROLES_EXPERIMENT_V1.equals(rulesVersion);
        boolean withComposed = RULES_COMPOSED_BANK_WORLD_V1.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V2.equals(rulesVersion)
                || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion);
        boolean withGroups = withComposed || withIntent
                || RULES_GROUPS_PLATFORM_EXPERIMENT_V1.equals(rulesVersion);
        boolean withEngineering = withGroups
                || RULES_ENGINEERING_WORLD_EXPERIMENT_V1.equals(rulesVersion);
        boolean withSocial = withEngineering || RULES_SOCIAL_EXPERIMENT_V1.equals(rulesVersion);
        boolean withEvidence = withSocial
                || RULES_MEDIA_EVIDENCE_EXPERIMENT_V1.equals(rulesVersion);
        boolean withDialogue = withEvidence
                || RULES_INTERVIEW_DIALOGUE_EXPERIMENT_V1.equals(rulesVersion);
        boolean withInterview = withDialogue
                || RULES_INTERVIEW_COORDINATION_EXPERIMENT_V1.equals(rulesVersion);
        boolean withMedia = withInterview
                || RULES_MEDIA_EDITORIAL_EXPERIMENT_V1.equals(rulesVersion);
        boolean withBelief = withMedia || RULES_COGNITION_BELIEF_EXPERIMENT_V1.equals(rulesVersion);
        boolean withCognition = withBelief
                || RULES_COGNITION_RECALL_EXPERIMENT_V1.equals(rulesVersion);
        boolean withLive = withCognition
                || RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1.equals(rulesVersion);
        boolean withDividend = withLive
                || RULES_DIVIDEND_WORLD_EXPERIMENT_V1.equals(rulesVersion);
        boolean withTrading = withLive
                || RULES_ROLE_TRADING_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion)
                || withDividend;
        boolean withRoles = withLive
                || RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                || withTrading;
        boolean withGovernance = withLive
                || RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                || withRoles;
        boolean withManufacturing = withLive
                || RULES_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                || withGovernance;
        boolean withMatching = withLive
                || RULES_SECURITIES_MATCHING_EXPERIMENT_V1.equals(rulesVersion)
                || withManufacturing;
        boolean withSecurities = withLive
                || RULES_SECURITIES_CLEARING_EXPERIMENT_V1.equals(rulesVersion)
                || withMatching;
        boolean withBanking = withBankWorld || withBankRoles;
        boolean withRegistry = withLive
                || RULES_CASH_REGISTRY_WORLD_V1.equals(rulesVersion)
                || withSecurities
                || withBanking;
        // 银行三条旧档（world/roles/funds）各自是独立分支：只在完整公司派生链算完之后增补，
        // 不能参与上面的 withRoles→withGovernance→withManufacturing→withMatching 派生，
        // 否则“有 roles”会被误读成“有治理/制造/撮合”，纯银行运行会被迫提供制造开局。
        withRoles = withRoles || withBankRoles;
        withRoles = withRoles || withFunds;
        withMatching = withMatching || withFunds;
        withSecurities = withSecurities || withFunds;
        withRegistry = withRegistry || withFunds;
        withBanking = withBanking || withFunds;
        if (withRegistry) {
            expected.put("registry", 1);
        }
        if (withSecurities) {
            expected.put("securities", 1);
        }
        if (withMatching) {
            expected.put("matching", 1);
        }
        if (withManufacturing) {
            expected.put("manufacturing", 1);
        }
        if (withGovernance) {
            expected.put("governance", 1);
        }
        if (withRoles) {
            expected.put("information", 1);
            expected.put("roles", 1);
        }
        if (withTrading) {
            expected.put("trading", 1);
        }
        if (withIntent) {
            expected.put("command-intent", 1);
        }
        if (RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion) || withDividend) {
            expected.put("decisions", 1);
        }
        if (withDividend) {
            expected.put("dividend", 1);
        }
        if (withLive) {
            expected.put("live-decisions", 1);
        }
        if (withCognition) {
            expected.put("cognition-recall", 1);
        }
        if (withBelief) {
            expected.put("belief", 1);
        }
        if (withMedia) {
            expected.put("media", 1);
        }
        if (RULES_INTERVIEW_COORDINATION_EXPERIMENT_V1.equals(rulesVersion)) {
            expected.put("interview", 1);
        }
        if (RULES_INTERVIEW_DIALOGUE_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_MEDIA_EVIDENCE_EXPERIMENT_V1.equals(rulesVersion)
                || withSocial) {
            expected.put("interview", 1);
            expected.put("dialogue", 1);
        }
        if (RULES_MEDIA_EVIDENCE_EXPERIMENT_V1.equals(rulesVersion)
                || withSocial) {
            expected.put("evidence", 1);
        }
        if (withSocial) {
            expected.put("social", 1);
        }
        if (withEngineering) {
            expected.put("engineering", 1);
        }
        if (withGroups) {
            expected.put("groups", 1);
            expected.put("publication", 1);
        }
        if (withBanking) {
            // 显式银行档案：银行与托管各是一个模块，不由 schema 推断
            expected.put("banking", 1);
            expected.put("custody", 1);
        }
        if (withComposed) {
            // 组合档额外公司（目前 north）的真实董事会与周期容器；与 engineering/manufacturing 不相交
            expected.put("company-governance", 1);
        }
        if (withBankCustody) {
            // B7b 是独立分支：只加任职与阅读，不派生治理/制造/四岗交易。
            expected.put("information", 1);
            expected.put("roles", 1);
        }
        if (withFunds) {
            // 资金桶结算必须与银行/证券/撮合/任职/阅读同时存在（成对，不靠编号猜）
            if (!expected.containsKey("banking") || !expected.containsKey("securities")
                    || !expected.containsKey("matching") || !expected.containsKey("roles")
                    || !expected.containsKey("information")) {
                throw new IllegalArgumentException("资金桶档案缺少成对模块："
                        + rulesVersion + " 需要 banking/custody/securities/matching/roles/information");
            }
            expected.put("funding", 1);
        }
        if (withBankCustody || RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion)) {
            // 客户托管受益权成交由显式模块冻结；v1/v2 没有该模块，继续拒绝 CUSTODY。
            expected.put("custody-fill", 1);
        }
        if (!expected.equals(modules)) {
            throw new IllegalArgumentException("规则档案 " + rulesVersion + " 需要模块 "
                    + expected + "，收到 " + modules);
        }
        moduleVersions = Collections.unmodifiableMap(modules);

        Map<String, String> data = new TreeMap<>();
        if (dataVersions != null) {
            dataVersions.forEach((name, version) -> {
                if (name == null || name.isBlank() || version == null || version.isBlank()) {
                    throw new IllegalArgumentException("资料版本非法：" + name + "=" + version);
                }
                data.put(name, version);
            });
        }
        dataVersions = Collections.unmodifiableMap(data);

        if (withLive) {
            if (!LIVE_DECISIONS_V1.equals(modelConfigVersion)) {
                throw new IllegalArgumentException("live 决定档案的模型配置版本必须是 "
                        + LIVE_DECISIONS_V1 + "，收到 " + modelConfigVersion);
            }
        } else if (RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_DIVIDEND_WORLD_EXPERIMENT_V1.equals(rulesVersion)) {
            if (!SCRIPTED_DECISIONS_V1.equals(modelConfigVersion)) {
                throw new IllegalArgumentException("决定档案的模型配置版本必须是 "
                        + SCRIPTED_DECISIONS_V1 + "，收到 " + modelConfigVersion);
            }
        } else if (!MODEL_NONE.equals(modelConfigVersion)) {
            throw new IllegalArgumentException("当前未接入模型，模型配置版本必须是 "
                    + MODEL_NONE + "，收到 " + modelConfigVersion);
        }
    }
}
