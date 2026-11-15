package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.util.*;

/** 有限水电开局情景，全部数值是EXP；法规只支持约束机制，不提供这些校准参数。 */
public final class HydroIndustryConfig {
    private HydroIndustryConfig() { }
    /** 公司、现金、经理与独立五席董事。 */
    public static final String COMPANY="hydro", CASH="HY-CASH", MANAGER="hydro-manager";
    /** 固定董事候选，不预置经理任职或预算。 */
    public static final List<String> DIRECTORS=List.of("hydro-director-1","hydro-director-2","hydro-director-3","hydro-director-4","hydro-director-5");
    /** 购电方与承包方有独立有限账户。 */
    public static final List<CashLedger.OpeningAccount> ACCOUNTS=List.of(
            account(CASH,COMPANY,"5000.00"), account("HY-GRID","hydro-grid","50000.00"),
            account("HY-OPS","hydro-ops","0.00"), account("HY-FOUNDER","hydro-founder","0.00"));
    /** 公开物理参数/合同与私有有限来水路径。 */
    public static HydroCompanyRun.Config config() {
        long day=86_400_000L;
        return new HydroCompanyRun.Config(COMPANY,"HY1",new HydroModel.Config(COMPANY,CASH,"HY-GRID","HY-OPS",
                new HydroModel.PhysicalSpec(100,new BigDecimal("0.10"),600,100,50000,12000,60,0),
                new HydroModel.InflowSeries(List.of(new HydroModel.InflowSegment(0,30*day,600),
                        new HydroModel.InflowSegment(30*day,60*day,180),new HydroModel.InflowSegment(60*day,120*day,900))),
                new HydroModel.SalesContract(80,new BigDecimal("0.40"),120*day),
                new HydroModel.OperatingTerms(100,new BigDecimal("30000.00"),3650)),6*3_600_000L,new BigDecimal("60.00"));
    }
    private static CashLedger.OpeningAccount account(String id,String owner,String cash) {
        return new CashLedger.OpeningAccount(id,owner,new BigDecimal(cash),"EXP水电与有限外部部门历史禀赋");
    }
}
