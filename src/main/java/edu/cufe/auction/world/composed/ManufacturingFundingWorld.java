package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.InterviewRun;
import edu.cufe.auction.world.ManufacturingFunding;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code composed-v4} 制造资金开局：组合档（{@code composed-v3} 的全部模块）之上再加
 * {@code manufacturing-funding}，并把制造经营五段真实资金腿的四段系统路由冻结进开局。
 *
 * <p><b>不改旧档</b>：本类是一个<b>新</b>的显式开局，不改 {@link ComposedWorldScenario} 的清单、
 * 模块表、开局现金、登记或画像；旧 {@code composed-v1/v2/v3} 运行仍按原契约读取，不会被本类隐式升级。</p>
 *
 * <p><b>不重复给钱</b>：开局现金、登记、银行档案、托管人与章程逐字沿用组合档；本类只增加
 * 冻结路由与一条<b>真实人物任职</b>（公司经理），因为五段资金腿中的人工、费用与回款都要由
 * 真实经理发起，系统不会替人点头。</p>
 *
 * <p><b>EXP</b>：所有金额、期限与主体都是实验夹具，不是现实校准数据。</p>
 */
public final class ManufacturingFundingWorld {

    /** 运行标识。 */
    public static final String RUN_ID = "web-composed-manufacturing-funding";

    /** 制造公司经理（真实任职，开局历史禀赋）。 */
    public static final String MANAGER = "erin";

    /** 该经理的任职标识。 */
    public static final String MANAGER_APPOINTMENT = "MFG-MGR-1";

    private ManufacturingFundingWorld() {
    }

    /**
     * 清单：{@code composed-v4} = 组合档全部模块 + {@code manufacturing-funding}。
     *
     * <p>模块表显式冻结，不由档案名或 schema 反推。</p>
     *
     * @return 清单
     */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>(
                WorldWebScenario.groupsManifest().moduleVersions());
        modules.put("banking", 1);
        modules.put("custody", 1);
        modules.put("funding", 1);
        modules.put("company-governance", 1);
        modules.put("command-intent", 1);
        modules.put("custody-fill", 1);
        modules.put("manufacturing-funding", 1);
        return new RunManifest(RUN_ID, 11L, RunManifest.RULES_COMPOSED_BANK_WORLD_V4, modules,
                Map.of("profile", "composed-manufacturing-funding-v4"),
                RunManifest.LIVE_DECISIONS_V1);
    }

    /**
     * 制造五段冻结路由：四段都指向冻结经营账户绑定里的真实现金账户。
     *
     * <p>这是“若必须新增固定路由配置就明确冻结在开局”的那一份配置：它随首个提交与运行画像一起
     * 冻结，重开同一日志必须给同一份，子分支继承同一份。</p>
     *
     * @return 冻结路由
     */
    public static ManufacturingFunding funding() {
        return ManufacturingFunding.cash(accounting().accounting());
    }

    /** @return 制造经营配置（组合档原样） */
    public static ManufacturingRun.Config accounting() {
        return new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                "SUP", "CUS", "OPS"), WorldWebScenario.SYMBOL);
    }

    /**
     * 打开（或重开）本档世界；同一日志必须给同一份申请与路由。
     *
     * @param journal 日志路径
     * @return 运行
     * @throws Exception 打开失败
     */
    public static WorldRuntime open(Path journal) throws Exception {
        long end = 30L * 86_400_000L;
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", "alice", WorldWebScenario.COMPANY,
                        RoleAccess.Role.RESEARCHER, 0L, end, null),
                new RoleAccess.Appointment("A-2", "bob", WorldWebScenario.COMPANY,
                        RoleAccess.Role.PORTFOLIO_MANAGER, 0L, end, null),
                new RoleAccess.Appointment("A-3", "carol", WorldWebScenario.COMPANY,
                        RoleAccess.Role.RISK_OFFICER, 0L, end, null),
                new RoleAccess.Appointment("A-4", "dave", WorldWebScenario.COMPANY,
                        RoleAccess.Role.EXECUTION_TRADER, 0L, end, null),
                // 制造业公司经理：五段资金腿的真实人物入口。开局即有效，任期覆盖整段经营；
                // 撤权/到期由真实任职任务发生（到期后动作会被 NOT_AUTHORIZED 拒绝）。
                new RoleAccess.Appointment(MANAGER_APPOINTMENT, MANAGER, WorldWebScenario.COMPANY,
                        RoleAccess.Role.COMPANY_MANAGER, 0L, 400L * 86_400_000L, null)));
        for (int i = 0; i < WorldWebScenario.MEDIA_PERSONS.size(); i++) {
            appointments.add(new RoleAccess.Appointment("MEDIA-" + i,
                    WorldWebScenario.MEDIA_PERSONS.get(i),
                    i < 2 ? "wire" : "investigative",
                    i % 2 == 0 ? RoleAccess.Role.JOURNALIST : RoleAccess.Role.EDITOR,
                    0L, end, null));
        }
        List<String> readers = new ArrayList<>(List.of("alice", "bob", "carol", "dave", "erin",
                WorldWebScenario.INVESTOR));
        readers.addAll(WorldWebScenario.MEDIA_PERSONS);
        readers.addAll(WorldWebScenario.SOCIAL_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers,
                WorldWebScenario.COGNITION_POLICY,
                new BeliefModel.Policy(200, 8, end, 20, 200, 3, end));
        List<InterviewRun.ContactEntry> contacts =
                new ArrayList<>(WorldWebScenario.interviewConfig().contacts());
        for (String person : WorldWebScenario.SOCIAL_PERSONS) {
            contacts.add(new InterviewRun.ContactEntry("私信", person,
                    "公开联系说明：可一对一私人消息", WorldWebScenario.SOCIAL_CONTACT_DOC, 1L));
        }
        return new WorldRuntime(journal, manifest(), ComposedWorldScenario.openings(),
                ComposedWorldScenario.registry(), accounting(),
                ComposedWorldScenario.cufeCharter(),
                new RoleRun.Config(List.of(new RoleAccess.ProductMandate(
                        WorldWebScenario.COMPANY, "fund-a", List.of("ACC-FUND"))), appointments),
                info,
                WorldWebScenario.groupsDecisionConfig(), null,
                WorldWebScenario.mediaConfig(), WorldWebScenario.interviewConfig(),
                WorldWebScenario.dialogueConfig(), WorldWebScenario.evidenceConfig(),
                WorldWebScenario.socialConfig(), WorldWebScenario.SOCIAL_BLOCK_MILLIS,
                contacts, ComposedWorldScenario.engineeringConfigWithAcceptance(),
                WorldWebScenario.groupsConfig(), WorldWebScenario.groupsPlatformConfig(),
                WorldWebScenario.GROUPS_FEED_LIMIT, WorldWebScenario.GROUPS_READ_MILLIS,
                ComposedWorldScenario.charters(), ComposedWorldScenario.bankOpening(),
                funding());
    }

    /** @return 开局登记（组合档原样，含 cufe/供应商/客户/人力主体） */
    public static OpeningRegistry registry() {
        return ComposedWorldScenario.registry();
    }
}
