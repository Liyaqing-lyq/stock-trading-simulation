package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 组合档中“额外公司”的治理容器：每家公司一份真实 {@link BoardRun} 与一份
 * {@link PeriodicModel.State}，与旧制造业治理单例及 {@link EngineeringRun} 的工程公司
 * 董事会按 companyId 严格不相交。
 *
 * <p><b>不是第二本账</b>：本容器只保存会议、材料、票、任免决议与周期快照的状态，不保存现金、
 * 存款、证券或银行三态；银行资金仍只在 {@code CashLedger} 与 {@code BankRun}。它存在的唯一
 * 理由是让银行这类非制造、非工程公司也能走<b>同一套</b> {@link BoardModel}/
 * {@link PeriodicModel} 规则，而不是为任免另造接口或把银行伪装成工程公司。</p>
 *
 * <p><b>旧档案</b>：该组件在旧 profile 为 null，序列化用 {@code NON_NULL} 不输出，旧构造与
 * 旧 JSON 逐字不变。组合档每家公司内部仍按标识排序，保证规范序列化确定。</p>
 */
public record CompanyGovernanceRun(
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, BoardRun> boards,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        PeriodicModel.State periods) {

    /** 校验公司集合一致、章程绑定与排序。 */
    public CompanyGovernanceRun {
        Map<String, BoardRun> copy = new TreeMap<>();
        if (boards != null) {
            boards.forEach((company, board) -> {
                if (company == null || company.isBlank() || board == null) {
                    throw new IllegalArgumentException("额外治理公司或董事会为空：" + company);
                }
                if (!company.equals(board.board().charter().companyPartyId())) {
                    throw new IllegalArgumentException("额外治理公司与章程不一致："
                            + company + " / " + board.board().charter().companyPartyId());
                }
                copy.put(company, board);
            });
        }
        boards = Collections.unmodifiableMap(copy);
        if (periods == null) {
            if (!boards.isEmpty()) {
                throw new IllegalArgumentException("额外治理公司必须有周期状态");
            }
        } else {
            List<String> keys = periods.companies().stream()
                    .map(PeriodicModel.CompanyPeriods::companyPartyId).toList();
            if (!keys.equals(List.copyOf(boards.keySet()))) {
                throw new IllegalArgumentException("额外治理公司与周期状态公司集合不一致："
                        + boards.keySet() + " / " + keys);
            }
        }
    }

    /**
     * 开局：按章程建立真实董事会与空周期状态；不预置任何经理任职。
     *
     * @param registry 开局登记
     * @param charters 额外公司章程（companyId → 章程）
     * @param policy   周期政策（与制造/工程同一份冻结政策）
     * @return 容器
     */
    public static CompanyGovernanceRun open(OpeningRegistry registry,
                                            Map<String, BoardModel.Charter> charters,
                                            PeriodicModel.Policy policy) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(charters, "charters");
        Objects.requireNonNull(policy, "policy");
        if (charters.isEmpty()) {
            return new CompanyGovernanceRun(Map.of(), null);
        }
        Map<String, BoardRun> boards = new TreeMap<>();
        for (Map.Entry<String, BoardModel.Charter> entry : charters.entrySet()) {
            String company = entry.getKey();
            BoardModel.Charter charter = entry.getValue();
            if (!company.equals(charter.companyPartyId())) {
                throw new IllegalArgumentException("额外公司章程公司与键不一致：" + company);
            }
            // 与工程/制造同一纯核入口：公司须为 ORGANIZATION、五席不同 PERSON、主席在席
            BoardModel.State board = BoardModel.open(registry, charter);
            boards.put(company, new BoardRun(board, Map.of(), Map.of()));
        }
        PeriodicModel.State periods = PeriodicModel.open(
                List.copyOf(boards.keySet()), policy);
        return new CompanyGovernanceRun(boards, periods);
    }

    /** @return 是否有该公司 */
    @JsonIgnore
    public boolean has(String companyPartyId) {
        return boards.containsKey(companyPartyId);
    }

    /** @return 公司集合（按标识排序） */
    @JsonIgnore
    public List<String> companies() {
        return List.copyOf(boards.keySet());
    }

    /** @return 该公司的真实董事会；不存在返回 null */
    @JsonIgnore
    public BoardRun boardOf(String companyPartyId) {
        return companyPartyId == null ? null : boards.get(companyPartyId);
    }

    /** @return 该公司的周期状态；不存在返回 null */
    @JsonIgnore
    public PeriodicModel.CompanyPeriods periodsOf(String companyPartyId) {
        return periods == null || companyPartyId == null ? null : periods.company(companyPartyId);
    }

    /**
     * 替换一家公司的董事会（保持其它公司不变）。
     *
     * @param companyPartyId 公司
     * @param board          新董事会状态
     * @return 新容器
     */
    public CompanyGovernanceRun withBoard(String companyPartyId, BoardRun board) {
        if (!boards.containsKey(companyPartyId)) {
            throw new IllegalArgumentException("额外治理公司不存在：" + companyPartyId);
        }
        Map<String, BoardRun> next = new TreeMap<>(boards);
        next.put(companyPartyId, Objects.requireNonNull(board, "board"));
        return new CompanyGovernanceRun(next, periods);
    }

    /**
     * 替换周期状态（公司集合不变）。
     *
     * @param next 新周期状态
     * @return 新容器
     */
    public CompanyGovernanceRun withPeriods(PeriodicModel.State next) {
        return new CompanyGovernanceRun(boards, Objects.requireNonNull(next, "next"));
    }
}
