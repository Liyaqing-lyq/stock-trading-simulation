package edu.cufe.auction.web;

import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.InterestAccrual;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人托管受益投影的权限回归：同池两位客户金额不同，本人只拿到自己那一份。
 *
 * <p><b>真实缺陷</b>：{@code personalBankView} 早期版本按 owner 循环却把
 * {@code run.custodyDetailTotal(custodian)}（全客户池合计）当成本人金额，任何有账户的人
 * 都能看到别人的托管钱，多属主还会重复计入。</p>
 *
 * <p><b>夹具来源</b>：托管明细账由核心 {@link CustodyBook#restoreStructural} 按真实档案指纹与
 * 键结构还原（不是手写 JSON、不是 mock 账）；档案与客户 key 都走真实
 * {@link BankingProfile}/{@link BankConfig}。经济口径（池资产 == 客户明细合计）由核心开局校验，
 * 本测试不重复造那份证据。</p>
 *
 * <p><b>覆盖边界</b>：这是投影层的定向回归，不是"托管权限已验收"；真实浏览器与
 * 带托管人的完整 World 运行时（本构建要求 manufacturing/governance 配置成对）未在此覆盖。</p>
 */
class WorldWebCustodyProjectionTest {

    private static final String CUSTODIAN = "custodian";
    private static final String A = "cust-a";
    private static final String B = "cust-b";

    @Test
    @DisplayName("同一托管池 A=120、B=180：本人只拿到自己那一份，池合计 300 不外泄")
    void perOwnerAmountsNotPoolTotal() {
        BankingProfile profile = profile();
        BankConfig config = config(profile);
        CustodyBook book = CustodyBook.restoreStructural("custody-projection-test",
                config.fingerprint(),
                Map.of(CUSTODIAN + "|" + A, money("120.00"),
                        CUSTODIAN + "|" + B, money("180.00")),
                Map.of(CUSTODIAN, "EXP 托管客户明细来源"),
                Map.of(), config);

        // 池合计是 300，只能用于 God 审计（旧 BankRun.custodyDetailTotal 的口径）
        BigDecimal pool = book.detailOf(CUSTODIAN, A).add(book.detailOf(CUSTODIAN, B));
        assertEquals("300.00", pool.toPlainString(),
                "池合计必须是两位客户之和（这是 God 口径）");

        // 旧实现的反例（红）：把池合计当成本人金额
        BigDecimal leaked = pool;
        assertEquals("300.00", leaked.toPlainString(),
                "旧投影取的就是这个池合计（所以 A 会看到 300，红）");
        assertFalse("120.00".equals(leaked.toPlainString()),
                "池合计不等于本人真实的 120.00，故必须按 owner 求明细");

        List<Map<String, Object>> aRows = WorldWebHost.personalCustodyRows(
                Set.of(A), profile, book);
        assertEquals(1, aRows.size(), "A 只能有一条托管明细");
        assertEquals(A, aRows.get(0).get("ownerId"));
        assertEquals(A, aRows.get(0).get("partyKey"));
        assertEquals(CUSTODIAN, aRows.get(0).get("custodian"));
        assertEquals("120.00", aRows.get(0).get("amount"),
                "A 的金额必须是自己的 120.00，不能是池合计 300.00");

        List<Map<String, Object>> bRows = WorldWebHost.personalCustodyRows(
                Set.of(B), profile, book);
        assertEquals(1, bRows.size());
        assertEquals("180.00", bRows.get(0).get("amount"));

        // 未授权 owner（包括没有 party 的 owner）什么也拿不到
        assertTrue(WorldWebHost.personalCustodyRows(Set.of("intruder"), profile, book)
                .isEmpty(), "未授权 owner 不得拿到任何托管明细");
        assertTrue(WorldWebHost.personalCustodyRows(Set.of("custodian-owner"), profile, book)
                .isEmpty(), "托管人自己的 owner 不走客户明细");

        // 多属主：各自一行、带 owner 身份、不重复也不合并成池
        Set<String> both = new TreeSet<>(List.of(A, B));
        List<Map<String, Object>> bothRows = WorldWebHost.personalCustodyRows(both, profile, book);
        assertEquals(2, bothRows.size(), "两个属主各一行，不得重复计入");
        assertEquals("120.00", bothRows.get(0).get("amount"));
        assertEquals(A, bothRows.get(0).get("ownerId"));
        assertEquals("180.00", bothRows.get(1).get("amount"));
        assertEquals(B, bothRows.get(1).get("ownerId"));
        for (Map<String, Object> row : bothRows) {
            assertFalse("300.00".equals(row.get("amount")), "任何一行都不得是池合计");
        }

        // 同一 owner 重复传入不会重复出行
        List<Map<String, Object>> duplicated = WorldWebHost.personalCustodyRows(
                new TreeSet<>(List.of(A, A)), profile, book);
        assertEquals(1, duplicated.size(), "同一 owner 不得重复出行");

        // 空 owner 集合没有明细，不报错也不回落到池
        assertTrue(WorldWebHost.personalCustodyRows(Set.of(), profile, book).isEmpty());
    }

    private static BankingProfile profile() {
        return new BankingProfile(
                Map.of("north", new BankingProfile.BankRef("north", "north", "north-owner",
                        "RSV-NORTH")),
                Map.of(A, new BankingProfile.PartyRef(A, A, "CUST-A", null),
                        B, new BankingProfile.PartyRef(B, B, "CUST-B", null)),
                Map.of(CUSTODIAN, new BankingProfile.CustodyRef(CUSTODIAN, "custodian-owner",
                        "north", "dep-cust", BankRules.CREDIT_NON_ACCRUAL)),
                Map.of(), Map.of(), List.of(), List.of(), List.of("dep-cust"), null, null, null);
    }

    private static BankConfig config(BankingProfile profile) {
        return new BankConfig.Builder(profile).version(19).archiveId("CUSTODY-PROJECTION-EXP")
                .label("custody-projection-exp").coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365())
                .depositOffers(Map.of("north", new BankConfig.DepositOffer("north", "OFF-north",
                        InterestAccrual.rateU(new BigDecimal("0.02")), 365L * 86_400_000L,
                        BankRules.CREDIT_OFFER)))
                .groupMap(Map.of())
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
