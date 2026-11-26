package edu.cufe.auction.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.bank.Fund;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 冻结开局中的个人自述和储备目标，以本人账本和订单冻结金额计算当前缺口。 */
final class PersonalOpeningProfiles {
    static final String VERSION = "household-plans-v1";
    private static final long DAY = 86_400_000L;
    private static final String SOURCE = "/world/household-plans-v1.json";
    private static final byte[] SOURCE_BYTES = sourceBytes();
    private static final Map<String, Plan> PLANS = loadPlans();

    private PersonalOpeningProfiles() { }

    private record Plan(String purpose, String initialExperience, String tradeoff,
                        String reviewCondition, String worldQuestion, long reviewDay, BigDecimal targetLiquidCash) { }

    private static byte[] sourceBytes() {
        try (var stream = Objects.requireNonNull(PersonalOpeningProfiles.class.getResourceAsStream(SOURCE))) {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return source.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Map<String, Plan> loadPlans() {
        try {
            return new ObjectMapper().readValue(SOURCE_BYTES, new TypeReference<Map<String, Plan>>() { });
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SOURCE_BYTES));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void append(ObjectNode engineering, String person, long now, List<String> ownedAccounts,
                       List<CashLedger.OpeningAccount> opening, Map<String, CashLedger.CashAccount> accounts,
                       WorldExchange.State exchange, boolean cashSettlement) {
        Plan plan = PLANS.get(person);
        if (plan == null) return;
        var goals = engineering.withObject("/deliberation").withArray("objectives");
        String id = "person:" + person;
        ObjectNode goal = null;
        for (var existing : goals) {
            if (id.equals(existing.path("id").textValue())) goal = (ObjectNode) existing;
        }
        if (goal == null) goal = goals.addObject().put("id", id);
        goal.put("purpose", plan.purpose()).put("tradeoff", plan.tradeoff())
                .put("source", "EXP开局本人自述；私人经历与关切不产生岗位权限、证券持有或机构履历。");
        long reviewAt = Math.multiplyExact(plan.reviewDay(), DAY);
        var detail = goal.putObject("personalPlan").put("sourceId", VERSION + "/" + person)
                .put("initialExperience", plan.initialExperience()).put("reviewCondition", plan.reviewCondition())
                .put("worldQuestion", plan.worldQuestion()).put("reviewAt", reviewAt).put("reviewDue", now >= reviewAt);
        if (plan.targetLiquidCash() != null) detail.put("targetLiquidCash", plan.targetLiquidCash().toPlainString());
        if (ownedAccounts.isEmpty()) return;
        detail.put("scope", "目标金额供本人安排。到期只供复查；支付、收款和成交须有各自账本记录。本人证券数量和冻结见profile.holdings中下列账户。");
        var sources = detail.putArray("ownedAccounts");
        BigDecimal available = BigDecimal.ZERO.setScale(2);
        BigDecimal trading = BigDecimal.ZERO.setScale(2);
        var settlement = cashSettlement ? SettlementCash.projection(exchange, now) : null;
        for (var original : opening) {
            String account = original.accountId();
            if (!ownedAccounts.contains(account)) continue;
            sources.addObject().put("accountId", account).put("openingCash", original.cash().toPlainString())
                    .put("openingSource", original.source());
            var balance = accounts.get(account).cash();
            if (settlement == null) {
                available = available.add(balance.subtract(WorldExchange.frozenCash(exchange, account)));
            } else {
                var wallet = settlement.balance(new Fund.Cash(account),balance,false);
                available = available.add(wallet.withdrawableCash());
                trading = trading.add(wallet.tradingAvailable());
            }
        }
        if (settlement != null) {
            detail.put("tradingAvailableCash", trading.toPlainString());
            detail.put("cashBasis", "availableCash 为本人下列现金账户现在可转出的金额，cashShortfall 按此计算。可再买证券的金额另列。存款及托管另见本人账户。");
        }
        detail.put("availableCash", available.toPlainString());
        if (plan.targetLiquidCash() != null) detail.put("cashShortfall",
                plan.targetLiquidCash().subtract(available).max(BigDecimal.ZERO.setScale(2)).toPlainString());
    }
}
