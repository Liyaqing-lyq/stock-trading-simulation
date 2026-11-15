package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.util.*;

/** 新互通档把原有外部投资者禀赋分给三个独立人物；总现金、发行量及成本不变。 */
public final class ConnectedWorldOpening {
    private ConnectedWorldOpening() { }
    /** 只在新档开局拆分既有现金，不在运行中充值。 */
    public static List<CashLedger.OpeningAccount> accounts(List<CashLedger.OpeningAccount> old) {
        var result=new ArrayList<CashLedger.OpeningAccount>();
        for(var a:old)if(a.accountId().equals("INV")) {
            result.add(new CashLedger.OpeningAccount("INV","investor",a.cash().multiply(new BigDecimal("0.50")),"原INV历史禀赋50%"));
            result.add(new CashLedger.OpeningAccount("HOUSE-NINA","nina",a.cash().multiply(new BigDecimal("0.30")),"原INV历史禀赋30%"));
            result.add(new CashLedger.OpeningAccount("HOUSE-OMAR","omar",a.cash().multiply(new BigDecimal("0.20")),"原INV历史禀赋20%"));
        } else result.add(a);
        return List.copyOf(result);
    }
    /** 为既有人物登记有限财产；所有原股份逐股分配，余数归原持有人。 */
    public static OpeningRegistry registry(OpeningRegistry old) {
        var parties=new ArrayList<>(old.parties());var owners=new ArrayList<>(old.owners());var shares=new ArrayList<OpeningRegistry.ShareAllocation>();
        for(String p:List.of("nina","omar")) {
            if(parties.stream().noneMatch(x->x.partyId().equals(p)))parties.add(new OpeningRegistry.Party(p,OpeningRegistry.PartyKind.PERSON,p));
            if(owners.stream().noneMatch(x->x.ownerId().equals(p)))owners.add(new OpeningRegistry.PropertyOwner(p,OpeningRegistry.OwnerKind.PERSON,p));
        }
        for(var a:old.allocations())if(a.accountId().equals("INV")) {
            long n=a.quantity()*25/100,o=a.quantity()*15/100;
            shares.add(new OpeningRegistry.ShareAllocation("INV",a.symbol(),a.quantity()-n-o,a.costPrice(),a.classification()));
            if(n>0)shares.add(new OpeningRegistry.ShareAllocation("HOUSE-NINA",a.symbol(),n,a.costPrice(),a.classification()));
            if(o>0)shares.add(new OpeningRegistry.ShareAllocation("HOUSE-OMAR",a.symbol(),o,a.costPrice(),a.classification()));
        }else shares.add(a);
        return new OpeningRegistry(parties,owners,old.securities(),shares);
    }
}
