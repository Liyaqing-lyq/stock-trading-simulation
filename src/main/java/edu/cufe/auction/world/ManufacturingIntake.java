package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 制造公司收单规则的显式单向迁移。既有合同和资产始终留在原经营台账。 */
public final class ManufacturingIntake {
    /** 原规则可以签订新合同，退役版本仅关闭新签入口。 */
    public static final String ACTIVE = "accept-new-v1", RUNOFF = "service-existing-v1";
    private ManufacturingIntake() { }

    /** @param expectedPosition 管理员所检查的同一运行位置 @param fromVersion 原规则
     * @param toVersion 目标规则 @param companyIds 本次原子切换的确切公司 */
    public record Migration(long expectedPosition, String fromVersion, String toVersion, List<String> companyIds) {
        public Migration {
            if (expectedPosition < 1 || !ACTIVE.equals(fromVersion) || !RUNOFF.equals(toVersion))
                throw new IllegalArgumentException("只支持从现行收单规则切换到仅履行存量合同");
            var checked = new TreeSet<String>();
            if (companyIds == null) throw new IllegalArgumentException("请选择实际制造公司");
            for (String id : companyIds)
                if (id == null || id.isBlank() || !checked.add(id)) throw new IllegalArgumentException("公司列表不能有空项或重复项");
            if (checked.isEmpty()) throw new IllegalArgumentException("请选择实际制造公司");
            companyIds = List.copyOf(checked);
        }
    }

    static CompanyOperationsRun migrate(CompanyOperationsRun run, Migration request) {
        for (String id : request.companyIds()) {
            var c = run.company(id);
            if (c == null || !c.currentIntakeVersion().equals(request.fromVersion()))
                throw new IllegalArgumentException("公司不存在或其当前收单版本已经改变：" + id);
        }
        var companies = new ArrayList<CompanyOperationsRun.Company>();
        for (var c : run.companies()) companies.add(request.companyIds().contains(c.companyPartyId())
                ? new CompanyOperationsRun.Company(c.companyPartyId(), c.symbol(), c.templateId(), c.ledger(), c.expansionOffer(), RUNOFF) : c);
        return new CompanyOperationsRun(run.rulesVersion(), companies, run.reports(), run.resource(), run.hydro(),
                run.digital(), run.aged(), run.externalDemand());
    }
}
