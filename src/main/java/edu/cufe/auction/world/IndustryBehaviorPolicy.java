package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 资源、水电、数字服务、陈酿四类公司的确定性经营策略。
 *
 * <p><b>输入边界</b>：只消费 {@code WorldRuntime.engineeringRoleInput(person)} 已经投影给本人的
 * 行业观察（{@code resourceOperations} / {@code hydroOperations} / {@code digitalOperations} /
 * {@code agedOperations}，由各 {@code XxxDecisionTools.observation} 生成）。本类<b>不</b>读世界状态、
 * 不读 God 视图、不读客户私有预算或冻结的未来需求序列，也不做任何现金计算；判断依据全部是
 * 订单、阶段、应收、现金、产能、预算这些本人可见字段。</p>
 *
 * <p><b>决策口径</b>：不按“第几日”固定下单，而是按可见事实逐次选择；没有可执行动作时返回
 * {@code WAIT} 并给出具体的等待原因（零需求、现金不足、产能用尽、预算不足、周期未到等）。
 * 到期完工、阶段完成、终端售出、关期这些系统动作不在本类中触发，因此不会把等待中的延迟或
 * 陈酿成熟期当成 0。</p>
 *
 * <p><b>去重</b>：同一期间内同一动作已被真实执行器拒绝过（回报里的 {@code accepted=false}），
 * 本类不再重复提交，只把原拒绝原因带进等待理由，避免无意义刷单。</p>
 *
 * <p><b>字段格式</b>：严格照各自 {@code XxxOperations.Request} 的契约——资源用非负整数文本，
 * 数字服务与陈酿的金额用“整数分”文本（{@code 4000} 表示 40.00），水电回款额用元的两小数文本。
 * 身份、工时、预算与成功结果一律不填，由世界层核验。</p>
 *
 * <p><b>EXP 参数</b>：试验与验证成本、采购单价、转产费、单位转换成本等均取自观察投影里的
 * 冻结实验参数（{@code stageQuotes}、{@code rawUnitCost}、{@code conversionPerUnit}、
 * {@code conversionCostPerMwh}），本类不估算、不校准、不改动这些单位经济，也不假设未来价格。</p>
 */
public final class IndustryBehaviorPolicy {

    /** 规则版本；由 Root 决定是否写进新档的 {@code dataVersions}。 */
    public static final String VERSION = "industry-visible-v1";

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    /** 数字服务与陈酿的期间长度兜底（视图未投影 {@code periodMillis} 时使用）。 */
    private static final long DEFAULT_PERIOD_MILLIS = ManufacturingTemplateModel.PERIOD_MILLIS;

    /** 水电“应收积压”阈值：满装机 2 个实验日的售电收入（EXP 判断阈值，不是现实校准值）。 */
    private static final int HYDRO_BACKLOG_DAYS = 2;

    private IndustryBehaviorPolicy() {
    }

    /**
     * 一次经营选择。
     *
     * @param company 公司标识（与观察的 {@code companyId} 一致；无权限时回填入参公司）
     * @param action  动作名（{@code WAIT} 表示不动作，其余为对应 {@code XxxOperations.Action} 的名字）
     * @param fields  该动作的全部且仅有字段；{@code WAIT} 时为空表；值全为精确文本
     * @param reason  可复查的中文理由（等待原因或该动作依据的可见事实）
     */
    public record Choice(String company, String action, Map<String, String> fields, String reason) {

        /** 冻结字段顺序并禁止空表之外的隐式字段。 */
        public Choice {
            fields = Collections.unmodifiableMap(new TreeMap<>(fields));
        }
    }

    /**
     * 按本人可见的行业观察选择一个动作。
     *
     * @param input   工程角色输入（{@code engineeringRoleInput} 的整棵 JSON），或直接是单个行业观察
     * @param company 本次要经营的公司标识
     * @param now     当前世界时点（毫秒）
     * @param id      本次决定标识，用来派生唯一业务标识（同一 {@code id}+{@code now} 重放得到同一业务标识）
     * @return 动作选择；不可经营时返回 {@code WAIT}
     */
    public static Choice choose(JsonNode input, String company, long now, String id) {
        if (company == null || company.isBlank()) {
            return waiting("", "缺少公司标识，不能替任何公司决定经营动作");
        }
        if (input == null || input.isNull() || input.isMissingNode()) {
            return waiting(company, "没有可见的工程经营输入");
        }
        View view = viewOf(input, company);
        if (view == null) {
            return waiting(company, "该公司不在本人当前可见的工程经营视图中（无现任经理任职，或该行业未开启）");
        }
        return switch (view.industry()) {
            case "resources" -> resources(view.node(), company, now, id);
            case "hydro" -> hydro(view.node(), company, now, id);
            case "digital" -> digital(view.node(), company, now, id);
            case "aged" -> aged(view.node(), company, now, id);
            default -> waiting(company, "未知行业视图：该视图没有本策略认识的字段");
        };
    }

    // ------------------------------------------------------------------ 资源公司

    /** 资源：开采 → 内部送煤 → 发电交付 → 到期收款；剩余煤在上网出力用尽后外销。 */
    private static Choice resources(JsonNode view, String company, long now, String id) {
        long periodMillis = IntegratedResourcesModel.PERIOD_MILLIS;
        long period = now / periodMillis;
        JsonNode use = view.path("periodUse").path(Long.toString(period));
        BigDecimal cash = amount(view, "cash");

        // 交付不等于回款：已到期应收优先收，收款只冲本应收。
        Choice collect = collectDue(view, company, now, periodMillis);
        if (collect != null) {
            return collect;
        }

        // 同一时刻只允许一个在制作业；完工由系统按到期触发，不能提前。
        for (JsonNode job : items(view.path("jobs"))) {
            if ("RUNNING".equals(text(job, "status"))) {
                return waiting(company, "在制作业 " + text(job, "jobId") + " 尚未到期，完工由系统触发，不能提前完成");
            }
        }

        long coalPerMwh = number(view, "coalKgPerMwh");
        if (coalPerMwh <= 0) {
            return waiting(company, "可见配置缺少每 MWh 耗煤，无法判断燃料缺口，不臆测");
        }
        long coal = number(view.path("mineCoal"), "quantity");
        long fuel = number(view.path("plantFuel"), "quantity");
        long mineLeft = Math.max(0, number(view, "mineCapacityKg") - number(use, "extractedKg"));
        long generatorLeft = Math.max(0, number(view, "generatorCapacityMwh") - number(use, "powerCapacityUsedMwh"));
        long transportLeft = Math.max(0, number(view, "transportCapacityKg") - number(use, "transportUsedKg"));

        if (generatorLeft > 0) {
            long mwh = Math.min(generatorLeft, fuel / coalPerMwh);
            if (mwh > 0) {
                BigDecimal conversion = amount(view, "conversionCostPerMwh").multiply(BigDecimal.valueOf(mwh));
                String denial = denial(view, "BEGIN_POWER", "", now, periodMillis);
                if (denial != null) {
                    return waiting(company, "本期发电已尝试被拒（" + denial + "），等条件变化后再说，不重复提交");
                }
                if (conversion.compareTo(cash) > 0) {
                    return waiting(company, "现金不足以支付本期转换成本 " + conversion.toPlainString() + "，不发电");
                }
                if (!withinBudget(view, conversion)) {
                    return waiting(company, "本期经营预算不足以支付转换成本 " + conversion.toPlainString());
                }
                return act(company, "BEGIN_POWER",
                        "厂内燃料可支撑本期剩余出力，按可见燃料与机组出力发电交付；收入要等到期收款，燃料不作零成本",
                        "jobId", business(id, "power", now),
                        "mwh", Long.toString(mwh),
                        "receivableId", business(id, "power-ar", now));
            }
            // 燃料不足：先看矿端库存，再决定内部送煤还是开采。
            long fuelGap = generatorLeft * coalPerMwh - fuel;
            if (coal > 0 && transportLeft > 0) {
                long move = Math.min(coal, Math.min(transportLeft, Math.max(1L, fuelGap)));
                return act(company, "TRANSFER_TO_PLANT",
                        "厂内燃料不足以支撑本期剩余出力，先从矿端送煤；内部转移不产生外部收入、不动现金，只占共享运力",
                        "transferId", business(id, "transfer", now),
                        "quantityKg", Long.toString(move));
            }
            if (coal > 0) {
                return waiting(company, "矿端有煤但本期共享运力已用尽，不能把煤“送”到厂内");
            }
            if (mineLeft > 0) {
                Choice extraction = extract(view, company, now, id, mineLeft, cash);
                if (extraction != null) {
                    return extraction;
                }
            }
            return waiting(company, "本期采矿能力已用尽且厂内无燃料，等待下期真实产能");
        }

        // 本期上网出力已用尽：只把超过下期燃料预留的煤外销，不把预留燃料卖掉。
        long fuelReserve = number(view, "generatorCapacityMwh") * coalPerMwh;
        long surplus = coal - fuelReserve;
        if (surplus > 0 && transportLeft > 0) {
            String denial = denial(view, "SELL_COAL", "", now, periodMillis);
            if (denial != null) {
                return waiting(company, "本期外销煤已尝试被拒（" + denial + "），客户需求与预算不可见，不重复提交");
            }
            long quantity = Math.min(surplus, transportLeft);
            return act(company, "SELL_COAL",
                    "本期机组出力已用尽，把超过下期燃料预留的煤按公开煤价外销；交付只形成应收，不等于回款",
                    "deliveryId", business(id, "sale", now),
                    "quantityKg", Long.toString(quantity),
                    "receivableId", business(id, "sale-ar", now));
        }
        if (surplus > 0) {
            return waiting(company, "本期运力已用尽，剩余煤留在矿端");
        }
        return waiting(company, "本期没有可启动的采矿或发电动作；客户需求与预算不在本人视图内，不外推");
    }

    /** 开采：储量、采矿能力、单位开采成本、现金与预算都取自可见投影。 */
    private static Choice extract(JsonNode view, String company, long now, String id, long mineLeft,
                                  BigDecimal cash) {
        String seamId = "";
        BigDecimal costPerKg = BigDecimal.ZERO;
        long remaining = 0;
        for (JsonNode seam : ordered(view.path("seams"), "seamId")) {
            String candidate = text(seam, "seamId");
            long left = view.path("remainingReservesKg").hasNonNull(candidate)
                    ? number(view.path("remainingReservesKg"), candidate) : number(seam, "reserveKg");
            if (left <= 0) {
                continue;
            }
            seamId = text(seam, "seamId");
            costPerKg = amount(seam, "extractionCostPerKg");
            remaining = left;
            break;
        }
        if (seamId.isBlank()) {
            return waiting(company, "可见矿脉没有剩余储量，不能开采");
        }
        String denial = denial(view, "BEGIN_EXTRACTION", "", now, IntegratedResourcesModel.PERIOD_MILLIS);
        if (denial != null) {
            return waiting(company, "本期开采已尝试被拒（" + denial + "），不重复提交");
        }
        long affordable = costPerKg.signum() <= 0
                ? remaining
                : cash.divide(costPerKg, 0, RoundingMode.DOWN).longValueExact();
        long quantity = Math.min(mineLeft, Math.min(remaining, Math.max(0, affordable)));
        if (quantity <= 0) {
            return waiting(company, "现金不足以支付开采成本（单位成本 " + costPerKg.toPlainString() + "），等待回款");
        }
        BigDecimal outlay = costPerKg.multiply(BigDecimal.valueOf(quantity));
        if (!withinBudget(view, outlay)) {
            return waiting(company, "本期经营预算不足以支付开采成本 " + outlay.toPlainString());
        }
        return act(company, "BEGIN_EXTRACTION",
                "无厂内燃料、无矿端库存且本期采矿能力未用尽：按单位开采成本真实付现开工，到期由系统产出煤",
                "jobId", business(id, "extract", now),
                "seamId", seamId,
                "quantityKg", Long.toString(quantity));
    }

    // ------------------------------------------------------------------ 水电

    /** 水电：按可见应收积压、合同、装机与库容调度；不推测未来来水。 */
    private static Choice hydro(JsonNode view, String company, long now, String id) {
        JsonNode plant = view.path("plant");
        JsonNode physical = view.path("physical");
        JsonNode contract = view.path("contract");
        BigDecimal cash = amount(view, "cash");
        BigDecimal price = amount(contract, "pricePerKilowattHour");
        BigDecimal outstanding = amount(plant, "deliveredKilowattHours").multiply(price)
                .setScale(2, RoundingMode.HALF_UP)
                .subtract(amount(plant, "collectedCash"))
                .max(ZERO_MONEY);
        long current = number(plant.path("dispatch"), "cubicMetresPerHour");
        long demandKw = number(contract, "demandKilowattHoursPerHour");
        if (demandKw <= 0 && current > 0) {
            return act(company, "DISPATCH_FLOW", "购电合同当期需求为 0，撤掉已有过机流量；等待不会自动关闭调度",
                    "cubicMetresPerHour", "0");
        }

        // 1 有应收就先按账面额收款（实际到账受购电方现金限制，部分结算也是真实结果）。
        if (outstanding.signum() > 0) {
            String denial = recentHydroCollectionFailure(view, now);
            if (denial == null) {
                return act(company, "COLLECT",
                        "已交付电量形成应收 " + outstanding.toPlainString() + "，先按账面未收额回款；回款只冲应收，不确认新收入",
                        "amount", outstanding.setScale(2, RoundingMode.DOWN).toPlainString());
            }
            // 2 回款被拒且积压已超过满装机若干实验日的收入：停止无回款供电。
            BigDecimal backlogLimit = price
                    .multiply(BigDecimal.valueOf(number(physical, "capacityKw")))
                    .multiply(BigDecimal.valueOf(24L * HYDRO_BACKLOG_DAYS))
                    .setScale(2, RoundingMode.DOWN);
            if (outstanding.compareTo(backlogLimit) > 0) {
                String reason = "购电方应收已积压 " + outstanding.toPlainString() + "（上限 "
                        + backlogLimit.toPlainString() + "），最近一日回款被拒（" + denial + "）";
                return current > 0 ? act(company, "DISPATCH_FLOW", reason + "，撤掉已有过机流量，停止扩大应收",
                        "cubicMetresPerHour", "0") : waiting(company, reason + "，保持停供，等待回款条件改变");
            }
        }

        // 3 停机期间不调度。
        JsonNode outage = plant.path("outage");
        if (!outage.isMissingNode() && !outage.isNull() && number(outage, "untilMillis") > now) {
            return waiting(company, "检修停机至 " + number(outage, "untilMillis") + "，期间不能发电");
        }

        long until = number(contract, "untilMillis");
        if (until > 0 && now >= until) {
            return waiting(company, "购电合同已到期，不能再按该合同上网，不虚构售电");
        }

        // 4 目标出力：合同需求与装机取小，再受可见库容（死库容以上）约束。
        long capacityKw = number(physical, "capacityKw");
        BigDecimal kwhPerCubicMetre = amount(physical, "kilowattHoursPerCubicMetre");
        if (capacityKw <= 0 || kwhPerCubicMetre.signum() <= 0) {
            return waiting(company, "可见物理参数不完整，无法把水量折算成电量");
        }
        long storage = number(plant, "storageCubicMetres");
        long deadStorage = number(physical, "deadStorageCubicMetres");
        long usableWater = Math.max(0, storage - deadStorage);
        long storableKwh = kwhPerCubicMetre.multiply(BigDecimal.valueOf(usableWater))
                .setScale(0, RoundingMode.DOWN).longValueExact();
        long target = Math.min(capacityKw, demandKw);
        long outputKw = Math.min(target, storableKwh);
        if (target <= 0) {
            return waiting(company, "购电合同当期需求为 0，不发电");
        }
        if (outputKw <= 0) {
            return waiting(company, "库容只剩死库容以上 " + usableWater + " m³，无可见可发水量（来水未知，不虚构）");
        }
        long flow = kwhPerCubicMetre.signum() <= 0 ? 0
                : BigDecimal.valueOf(outputKw).divide(kwhPerCubicMetre, 0, RoundingMode.FLOOR).longValueExact();
        if (flow <= 0) {
            return waiting(company, "目标出力折算过机流量不足 1 m³/h，无法调度");
        }
        long maxFlow = number(physical, "maxTurbineCubicMetresPerHour");
        if (maxFlow > 0 && flow > maxFlow) {
            flow = maxFlow;
        }
        if (current == flow) {
            return waiting(company, "当前调度已等于目标流量 " + flow + " m³/h，不重复登记同一调度");
        }
        if (cash.signum() < 0) {
            return waiting(company, "公司现金为负，先处理收款，不扩大发电");
        }
        return act(company, "DISPATCH_FLOW",
                "按公开合同需求、装机、机组过水上限及可见库容设定实际流量 " + flow + " m³/h；真实电量仍由物理结算决定",
                "cubicMetresPerHour", Long.toString(flow));
    }

    // ------------------------------------------------------------------ 数字服务

    /** 数字服务：到期收款 → 履行合同交付 → 试验/验证 → 商业化登记 → 签约。 */
    private static Choice digital(JsonNode view, String company, long now, String id) {
        long periodMillis = DigitalServiceModel.PERIOD_MILLIS;
        long period = now / periodMillis;
        BigDecimal cash = amount(view, "cash");

        Choice collect = collectDue(view, company, now, periodMillis);
        if (collect != null) {
            return collect;
        }

        // 1 在履行合同先交付：交付才确认收入与服务成本。
        for (JsonNode contract : ordered(view.path("contracts"), "contractId")) {
            if (!"ACTIVE".equals(text(contract, "status"))) {
                continue;
            }
            long remaining = number(contract, "quantity") - number(contract, "delivered");
            if (remaining <= 0) {
                continue;
            }
            long capacity = number(view, "serviceCapacityUnitsPerPeriod");
            long units = Math.min(remaining, Math.max(0, capacity - deliveredThisPeriod(view, period)));
            if (units <= 0) {
                return waiting(company, "本期服务产能已用尽，交付不能提前或跨期补记");
            }
            BigDecimal cost = amount(view, "serviceUnitCost").multiply(BigDecimal.valueOf(units));
            if (cost.compareTo(cash) > 0) {
                return waiting(company, "现金不足以支付本次交付的服务成本 " + cost.toPlainString());
            }
            if (!withinBudget(view, cost)) {
                return waiting(company, "本期经营预算不足以支付服务成本 " + cost.toPlainString());
            }
            String denial = denial(view, "DELIVER", text(contract, "contractId"), now, periodMillis);
            if (denial != null) {
                return waiting(company, "本期交付已尝试被拒（" + denial + "），不重复提交");
            }
            return act(company, "DELIVER",
                    "按合同剩余量与本期剩余产能交付；未收部分转应收，到期才可收，客户余额不作假设",
                    "contractId", text(contract, "contractId"),
                    "units", Long.toString(units),
                    "receivableId", business(id, "delivery-ar", now));
        }

        if (view.path("researchFailed").asBoolean(false)) {
            return waiting(company, "研究阶段已失败，机制上不得重试；只能等既有合同与应收处理完");
        }

        JsonNode stages = view.path("stages");
        JsonNode pilot = stages.path("PILOT");
        JsonNode validation = stages.path("VALIDATION");

        if (pilot.isMissingNode() || pilot.isNull()) {
            BigDecimal cost = amount(view.path("stageQuotes").path("PILOT"), "cost");
            if (cost.compareTo(cash) > 0) {
                return waiting(company, "现金不足以支付试验（PILOT）支出 " + cost.toPlainString());
            }
            if (!withinBudget(view, cost)) {
                return waiting(company, "本期预算不足以支付试验支出 " + cost.toPlainString());
            }
            String denial = denial(view, "BEGIN_PILOT", "", now, periodMillis);
            if (denial != null) {
                return waiting(company, "本期试验开工已尝试被拒（" + denial + "），不重复提交");
            }
            return act(company, "BEGIN_PILOT",
                    "尚无试验记录且现金覆盖公开报价：开工即费用化，成败在到期时由冻结情景给出，经理不得自报",
                    new String[0]);
        }
        if ("RUNNING".equals(text(pilot, "status"))) {
            return waiting(company, "试验（PILOT）在制，到期由系统完成，不能提前");
        }
        if ("PASSED".equals(text(pilot, "status")) && (validation.isMissingNode() || validation.isNull())) {
            BigDecimal cost = amount(view.path("stageQuotes").path("VALIDATION"), "cost");
            if (cost.compareTo(cash) > 0) {
                return waiting(company, "现金不足以支付验证（VALIDATION）支出 " + cost.toPlainString());
            }
            if (!withinBudget(view, cost)) {
                return waiting(company, "本期预算不足以支付验证支出 " + cost.toPlainString());
            }
            String denial = denial(view, "BEGIN_VALIDATION", "", now, periodMillis);
            if (denial != null) {
                return waiting(company, "本期验证开工已尝试被拒（" + denial + "），不重复提交");
            }
            return act(company, "BEGIN_VALIDATION",
                    "试验已通过且尚无验证记录：开工先计入在研，通过后转平台资产并按期摊销，失败转减值不得重试",
                    new String[0]);
        }
        if (!validation.isMissingNode() && !validation.isNull()
                && "RUNNING".equals(text(validation, "status"))) {
            return waiting(company, "验证（VALIDATION）在制，到期由系统完成，不能提前");
        }
        boolean validated = !validation.isMissingNode() && !validation.isNull()
                && "PASSED".equals(text(validation, "status"));
        if (!validated) {
            return waiting(company, "尚无通过的验证阶段，不能签约收费");
        }
        if (!view.path("commercialApproved").asBoolean(false)) {
            String permit = text(view, "commercialPermit");
            if (permit.isBlank()) {
                return waiting(company, "尚无可引用的商业化批准，经理不能自授许可");
            }
            return act(company, "APPROVE_COMMERCIAL",
                    "验证已通过，按观察里已有的真实批准引用登记商业化事实；只是授权事实，不产生合同与收入",
                    "approvalId", permit);
        }

        // 2 签约：本期还没有合同、也没有在履行合同，才占用公开需求额度。
        boolean activeContract = false;
        boolean signedThisPeriod = false;
        for (JsonNode contract : items(view.path("contracts"))) {
            if ("ACTIVE".equals(text(contract, "status"))) {
                activeContract = true;
            }
            if (number(contract, "period") == period) {
                signedThisPeriod = true;
            }
        }
        if (activeContract || signedThisPeriod) {
            return waiting(company, "本期需求额度已有合同占用，等既有合同交付完再签约");
        }
        long demand = number(view, "customerDemandUnitsPerPeriod");
        long capacity = number(view, "serviceCapacityUnitsPerPeriod");
        long quantity = Math.min(demand, capacity);
        if (quantity <= 0) {
            return waiting(company, "公开客户需求或服务产能为 0，无法签约");
        }
        String denial = denial(view, "SIGN_CONTRACT", "", now, periodMillis);
        if (denial != null) {
            return waiting(company, "本期签约已尝试被拒（" + denial + "），客户预算不公开，不重复硬试");
        }
        return act(company, "SIGN_CONTRACT",
                "验证与商业化批准均已成立：按公开当期需求上限与产能签约，预收 0 元以避免假设客户现金，未收部分交付时转应收",
                "contractId", business(id, "contract", now),
                "quantityUnits", Long.toString(quantity),
                "prepaidCents", "0");
    }

    /** 本期已确认交付量：由本人可见应收的确认时点反推，不用隐藏台账。 */
    private static long deliveredThisPeriod(JsonNode view, long period) {
        long units = 0;
        for (JsonNode receivable : items(view.path("receivables"))) {
            if (number(receivable, "recognizedAt") / DigitalServiceModel.PERIOD_MILLIS == period) {
                units += number(receivable, "units");
            }
        }
        return units;
    }

    // ------------------------------------------------------------------ 陈酿

    /** 陈酿：渠道回款 → 成熟批次发货 → 采购到货 → 投产 → 补采购 → 退款/固定费。 */
    private static Choice aged(JsonNode view, String company, long now, String id) {
        long periodMillis = number(view, "periodMillis");
        if (periodMillis <= 0) {
            periodMillis = DEFAULT_PERIOD_MILLIS;
        }
        long period = now / periodMillis;
        BigDecimal cash = amount(view, "cash");
        long capacity = number(view, "capacityPerPeriod");
        BigDecimal rawUnitCost = amount(view, "rawUnitCost");
        BigDecimal conversionPerUnit = amount(view, "conversionPerUnit");
        long agingPeriods = number(view, "agingPeriods");

        // 1 渠道应收先回款：回款只冲应收，受渠道现金限制。
        for (JsonNode ledger : ordered(view.path("channelLedgers"), "channelId")) {
            BigDecimal receivable = amount(ledger, "receivable");
            if (receivable.signum() <= 0) {
                continue;
            }
            String channelId = text(ledger, "channelId");
            String denial = denial(view, "COLLECT", "", now, periodMillis);
            if (denial != null) {
                return waiting(company, "渠道 " + channelId + " 本期回款已尝试被拒（" + denial + "），不重复提交");
            }
            return act(company, "COLLECT",
                    "渠道已售出形成应收 " + receivable.toPlainString() + "，先按账面额回款；回款只冲应收、不确认新收入",
                    "channelId", channelId,
                    "amountCents", cents(receivable));
        }

        // 2 成熟批次先按渠道公开配额发货（寄售）。
        long mature = 0;
        long shelfPeriods = number(view, "shelfPeriods");
        for (JsonNode batch : items(view.path("batches"))) {
            if (number(batch, "remaining") <= 0) {
                continue;
            }
            long matureAt = number(batch, "matureAtPeriod");
            if (period < matureAt || period >= matureAt + shelfPeriods) {
                continue;
            }
            mature += number(batch, "remaining");
        }
        if (mature > 0) {
            for (JsonNode channel : ordered(view.path("channels"), "channelId")) {
                String channelId = text(channel, "channelId");
                long quota = number(channel, "quotaPerPeriod");
                if (quota <= 0) {
                    continue;
                }
                BigDecimal price = amount(view.path("currentPrices"), channelId);
                BigDecimal floor = amount(channel, "priceFloor");
                if (floor.signum() > 0 && price.compareTo(floor) < 0) {
                    return act(company, "SET_PRICE",
                            "该渠道当前寄售价低于公开底价（或尚未定价），先设到公开底价；不改动既有寄售 lot 与成交",
                            "channelId", channelId,
                            "priceCents", cents(floor));
                }
                long units = Math.min(mature, quota);
                String denial = denial(view, "SHIP", channelId, now, periodMillis);
                if (denial != null) {
                    return waiting(company, "渠道 " + channelId + " 本期发货已尝试被拒（" + denial + "），不重复提交");
                }
                return act(company, "SHIP",
                        "有陈酿满期的自有批次且渠道配额未用尽：按寄售发货，渠道按公开比例预付，发货不确认收入",
                        "shipmentId", business(id, "ship", now),
                        "channelId", channelId,
                        "units", Long.toString(units));
            }
        }

        // 3 采购到货：到约定到货期才付款建原料 lot。
        for (JsonNode order : ordered(view.path("orders"), "orderId")) {
            if (number(order, "duePeriod") > period) {
                continue;
            }
            BigDecimal payable = amount(order, "unitCost")
                    .multiply(BigDecimal.valueOf(number(order, "units")));
            if (payable.compareTo(cash) > 0) {
                return waiting(company, "原料到货应付 " + payable.toPlainString() + " 超过可见现金，不虚构到货");
            }
            if (!withinBudget(view, payable)) {
                return waiting(company, "本期预算不足以支付原料到货款 " + payable.toPlainString());
            }
            return act(company, "RECEIVE_RAW",
                    "采购单已到约定到货期，按订单单价真实付款并建原料 lot；下单阶段不付现",
                    "orderId", text(order, "orderId"));
        }

        // 4 投产：原料 FIFO、付转产费，批次要陈酿满期才可发货。
        long rawAvailable = 0;
        for (JsonNode lot : items(view.path("rawLots"))) {
            rawAvailable += number(lot, "remaining");
        }
        long producedThisPeriod = 0;
        for (JsonNode batch : items(view.path("batches"))) {
            if (number(batch, "producedAtPeriod") == period) {
                producedThisPeriod += number(batch, "units");
            }
        }
        long capacityLeft = Math.max(0, capacity - producedThisPeriod);
        if (rawAvailable > 0 && capacityLeft > 0) {
            long units = Math.min(rawAvailable, capacityLeft);
            BigDecimal conversion = conversionPerUnit.multiply(BigDecimal.valueOf(units));
            if (conversion.compareTo(cash) > 0) {
                return waiting(company, "现金不足以支付本次转产费 " + conversion.toPlainString());
            }
            if (!withinBudget(view, conversion)) {
                return waiting(company, "本期预算不足以支付转产费 " + conversion.toPlainString());
            }
            return act(company, "PRODUCE",
                    "有原料且本期产能未用尽：投产并支付转产费；批次需陈酿满 " + agingPeriods + " 期才可发货，成熟期不置零",
                    "batchId", business(id, "batch", now),
                    "units", Long.toString(units));
        }

        // 5 补采购：只补到覆盖一期产能，不囤积超过产能的原料。
        long onOrder = 0;
        for (JsonNode order : items(view.path("orders"))) {
            onOrder += number(order, "units");
        }
        long gap = capacity - (rawAvailable + onOrder);
        if (gap > 0 && capacity > 0) {
            BigDecimal outlay = rawUnitCost.multiply(BigDecimal.valueOf(gap));
            if (outlay.compareTo(cash) > 0) {
                return waiting(company, "补足一期原料的现金不足（下单不付现，但到货要付），不虚报采购");
            }
            return act(company, "ORDER_RAW",
                    "原料在手与在途不足以覆盖一期产能：按公开采购价补足，下单不付现，到货才付供应商",
                    "orderId", business(id, "order", now),
                    "units", Long.toString(gap));
        }

        // 6 只在一期生产资金之外还有余量时清退款与固定费，不挤占生产现金。
        BigDecimal productionReserve = rawUnitCost.add(conversionPerUnit)
                .multiply(BigDecimal.valueOf(Math.max(1L, capacity)));
        for (JsonNode ledger : ordered(view.path("channelLedgers"), "channelId")) {
            BigDecimal refundPayable = amount(ledger, "refundPayable");
            if (refundPayable.signum() <= 0) {
                continue;
            }
            String channelId = text(ledger, "channelId");
            if (cash.subtract(refundPayable).compareTo(productionReserve) < 0) {
                return waiting(company, "现金需保留一期生产资金，暂不退还渠道未抵预收 " + refundPayable.toPlainString());
            }
            return act(company, "PAY_REFUND",
                    "渠道有未抵扣预收形成的退款义务，且现金扣除一期生产资金后仍可覆盖，按账面额退还",
                    "channelId", channelId,
                    "amountCents", cents(refundPayable));
        }
        BigDecimal fixedPayable = amount(view, "fixedPayable");
        if (fixedPayable.signum() > 0) {
            if (cash.subtract(fixedPayable).compareTo(productionReserve) < 0) {
                return waiting(company, "现金需保留一期生产资金，暂不支付未付固定费 " + fixedPayable.toPlainString());
            }
            return act(company, "PAY_FIXED",
                    "已有应计未付固定费，且支付后仍保留一期生产资金，按账面额支付",
                    "amountCents", cents(fixedPayable));
        }
        return waiting(company, "等待陈酿满期、渠道需求或回款；渠道未来需求与终端现金不公开，不推测");
    }

    // ------------------------------------------------------------------ 公共

    /** 回款失败后至少间隔一个实验日；跨日历日或跨物理时间格不提前重试。 */
    private static String recentHydroCollectionFailure(JsonNode view, long now) {
        long latest = -1;
        String reason = null;
        for (JsonNode report : items(view.path("reports"))) {
            long at = number(report, "timeMillis");
            if ("COLLECT".equals(text(report, "action")) && !report.path("accepted").asBoolean(true)
                    && at <= now && now - at < ManufacturingTemplateModel.DAY_MILLIS && at >= latest) {
                latest = at;
                reason = text(report, "reason");
            }
        }
        return reason;
    }

    /**
     * 到期应收先回款；同一期间内同一应收的收款已被拒时直接返回等待，不再重复提交。
     *
     * @return 有到期应收时的选择，否则 {@code null}
     */
    private static Choice collectDue(JsonNode view, String company, long now, long periodMillis) {
        for (JsonNode receivable : ordered(view.path("receivables"), "receivableId")) {
            if (!"OPEN".equals(text(receivable, "status"))) {
                continue;
            }
            if (number(receivable, "dueAt") > now) {
                continue;
            }
            String receivableId = text(receivable, "receivableId");
            String denial = denial(view, "COLLECT", receivableId, now, periodMillis);
            if (denial != null) {
                return waiting(company, "到期应收 " + receivableId + " 本期已尝试收款被拒（" + denial
                        + "），不重复提交，等真实回款条件");
            }
            return act(company, "COLLECT",
                    "已到期应收优先回收；收款只冲应收，不确认新收入，也不假设客户余额",
                    "receivableId", receivableId);
        }
        return null;
    }

    /** 视图定位：先按四类行业键匹配 {@code companyId}，再支持直接传入单个行业观察。 */
    private static View viewOf(JsonNode input, String company) {
        View view = match(input.get("resourceOperations"), company, "resources");
        if (view != null) {
            return view;
        }
        view = match(input.get("hydroOperations"), company, "hydro");
        if (view != null) {
            return view;
        }
        view = match(input.get("digitalOperations"), company, "digital");
        if (view != null) {
            return view;
        }
        view = match(input.get("agedOperations"), company, "aged");
        if (view != null) {
            return view;
        }
        // Root 也可能只把本公司那一段观察传进来：按行业独有字段识别。
        if (company.equals(text(input, "companyId"))) {
            if (input.has("remainingReservesKg") || input.has("seams")) {
                return new View("resources", input);
            }
            if (input.has("physical") || input.has("plant")) {
                return new View("hydro", input);
            }
            if (input.has("stages") || input.has("stageQuotes")) {
                return new View("digital", input);
            }
            if (input.has("batches") || input.has("shipmentLots")) {
                return new View("aged", input);
            }
        }
        return null;
    }

    private static View match(JsonNode node, String company, String industry) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return company.equals(text(node, "companyId")) ? new View(industry, node) : null;
    }

    /** 行业名 + 该行业的观察节点。 */
    private record View(String industry, JsonNode node) {
    }

    /** 同一期间内同动作（可选同业务标识）的真实拒绝原因；没有则 {@code null}。 */
    private static String denial(JsonNode view, String action, String businessId, long now,
                                 long periodMillis) {
        long window = Math.max(1L, periodMillis);
        long period = now / window;
        String denial = null;
        for (JsonNode report : items(view.path("reports"))) {
            if (!action.equals(text(report, "action"))) {
                continue;
            }
            if (report.path("accepted").asBoolean(true)) {
                continue;
            }
            if (businessId != null && !businessId.isBlank()) {
                String tried = text(report, "businessId");
                if (!tried.isBlank() && !tried.equals(businessId)) {
                    continue;
                }
            }
            if (number(report, "timeMillis") / window != period) {
                continue;
            }
            denial = text(report, "reason");
        }
        return denial;
    }

    /** 预算约束：视图未投影预算（null/缺失）时不额外加限制，由世界层与纯核裁决。 */
    private static boolean withinBudget(JsonNode view, BigDecimal cost) {
        JsonNode budget = view.path("remainingBudget");
        if (budget.isMissingNode() || budget.isNull()) {
            return true;
        }
        return amount(view, "remainingBudget").compareTo(cost) >= 0;
    }

    private static Choice waiting(String company, String reason) {
        return new Choice(company, "WAIT", Map.of(), reason);
    }

    private static Choice act(String company, String action, String reason, String... pairs) {
        Map<String, String> fields = new TreeMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            fields.put(pairs[index], pairs[index + 1]);
        }
        return new Choice(company, action, fields, reason);
    }

    /**
     * 派生唯一业务标识：同一 {@code id} 与同一 {@code now} 重放得到同一标识（幂等），
     * 不同时点的同类动作不会互相覆盖。
     */
    private static String business(String id, String kind, long now) {
        String base = id == null || id.isBlank() ? "policy" : id;
        return base + "-" + kind + "-" + now;
    }

    /** 金额转“整数分”文本；不经过浮点。 */
    private static String cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN).movePointRight(2)
                .setScale(0, RoundingMode.DOWN).toBigIntegerExact().toString();
    }

    private static BigDecimal amount(JsonNode node, String field) {
        return node == null || node.isMissingNode() || node.isNull()
                ? ZERO_MONEY : decimal(node.path(field));
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return ZERO_MONEY;
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return ZERO_MONEY;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            return ZERO_MONEY;
        }
    }

    private static long number(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0L;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            try {
                return new BigDecimal(text).longValue();
            } catch (NumberFormatException stillInvalid) {
                return 0L;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null || node.isMissingNode() || node.isNull()
                ? "" : node.path(field).asText("");
    }

    /** 数组或按标识索引的对象都摊平成行，便于统一处理各行业的投影形状。 */
    private static List<JsonNode> items(JsonNode node) {
        List<JsonNode> rows = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return rows;
        }
        if (node.isArray() || node.isObject()) {
            node.elements().forEachRemaining(rows::add);
        }
        return rows;
    }

    private static List<JsonNode> ordered(JsonNode node, String key) {
        List<JsonNode> rows = items(node);
        rows.sort(Comparator.comparing(row -> text(row, key)));
        return rows;
    }
}
