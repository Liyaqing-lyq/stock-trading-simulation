package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 新虚构公司档的岗位与工具语义。只提供公共规则，不替角色选票、订单或经营判断。 */
public final class PrototypeDecisionGuide {
    private PrototypeDecisionGuide() { }

    /** 将固定规则附于真实角色投影；不引入全知资料或现实公司身份。 */
    public static void append(ObjectNode view) {
        view.put("decisionGuideVersion", "prototype-v1");
        var guide = view.putObject("decisionGuide");
        guide.put("purpose", "履行自己的实际岗位职责。可主动阅读、研究、治理和经营；允许有理由的等待，不要求每轮交易或保证盈利。");
        guide.put("identifiers", "companyPartyId、personPartyId、已有会议/合同/资料必须取观察。创建新对象的meetingId、materialId、appointmentId、researchId、docId、targetId、childId等可自行命名，例如person+time+用途；不是编造已有事实。更新已有对象必须引用观察中的原标识及版本。");
        guide.put("time", "time与所有期限均为运行起点起的毫秒，1日86400000毫秒。决定及动作有耗时；新会议materialDeadline须晚于动作完成，opensAt不早于materialDeadline，closesAt晚于opensAt，需留足阅读和多名董事表决时间。预算period=floor(time/2592000000)。");
        guide.put("directors", "先阅读本公司的开局资料和材料，再判断经理任命、预算与考核。主席可召集，董事各自到会、读材料和表决。候选不等于已任命；需有有效任职和预算，经理才能经营。任免的被提名董事应回避。禁止替别人投票。");
        guide.put("manager", "在实际预算、现金、物量和需求约束内选择经营动作，检查在制和到期回款，避免重复承诺。长期项目允许在十日结束时仍未完成；不得压缩真实合同规定的时滞或编造结果。");
        guide.put("researcher", "阅读虚构公司已发布的资料，形成有引用、风险和反证的研究并提交给本机构；没有读到的正文不可引用，不能猜现实原型的后续历史。");
        guide.put("portfolioManager", "依据本人已读资料、研究、真实持仓与可用现金决定绝对目标股数，可减仓或保留现金。新目标expectedVersion=0；修改用当前版本。风控审核后需另行确认，不能自己代替风控。");
        guide.put("riskOfficer", "独立审核已有目标的资金、集中和流动性风险，批准或拒绝并说明依据；审核不是实际交易。");
        guide.put("executionTrader", "仅执行已经确认且有效审核的母任务，结合盘口选择限价与数量；没有成交可等待、撤单或重报价，不能擅自改变投资方向。");
        var contracts = guide.putObject("toolSemantics");
        contracts.put("board.convene.topic", "BUDGET 或 MANAGER_APPOINTMENT；其它议题仅在对应工具已提供时使用");
        contracts.put("board.vote.vote", "YES、NO、ABSTAIN；会议实际开票后且截止前、材料已读并可回忆才可投票");
        contracts.put("board.proposeAppointment", "action为APPOINT或REVOKE；新任命effectiveAt须晚于会议关闭和实施，endsAt大于effectiveAt；董事本人被任命须回避");
        contracts.put("board.proposeBudget.lines", "数组，每项{period:整数,category:PROCUREMENT或PRODUCTION_LABOR,amount:金额字符串}。额度是期间新承诺上限，不是新增现金。");
        contracts.put("role.research.submit.citations", "数组，每项{docId:已读资料标识,version:已读版本}；需真实可引用。研究属于自己的授权产品。");
        contracts.put("role.target.set", "absoluteTargetQuantity为该账户标的的绝对目标股数，不是本轮额外买入量；deadlineMillis是绝对到期时点");
    }
}
