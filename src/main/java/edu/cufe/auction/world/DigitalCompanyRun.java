package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 同一世界里数字技术服务公司的唯一子账：公司身份、证券代码、冻结经营配置与纯核台账。
 *
 * <p><b>不复制现金</b>：余额只存在世界 {@link FundState} 的 {@code CashLedger} 账户表里。
 * 本类每次只被读取，候选由 {@link DigitalOperations#apply} 返回后整体替换；这里没有第二份余额、
 * 也没有缓存的开局价。</p>
 *
 * <p><b>身份分工</b>：纯核的 {@code actor} 是公司主体（{@code model.companyOwnerId()}），人物身份、
 * 任职与执行权限由世界层核验；适配层不接受任何自报主体。证券代码只做身份绑定，不产生撮合。</p>
 *
 * <p><b>冻结结果不落在人物身上</b>：阶段成败（{@code pilotPasses} / {@code validationPasses}）
 * 在 {@link DigitalServiceModel.Config} 里冻结，完成时的观察引用在 {@link Config#stageEvidence()}
 * 里冻结；它们都不进入任何人的观察投影。</p>
 *
 * @param companyPartyId 公司组织标识
 * @param symbol         该公司证券代码
 * @param config         冻结运行配置（含纯核配置、阶段观察引用、治理决议）
 * @param ledger         唯一纯核台账
 */
public record DigitalCompanyRun(String companyPartyId, String symbol, Config config,
                                DigitalServiceModel.State ledger) {

    /** 校验身份、配置与台账三者绑定。 */
    public DigitalCompanyRun {
        requireText(companyPartyId, "公司主体");
        requireText(symbol, "证券代码");
        Objects.requireNonNull(config, "冻结配置");
        Objects.requireNonNull(ledger, "数字服务台账");
        if (!companyPartyId.equals(config.companyPartyId())
                || !companyPartyId.equals(ledger.config().companyOwnerId())) {
            throw new IllegalArgumentException("公司主体与配置或台账不一致：" + companyPartyId);
        }
    }

    /**
     * 在本世界开局现金上建立该公司子账。
     *
     * @param config 冻结配置
     * @param funds  世界资金三态（账户必须已按配置开户）
     * @return 开局子账
     */
    public static DigitalCompanyRun open(Config config, FundState funds) {
        Objects.requireNonNull(config, "冻结配置");
        return new DigitalCompanyRun(config.companyPartyId(), config.symbol(), config,
                DigitalServiceModel.open(config.model(), funds));
    }

    /**
     * 用候选台账替换，保留身份、证券与冻结配置。
     *
     * @param next 候选台账
     * @return 新子账
     */
    public DigitalCompanyRun withLedger(DigitalServiceModel.State next) {
        return new DigitalCompanyRun(companyPartyId, symbol, config,
                Objects.requireNonNull(next, "候选台账"));
    }

    /**
     * 查某阶段的冻结观察引用；找不到返回 {@code null}，由适配层以 {@code UNKNOWN_EVIDENCE} 拒绝。
     *
     * @param stage      阶段
     * @param evidenceId 引用标识
     * @return 冻结证据或 null
     */
    public DigitalServiceModel.Evidence evidenceOf(DigitalServiceModel.Stage stage, String evidenceId) {
        Map<String, DigitalServiceModel.Evidence> ofStage = config.stageEvidence().get(stage);
        return ofStage == null || evidenceId == null ? null : ofStage.get(evidenceId);
    }

    /**
     * 查一条冻结治理决议；找不到返回 {@code null}。
     *
     * @param mandateId 决议引用
     * @return 决议或 null
     */
    public Mandate mandateOf(String mandateId) {
        return mandateId == null ? null : config.mandates().get(mandateId);
    }

    /**
     * 可重演的开局配置：不预置经理、预算或任何成功标记。
     *
     * @param companyPartyId 公司组织标识
     * @param symbol         证券代码
     * @param model          纯核冻结配置
     * @param stageEvidence  按阶段的冻结观察引用（键为阶段，内层键为引用标识）
     * @param mandates       开局冻结的治理决议（键与 {@code mandateId} 一致）
     */
    public record Config(String companyPartyId, String symbol, DigitalServiceModel.Config model,
                         Map<DigitalServiceModel.Stage, Map<String, DigitalServiceModel.Evidence>> stageEvidence,
                         Map<String, Mandate> mandates,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean evidenceAtCompletion) {
        /** 独立纯核夹具仍使用绝对观察时点。 */
        public Config(String companyPartyId, String symbol, DigitalServiceModel.Config model, Map<DigitalServiceModel.Stage, Map<String, DigitalServiceModel.Evidence>> stageEvidence, Map<String, Mandate> mandates) { this(companyPartyId,symbol,model,stageEvidence,mandates,false); }

        /** 校验身份、深复制冻结引用并核对键一致。 */
        public Config {
            requireText(companyPartyId, "公司主体");
            requireText(symbol, "证券代码");
            Objects.requireNonNull(model, "纯核配置");
            if (!companyPartyId.equals(model.companyOwnerId())) {
                throw new IllegalArgumentException("公司主体与纯核配置不一致：" + companyPartyId);
            }
            Map<DigitalServiceModel.Stage, Map<String, DigitalServiceModel.Evidence>> evidence =
                    new LinkedHashMap<>();
            if (stageEvidence != null) {
                for (Map.Entry<DigitalServiceModel.Stage,
                        Map<String, DigitalServiceModel.Evidence>> entry : stageEvidence.entrySet()) {
                    Objects.requireNonNull(entry.getKey(), "证据阶段");
                    Map<String, DigitalServiceModel.Evidence> ofStage = new LinkedHashMap<>();
                    for (Map.Entry<String, DigitalServiceModel.Evidence> item
                            : Objects.requireNonNull(entry.getValue(), "阶段证据").entrySet()) {
                        if (!item.getKey().equals(item.getValue().evidenceId())) {
                            throw new IllegalArgumentException("证据引用与键不一致：" + item.getKey());
                        }
                        ofStage.put(item.getKey(), item.getValue());
                    }
                    evidence.put(entry.getKey(), Map.copyOf(ofStage));
                }
            }
            stageEvidence = Map.copyOf(evidence);
            Map<String, Mandate> decisions = new LinkedHashMap<>();
            if (mandates != null) {
                for (Map.Entry<String, Mandate> entry : mandates.entrySet()) {
                    if (!entry.getKey().equals(entry.getValue().mandateId())) {
                        throw new IllegalArgumentException("决议引用与键不一致：" + entry.getKey());
                    }
                    decisions.put(entry.getKey(), entry.getValue());
                }
            }
            mandates = Map.copyOf(decisions);
        }

        /**
         * 只要纯核配置、没有阶段引用与治理决议时的最小配置。
         *
         * @param companyPartyId 公司组织标识
         * @param symbol         证券代码
         * @param model          纯核冻结配置
         * @return 最小配置
         */
        public static Config of(String companyPartyId, String symbol, DigitalServiceModel.Config model) {
            return new Config(companyPartyId, symbol, model, Map.of(), Map.of());
        }
    }

    /**
     * 一条开局冻结的治理决议事实。
     *
     * <p>本记录只证明“世界在开局前已有这条决议”，不实现董事会流程，也不由适配层代签。
     * 世界必须先用真实治理动作产生决议，再把它写进冻结配置；届时引用才存在。</p>
     *
     * @param mandateId 决议引用
     * @param kind      决议种类
     * @param decidedAt 决议时点
     */
    public record Mandate(String mandateId, Kind kind, long decidedAt) {

        /** 决议种类；当前只启用注资。 */
        public enum Kind {
            /** 外部资本注入。 */
            CAPITAL_INJECTION
        }

        /** 校验引用、种类与时点。 */
        public Mandate {
            requireText(mandateId, "决议引用");
            Objects.requireNonNull(kind, "决议种类");
            if (decidedAt < 0) {
                throw new IllegalArgumentException("决议时点不能为负");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
