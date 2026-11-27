package edu.cufe.auction.web;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.util.*;

/** 管理员在单一确认快照上查看各财产主体持股的静态价格冲击。 */
final class WorldExposureMatrix {
    private WorldExposureMatrix() { }
    private static final BigDecimal SHOCK = new BigDecimal("-0.10");

    record Issuer(String symbol,String issuerId,String name,String issuedShares,String price,String source,
                  String priceTime,String priceAge,String marketStatus) { }
    record LotEvidence(String lotId,String accountId,String quantity,String shareClass,String unitCost,
                       String acquiredAt,String ledgerSequence,Map<String,String> original) { }
    record Cell(String quantity,String valueDelta,List<LotEvidence> lots) { }
    record OwnerRow(String ownerId,String name,Map<String,Cell> cells,String valueDelta) { }
    record Group(String kind,List<OwnerRow> owners,Map<String,Cell> cells,String valueDelta) { }
    record View(boolean available,String position,String timeMillis,String shock,List<Issuer> issuers,List<Group> groups) { }

    static View of(WorldRuntime world) {
        var state=world.snapshot();
        if (world.openingRegistry().isEmpty() || state.securities().isEmpty() || state.exchange().isEmpty())
            return new View(false,Long.toString(state.position()),Long.toString(state.timeMillis()),SHOCK.toPlainString(),List.of(),List.of());
        var registry=world.openingRegistry().orElseThrow();
        var securities=state.securities().orElseThrow();
        var exchange=state.exchange().orElseThrow();
        var symbols=registry.securities().stream().map(OpeningRegistry.Security::symbol).toList();
        var marks=ValuationModel.prices(state.position(),state.timeMillis(),exchange,registry,symbols,
                state.dividends().orElseGet(DividendModel::empty));
        Map<String,String> names=new HashMap<>();
        for (var party:registry.parties()) names.put(party.partyId(),WorldOverviewView.displayName(party.partyId(),party.name()));
        List<Issuer> issuers=new ArrayList<>();
        for (var security:registry.securities()) {
            var mark=ValuationModel.priceOf(marks,security.symbol());
            var resolution=world.resolutionStatus(security.symbol());
            String status=resolution!=null && resolution.openedAtMillis()!=null ? resolution.trading()
                    : RunManifest.usesMarketSessions(world.profile().manifest().policyRunId())
                    ? MarketSessions.status(security.symbol(),state.timeMillis(),exchange.closed()) : exchange.closed() ? "CLOSED" : "OPEN";
            issuers.add(new Issuer(security.symbol(),security.issuerOrganizationId(),names.get(security.issuerOrganizationId()),
                    Long.toString(security.issuedShares()),decimal(mark.price()),mark.source().name(),
                    mark.price()==null?null:Long.toString(mark.asOfMillis()),mark.price()==null?null:Long.toString(mark.ageMillis()),status));
        }
        Map<String,WorldExchange.Trade> trades=new HashMap<>();
        for (var trade:exchange.trades()) trades.put("lot-fill:trade-"+trade.tradeId(),trade);
        Map<String,Map<String,List<LotEvidence>>> holdings=new HashMap<>();
        for (var lot:securities.lots()) {
            String ownerId=state.accounts().get(lot.accountId()).ownerId();
            Map<String,String> original;
            var trade=trades.get(lot.lotId());
            if (trade!=null) original=Map.of("kind","TRADE","tradeId",Long.toString(trade.tradeId()),
                    "buyOrderId",Long.toString(trade.buyOrderId()),"sellOrderId",Long.toString(trade.sellOrderId()),
                    "buyer",trade.buyAccountId(),"seller",trade.sellAccountId(),"price",decimal(trade.price()),
                    "quantity",Long.toString(trade.quantity()),"timeMillis",Long.toString(trade.timeMillis()));
            else if (("lot-open-"+lot.acquiredPosition()).equals(lot.lotId())) {
                var originalAllocation=registry.allocations().get(Math.toIntExact(lot.acquiredPosition()));
                original=Map.of("kind","OPENING_ALLOCATION","accountId",originalAllocation.accountId(),"symbol",originalAllocation.symbol(),
                        "shareClass",originalAllocation.classification().name(),"price",decimal(originalAllocation.costPrice()),
                        "quantity",Long.toString(originalAllocation.quantity()),"timeMillis","0");
            } else original=Map.of("kind","SECURITIES_LEDGER");
            holdings.computeIfAbsent(ownerId,k->new TreeMap<>()).computeIfAbsent(lot.symbol(),k->new ArrayList<>())
                    .add(new LotEvidence(lot.lotId(),lot.accountId(),Long.toString(lot.remaining()),lot.classification().name(),
                            decimal(lot.costPrice()),Long.toString(lot.acquiredAtMillis()),Long.toString(lot.acquiredPosition()),original));
        }
        List<Group> groups=new ArrayList<>();
        for (var kind:OpeningRegistry.OwnerKind.values()) {
            List<OwnerRow> owners=new ArrayList<>();Map<String,Long> sums=new TreeMap<>();
            for (var owner:registry.owners()) if (owner.kind()==kind) {
                Map<String,Cell> cells=new TreeMap<>();
                for (var item:holdings.getOrDefault(owner.ownerId(),Map.of()).entrySet()) {
                    long q=0;for(var lot:item.getValue()) q=Math.addExact(q,Long.parseLong(lot.quantity()));
                    cells.put(item.getKey(),cell(q,marks.prices().get(item.getKey()),item.getValue()));
                    sums.merge(item.getKey(),q,Math::addExact);
                }
                owners.add(new OwnerRow(owner.ownerId(),names.getOrDefault(owner.partyId(),owner.ownerId()),cells,total(cells)));
            }
            Map<String,Cell> cells=new TreeMap<>();
            sums.forEach((symbol,q)->cells.put(symbol,cell(q,marks.prices().get(symbol),List.of())));
            groups.add(new Group(kind.name(),owners,cells,total(cells)));
        }
        return new View(true,Long.toString(state.position()),Long.toString(state.timeMillis()),SHOCK.toPlainString(),issuers,groups);
    }

    private static Cell cell(long quantity,ValuationModel.Price mark,List<LotEvidence> lots) {
        var price=mark.price();
        return new Cell(Long.toString(quantity),price==null?null:decimal(price.multiply(BigDecimal.valueOf(quantity)).multiply(SHOCK)),lots);
    }

    private static String total(Map<String,Cell> cells) {
        BigDecimal sum=BigDecimal.ZERO;
        for (var c:cells.values()) {
            if (c.valueDelta()==null) return null;
            sum=sum.add(new BigDecimal(c.valueDelta()));
        }
        return decimal(sum);
    }

    private static String decimal(BigDecimal value) {
        if (value==null) return null;
        return value.scale()>2 ? value.setScale(Math.max(2,value.stripTrailingZeros().scale())).toPlainString()
                : value.setScale(2).toPlainString();
    }
}
