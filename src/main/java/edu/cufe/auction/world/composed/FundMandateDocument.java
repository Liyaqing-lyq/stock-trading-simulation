package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

/** 由开局产品委托与账户登记写入受托机构的内部资料，仅在新档创建一次。 */
final class FundMandateDocument {
    private FundMandateDocument() { }

    static void publish(WorldRuntime world) throws Exception {
        var snap = world.snapshot();
        var access = snap.roles().orElseThrow().access();
        var registry = world.openingRegistry().orElseThrow();
        for (var mandate : access.mandates()) {
            var org = mandate.organizationPartyId();
            var product = mandate.productOwnerId();
            var manager = access.appointments().stream().filter(a -> a.organizationPartyId().equals(org)
                    && a.role() == RoleAccess.Role.PORTFOLIO_MANAGER && a.activeAt(snap.timeMillis()))
                    .findFirst().orElseThrow();
            var body = new StringBuilder("EXP 开局产品委托登记\n受托组织=").append(org)
                    .append("；产品财产主体=").append(product)
                    .append("。本机构受托跟踪产品持仓、可用现金及可核实的投资依据，自行评估是否维持配置。\n");
            if (RunManifest.FIXED_FUND_CAPITAL.equals(world.profile().manifest().dataVersions().get("fund-capital-policy"))) {
                body.append("产品采用固定开局资本，不受理投资者申购、赎回与追加或提取资本。证券成交和分红由产品账户入账。\n");
            }
            for (String account : mandate.accountIds()) {
                var opening = snap.accounts().get(account);
                body.append("开局账户=").append(account).append("；财产主体=").append(opening.ownerId())
                        .append("；账面现金=").append(opening.cash().toPlainString()).append("。\n");
                for (var lot : registry.allocations()) {
                    if (account.equals(lot.accountId())) {
                        body.append("初始配股：").append(lot.symbol()).append("，").append(lot.quantity()).append("股。\n");
                    }
                }
            }
            body.append("复查依据：当前产品资产、冻结数量见本人授权账户；买卖盘与历史成交见本人行情。公司公开资料可检索、阅读，正文有各自的发布者和版本。\n")
                    .append("研究员可选标的和问题，核查已读来源后提交该产品的具名研究报告。没有可靠来源时可继续查资料、询问或等待。\n")
                    .append("组合经理可阅读报告、比较现金与现有持仓。提出目标须对应本产品同一证券的已读报告，填写绝对目标股数、价格边界与截止。也可维持配置。\n")
                    .append("风控可核对目标版本和投资依据，审议数量、单笔、价格与有效期，作出批准或拒绝。经理确认已批准的同一版本后进入执行。\n")
                    .append("执行员可核对已确认的版本、风控边界与市场条件，自行决定是否分笔申报或撤单。无有效授权时可观察和查证。实际订单和成交分别以交易所与产品账户回报为准。");
            if ("appointment-pair-v1".equals(world.profile().manifest().dataVersions().get("fund-office-contacts"))) {
                body.append("\n在岗业务通讯录：读完本文件后，social.contacts 可见同一产品其他在岗人员、职务和到达其本人的业务地址。"
                        + "地址随双方任职变更失效；写信、收件、阅读分别占用时间。");
            }
            String id = "fund-mandate:" + org + ":" + product;
            world.schedulePublishDocument("opening-" + id, "opening-task-" + id, 1L, id,
                    manager.personPartyId(), InformationModel.Audience.organization(org),
                    product + " 受托产品委托书、开局资产与四岗权限（EXP）", body.toString(),
                    "开局受托登记，归档人为登记的产品经理，内容取自冻结账户和制度", CompanyOpeningDocuments.READ_MILLIS,
                    java.util.List.of());
        }
    }
}
