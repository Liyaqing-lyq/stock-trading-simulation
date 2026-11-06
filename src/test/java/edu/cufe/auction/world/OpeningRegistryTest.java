package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.OpeningRegistry.Security;
import edu.cufe.auction.world.OpeningRegistry.ShareAllocation;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M2.2：开局身份、财产主体与股份登记的纯校验。
 *
 * <p>只覆盖登记数据本身与“与既有现金开局账户对齐”，不涉及交易、T+1、冻结或公司经营。</p>
 */
@DisplayName("OpeningRegistry：开局身份与股份登记校验")
class OpeningRegistryTest {

    private static final BigDecimal PRICE = new BigDecimal("10.00");
    private static final BigDecimal COST = new BigDecimal("10.0000");

    @Test
    @DisplayName("发行 1000 拆成 A600/B300/外部100：全部登记后通过")
    void fullyAllocatedIssuanceAccepted() {
        OpeningRegistry registry = registry(600, 300, 100, 1000);
        assertDoesNotThrow(() -> registry.validateAgainst(cash()));
        assertEquals(3, registry.allocations().size());
        assertEquals(1000L, registry.securities().get(0).issuedShares());
    }

    @Test
    @DisplayName("少发与超发都被拒绝")
    void underAndOverIssuanceRejected() {
        assertThrows(IllegalArgumentException.class, () -> registry(600, 300, 99, 1000),
                "合计 999 少于发行 1000");
        assertThrows(IllegalArgumentException.class, () -> registry(600, 300, 101, 1000),
                "合计 1001 多于发行 1000");
    }

    @Test
    @DisplayName("锁定股与库存股都计入发行总数，只算流通股必须失败")
    void lockedAndTreasuryCountTowardIssuedShares() {
        OpeningRegistry mixed = new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("B", "ACME1", 300, COST, ShareClass.LOCKED),
                        new ShareAllocation("TREAS", "ACME1", 100, COST, ShareClass.TREASURY)));
        assertDoesNotThrow(() -> mixed.validateAgainst(cash()));

        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                        List.of(new Security("ACME1", "acme", 1000, PRICE)),
                        List.of(new ShareAllocation("A", "ACME1", 600, COST, ShareClass.FLOAT))),
                "按可卖量漏算锁定/库存股时必须失败");
    }

    @Test
    @DisplayName("库存股必须归发行组织，落在个人账户上拒绝")
    void treasuryMustBelongToIssuer() {
        OpeningRegistry wrong = new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("B", "ACME1", 300, COST, ShareClass.FLOAT),
                        new ShareAllocation("EXT", "ACME1", 100, COST, ShareClass.TREASURY)));
        assertThrows(IllegalArgumentException.class, () -> wrong.validateAgainst(cash()),
                "库存股账户属主是另一个组织，不是发行组织");
    }

    @Test
    @DisplayName("未知持股账户、未知账户属主都拒绝")
    void unknownHolderAccountRejected() {
        OpeningRegistry unknownAccount = new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("B", "ACME1", 300, COST, ShareClass.FLOAT),
                        new ShareAllocation("ZZ", "ACME1", 100, COST, ShareClass.FLOAT)));
        assertThrows(IllegalArgumentException.class, () -> unknownAccount.validateAgainst(cash()));

        List<OpeningAccount> ghostOwner = new ArrayList<>(cash());
        ghostOwner.add(new OpeningAccount("G", "ghost", BigDecimal.ZERO, "历史禀赋"));
        assertThrows(IllegalArgumentException.class, () -> registry(600, 300, 100, 1000)
                .validateAgainst(ghostOwner), "账户属主未登记为财产主体");
    }

    @Test
    @DisplayName("发行人必须是已登记组织，未知证券也拒绝")
    void unknownIssuerOrSymbolRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "alice", 1000, PRICE)), List.of()),
                "人物不能作为发行人");
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "nobody", 1000, PRICE)), List.of()),
                "未知发行组织");
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "NOPE", 1000, COST, ShareClass.FLOAT))),
                "未知证券");
    }

    @Test
    @DisplayName("各表重复主键都拒绝")
    void duplicateKeysRejected() {
        List<Party> dupParties = new ArrayList<>(parties());
        dupParties.add(new Party("alice", PartyKind.PERSON, "另一个 Alice"));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(dupParties, owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)), allocations(1000)));

        List<PropertyOwner> dupOwners = new ArrayList<>(owners());
        dupOwners.add(new PropertyOwner("alice", OwnerKind.PERSON, "alice"));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), dupOwners,
                List.of(new Security("ACME1", "acme", 1000, PRICE)), allocations(1000)));

        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE),
                        new Security("ACME1", "acme", 10, PRICE)), allocations(1000)));

        List<ShareAllocation> dupAllocations = new ArrayList<>(allocations(1000));
        dupAllocations.add(new ShareAllocation("A", "ACME1", 1, COST, ShareClass.FLOAT));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1001, PRICE)), dupAllocations));
    }

    @Test
    @DisplayName("数量非正与 long 溢出都拒绝")
    void negativeAndOverflowQuantitiesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 0, COST, ShareClass.FLOAT))));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", -1, COST, ShareClass.FLOAT))));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", Long.MAX_VALUE, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", Long.MAX_VALUE - 1, COST, ShareClass.FLOAT),
                        new ShareAllocation("B", "ACME1", 2, COST, ShareClass.FLOAT))),
                "相加溢出必须拒绝");
    }

    @Test
    @DisplayName("成本精度超 4 位、参考估值精度超 2 位都拒绝")
    void badPrecisionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 1000, new BigDecimal("10.00001"),
                        ShareClass.FLOAT))));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, new BigDecimal("10.001"))),
                allocations(1000)));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, BigDecimal.ZERO)), allocations(1000)));
    }

    @Test
    @DisplayName("产品财产主体独立于人物与雇主，人物/组织必须引用同类 Party")
    void productOwnerIsIndependent() {
        PropertyOwner product = owners().stream()
                .filter(owner -> owner.ownerId().equals("prod-1")).findFirst().orElseThrow();
        assertEquals(OwnerKind.PRODUCT, product.kind());
        assertEquals(null, product.partyId(), "产品主体不挂靠人物或组织");
        assertDoesNotThrow(() -> registry(600, 300, 100, 1000).validateAgainst(cash()));

        List<PropertyOwner> wrongKind = new ArrayList<>(owners());
        wrongKind.add(new PropertyOwner("bad", OwnerKind.PERSON, "acme"));
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), wrongKind,
                List.of(new Security("ACME1", "acme", 1000, PRICE)), allocations(1000)),
                "PERSON 主体不能引用 ORGANIZATION");
        OpeningRegistry treasuryOnProduct = new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("FUND", "ACME1", 1000, COST, ShareClass.TREASURY)));
        assertThrows(IllegalArgumentException.class, () -> treasuryOnProduct.validateAgainst(cash()),
                "产品账户不能作为库存股持有人");
    }

    @Test
    @DisplayName("开局成本必须与参考估值数值相等（10.00 与 10.0000 都合法）")
    void costMustEqualReferencePrice() {
        assertDoesNotThrow(() -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, new BigDecimal("10.00"))),
                List.of(new ShareAllocation("A", "ACME1", 1000, new BigDecimal("10.0000"),
                        ShareClass.FLOAT))), "10.0000 与 10.00 数值相等，必须接受");
        assertDoesNotThrow(() -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, new BigDecimal("10.00"))),
                List.of(new ShareAllocation("A", "ACME1", 1000, new BigDecimal("10"),
                        ShareClass.FLOAT))), "10 与 10.00 数值相等，必须接受");
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, new BigDecimal("10.00"))),
                List.of(new ShareAllocation("A", "ACME1", 1000, new BigDecimal("0.0001"),
                        ShareClass.FLOAT))), "成本与参考估值不一致");
        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, new BigDecimal("10.00"))),
                List.of(new ShareAllocation("A", "ACME1", 1000, new BigDecimal("25.0000"),
                        ShareClass.FLOAT))), "成本与参考估值不一致");
    }

    @Test
    @DisplayName("同一账户同一证券可按分类分行，同三元组重复仍拒绝")
    void sameAccountMayHoldDifferentShareClasses() {
        OpeningRegistry mixed = new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("A", "ACME1", 400, COST, ShareClass.LOCKED)));
        assertDoesNotThrow(() -> mixed.validateAgainst(cash()), "同一持有人只需一个现金账户");
        assertEquals(2, mixed.allocations().size());

        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", 1000, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("A", "ACME1", 400, COST, ShareClass.FLOAT))),
                "同账户同证券同分类重复必须拒绝");
    }

    @Test
    @DisplayName("标识含 NUL 但三元组不同时不得误判重复")
    void nulIdentifiersDoNotCreateFalseDuplicate() {
        OpeningRegistry distinct = new OpeningRegistry(parties(), owners(),
                List.of(new Security("C", "acme", 600, PRICE),
                        new Security("B\u0000C", "acme", 400, PRICE)),
                List.of(new ShareAllocation("A\u0000B", "C", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("A", "B\u0000C", 400, COST, ShareClass.FLOAT)));
        assertEquals(2, distinct.allocations().size(), "两个不同三元组不得被判重复");

        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(parties(), owners(),
                List.of(new Security("C", "acme", 1200, PRICE)),
                List.of(new ShareAllocation("A\u0000B", "C", 600, COST, ShareClass.FLOAT),
                        new ShareAllocation("A\u0000B", "C", 600, COST, ShareClass.FLOAT))),
                "真正同三元组重复仍必须拒绝");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static OpeningRegistry registry(long a, long b, long ext, long issued) {
        return new OpeningRegistry(parties(), owners(),
                List.of(new Security("ACME1", "acme", issued, PRICE)),
                List.of(new ShareAllocation("A", "ACME1", a, COST, ShareClass.FLOAT),
                        new ShareAllocation("B", "ACME1", b, COST, ShareClass.FLOAT),
                        new ShareAllocation("EXT", "ACME1", ext, COST, ShareClass.FLOAT)));
    }

    private static List<ShareAllocation> allocations(long total) {
        return List.of(new ShareAllocation("A", "ACME1", total, COST, ShareClass.FLOAT));
    }

    private static List<Party> parties() {
        return List.of(new Party("alice", PartyKind.PERSON, "Alice"),
                new Party("bob", PartyKind.PERSON, "Bob"),
                new Party("acme", PartyKind.ORGANIZATION, "Acme 制造"),
                new Party("ext-org", PartyKind.ORGANIZATION, "外部持有人"));
    }

    private static List<PropertyOwner> owners() {
        return List.of(new PropertyOwner("alice", OwnerKind.PERSON, "alice"),
                new PropertyOwner("bob", OwnerKind.PERSON, "bob"),
                new PropertyOwner("acme", OwnerKind.ORGANIZATION, "acme"),
                new PropertyOwner("ext-org", OwnerKind.ORGANIZATION, "ext-org"),
                new PropertyOwner("prod-1", OwnerKind.PRODUCT, null));
    }

    private static List<OpeningAccount> cash() {
        return List.of(
                new OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("EXT", "ext-org", new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("TREAS", "acme", new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("FUND", "prod-1", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }
}
