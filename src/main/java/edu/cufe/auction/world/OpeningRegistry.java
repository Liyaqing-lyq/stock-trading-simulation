package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 开局登记：人物/组织、财产主体、证券发行与初始股份分配。
 *
 * <p>这是**不可变开局元数据**，只描述历史开局时的归属，不表示实时持仓，也不启用证券交易、
 * T+1、基金申赎或公司经营。持仓数量只有 {@link #allocations()} 一份权威，不再另存发行人持股表。</p>
 *
 * <p>构造期完成表内校验（主键唯一、引用存在、发行守恒）；与现金开局账户的对齐由
 * {@link #validateAgainst(List)} 完成，后者不复制现金表，只读取 accountId/ownerId。</p>
 */
public record OpeningRegistry(List<Party> parties, List<PropertyOwner> owners,
                              List<Security> securities, List<ShareAllocation> allocations) {

    /** 人物 / 组织。 */
    public enum PartyKind {
        /** 自然人。 */
        PERSON,
        /** 组织。 */
        ORGANIZATION
    }

    /** 财产主体种类；产品与聚合主体不挂靠人物或组织。 */
    public enum OwnerKind {
        /** 个人财产主体。 */
        PERSON,
        /** 组织财产主体。 */
        ORGANIZATION,
        /** 产品财产主体（独立持有，管理授权后续单独实现）。 */
        PRODUCT,
        /** 聚合外部主体。 */
        AGGREGATE
    }

    /** 股份分类；本轮只有登记，没有解锁或转让动作。 */
    public enum ShareClass {
        /** 流通股。 */
        FLOAT,
        /** 锁定股。 */
        LOCKED,
        /** 库存股，必须归发行组织。 */
        TREASURY
    }

    /**
     * 人物或组织：只有身份，不含资金或股份。
     *
     * @param partyId 标识
     * @param kind    种类
     * @param name    展示名
     */
    public record Party(String partyId, PartyKind kind, String name) {
        /** 校验非空字段。 */
        public Party {
            requireText(partyId, "人物/组织标识");
            Objects.requireNonNull(kind, "人物/组织种类");
            requireText(name, "人物/组织名称");
        }
    }

    /**
     * 财产主体：持有资产的权利主体，不等同人物或雇主。
     *
     * @param ownerId 标识
     * @param kind    种类
     * @param partyId 关联人物/组织；产品与聚合主体必须为空
     */
    public record PropertyOwner(String ownerId, OwnerKind kind, String partyId) {
        /** 校验种类与关联字段的一致性。 */
        public PropertyOwner {
            requireText(ownerId, "财产主体标识");
            Objects.requireNonNull(kind, "财产主体种类");
            if (kind == OwnerKind.PERSON || kind == OwnerKind.ORGANIZATION) {
                requireText(partyId, "人物/组织类财产主体的 partyId");
            } else if (partyId != null && !partyId.isBlank()) {
                throw new IllegalArgumentException(kind + " 财产主体不能关联人物或组织：" + ownerId);
            } else {
                partyId = null;
            }
        }
    }

    /**
     * 证券与发行。
     *
     * @param symbol               标的代码
     * @param issuerOrganizationId 发行组织（必须是已登记的组织）
     * @param issuedShares         已发行股数
     * @param initialReferencePrice 开局参考估值，精确到分
     */
    public record Security(String symbol, String issuerOrganizationId, long issuedShares,
                           BigDecimal initialReferencePrice) {
        /** 校验标识、股数与估值精度。 */
        public Security {
            requireText(symbol, "证券代码");
            requireText(issuerOrganizationId, "发行组织");
            if (issuedShares <= 0) {
                throw new IllegalArgumentException("已发行股数必须为正：" + issuedShares);
            }
            initialReferencePrice = money2(initialReferencePrice, "开局参考估值");
        }
    }

    /**
     * 开局股份分配：数量与成本的唯一权威。
     *
     * @param accountId      持股账户
     * @param symbol         标的代码
     * @param quantity       数量
     * @param costPrice      成本价，精确 4 位
     * @param classification 股份分类
     */
    public record ShareAllocation(String accountId, String symbol, long quantity,
                                  BigDecimal costPrice, ShareClass classification) {
        /** 校验数量、成本精度与分类。 */
        public ShareAllocation {
            requireText(accountId, "持股账户");
            requireText(symbol, "证券代码");
            if (quantity <= 0) {
                throw new IllegalArgumentException("分配数量必须为正：" + quantity);
            }
            costPrice = cost4(costPrice, "成本价");
            Objects.requireNonNull(classification, "股份分类");
        }
    }

    /** 规范化并校验登记表；拒绝重复主键与引用缺失，并核对逐证券发行守恒。 */
    public OpeningRegistry {
        List<Party> partyList = sorted(parties, Comparator.comparing(Party::partyId));
        Map<String, Party> partyById = new LinkedHashMap<>();
        for (Party party : partyList) {
            if (partyById.putIfAbsent(party.partyId(), party) != null) {
                throw new IllegalArgumentException("重复人物/组织标识：" + party.partyId());
            }
        }

        List<PropertyOwner> ownerList = sorted(owners, Comparator.comparing(PropertyOwner::ownerId));
        Map<String, PropertyOwner> ownerById = new LinkedHashMap<>();
        for (PropertyOwner owner : ownerList) {
            if (ownerById.putIfAbsent(owner.ownerId(), owner) != null) {
                throw new IllegalArgumentException("重复财产主体标识：" + owner.ownerId());
            }
            if (owner.kind() == OwnerKind.PERSON || owner.kind() == OwnerKind.ORGANIZATION) {
                Party party = partyById.get(owner.partyId());
                if (party == null) {
                    throw new IllegalArgumentException("财产主体引用了未登记的人物/组织："
                            + owner.ownerId() + " -> " + owner.partyId());
                }
                PartyKind expected = owner.kind() == OwnerKind.PERSON
                        ? PartyKind.PERSON : PartyKind.ORGANIZATION;
                if (party.kind() != expected) {
                    throw new IllegalArgumentException("财产主体种类与人物/组织不一致："
                            + owner.ownerId());
                }
            }
        }

        List<Security> securityList = sorted(securities, Comparator.comparing(Security::symbol));
        Map<String, Security> securityBySymbol = new LinkedHashMap<>();
        for (Security security : securityList) {
            if (securityBySymbol.putIfAbsent(security.symbol(), security) != null) {
                throw new IllegalArgumentException("重复证券代码：" + security.symbol());
            }
            Party issuer = partyById.get(security.issuerOrganizationId());
            if (issuer == null || issuer.kind() != PartyKind.ORGANIZATION) {
                throw new IllegalArgumentException("发行组织未登记或不是组织："
                        + security.issuerOrganizationId());
            }
        }

        List<ShareAllocation> allocationList = sorted(allocations,
                Comparator.comparing(ShareAllocation::accountId)
                        .thenComparing(ShareAllocation::symbol)
                        .thenComparing(ShareAllocation::classification));
        Set<AllocationKey> seen = new LinkedHashSet<>();
        for (ShareAllocation allocation : allocationList) {
            Security security = securityBySymbol.get(allocation.symbol());
            if (security == null) {
                throw new IllegalArgumentException("分配引用了未登记证券：" + allocation.symbol());
            }
            if (allocation.costPrice().compareTo(security.initialReferencePrice()) != 0) {
                throw new IllegalArgumentException("开局成本必须等于参考估值："
                        + allocation.accountId() + " " + allocation.symbol() + " 成本 "
                        + allocation.costPrice().toPlainString() + "，估值 "
                        + security.initialReferencePrice().toPlainString());
            }
            AllocationKey key = new AllocationKey(allocation.accountId(), allocation.symbol(),
                    allocation.classification());
            if (!seen.add(key)) {
                throw new IllegalArgumentException("重复分配主键（账户, 证券, 分类）："
                        + allocation.accountId() + ", " + allocation.symbol() + ", "
                        + allocation.classification());
            }
        }
        for (Security security : securityList) {
            long total = 0L;
            try {
                for (ShareAllocation allocation : allocationList) {
                    if (allocation.symbol().equals(security.symbol())) {
                        total = Math.addExact(total, allocation.quantity());
                    }
                }
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("股份合计溢出：" + security.symbol(), overflow);
            }
            if (total != security.issuedShares()) {
                throw new IllegalArgumentException("已发行股数与分配合计不一致：" + security.symbol()
                        + " 发行 " + security.issuedShares() + "，分配 " + total);
            }
        }

        parties = List.copyOf(partyList);
        owners = List.copyOf(ownerList);
        securities = List.copyOf(securityList);
        allocations = List.copyOf(allocationList);
    }

    /**
     * 与既有现金开局账户对齐：账户属主必须已登记，持股账户必须存在，
     * 库存股必须归发行组织。不复制现金余额，也不改动现金表。
     *
     * @param cashOpenings 现金开局清单
     * @throws IllegalArgumentException 对齐失败
     */
    public void validateAgainst(List<OpeningAccount> cashOpenings) {
        Objects.requireNonNull(cashOpenings, "cashOpenings");
        Map<String, String> ownerOfAccount = new LinkedHashMap<>();
        for (OpeningAccount account : cashOpenings) {
            if (ownerOfAccount.putIfAbsent(account.accountId(), account.ownerId()) != null) {
                throw new IllegalArgumentException("重复开局现金账户：" + account.accountId());
            }
            if (owner(account.ownerId()) == null) {
                throw new IllegalArgumentException("现金账户属主未登记为财产主体："
                        + account.accountId() + " -> " + account.ownerId());
            }
        }
        for (ShareAllocation allocation : allocations) {
            String ownerId = ownerOfAccount.get(allocation.accountId());
            if (ownerId == null) {
                throw new IllegalArgumentException("持股账户不在现金开局清单中："
                        + allocation.accountId());
            }
            if (allocation.classification() == ShareClass.TREASURY) {
                PropertyOwner holder = owner(ownerId);
                Security security = security(allocation.symbol());
                if (holder.kind() != OwnerKind.ORGANIZATION
                        || !Objects.equals(holder.partyId(), security.issuerOrganizationId())) {
                    throw new IllegalArgumentException("库存股必须归发行组织：账户 "
                            + allocation.accountId() + " 属于 " + ownerId);
                }
            }
        }
    }

    /**
     * 分配主键：账户 + 证券 + 分类。
     *
     * <p>用元组而不是分隔符拼接：标识本身含分隔符时不会与另一组标识撞成同一个键。
     * 同一账户同一证券的不同分类可以并存（流通/锁定/库存分别列示），只有三元组完全相同才算重复。</p>
     */
    private record AllocationKey(String accountId, String symbol, ShareClass classification) {
    }

    /** @param ownerId 财产主体标识 @return 已登记主体；不存在返回 null */
    public PropertyOwner owner(String ownerId) {
        for (PropertyOwner owner : owners) {
            if (owner.ownerId().equals(ownerId)) {
                return owner;
            }
        }
        return null;
    }

    /** @param symbol 证券代码 @return 已登记证券；不存在返回 null */
    public Security security(String symbol) {
        for (Security security : securities) {
            if (security.symbol().equals(symbol)) {
                return security;
            }
        }
        return null;
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> order) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source, "登记表不能为空"));
        copy.sort(order);
        return copy;
    }

    private static BigDecimal money2(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到分：" + value, failure);
        }
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须为正：" + value);
        }
        return scaled;
    }

    private static BigDecimal cost4(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到 4 位小数：" + value, failure);
        }
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须为正：" + value);
        }
        return scaled;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
