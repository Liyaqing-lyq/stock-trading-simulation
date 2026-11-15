package edu.cufe.auction.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * schema22 研发世界状态：一个公司（首切片固定 eng-a）的冻结项目表、承包方容量与纯核状态。
 *
 * <p>现金不在此复制，始终由唯一 {@link CashLedger} 账户表承载；本记录只保存项目/阶段/证据
 * 与命令幂等索引。旧档为 null；非 research 模块不创建。</p>
 *
 * @param companyPartyId 公司组织
 * @param projects       冻结的有限项目表（projectId → 阶段计划）
 * @param maxActiveStages 承包方同时最多在途阶段数（真实有限容量）
 * @param state          纯核研发状态（含项目、阶段、证据与命令索引）
 */
public record ResearchRun(String companyPartyId,
                          Map<String, ResearchModel.ProjectSpec> projects,
                          int maxActiveStages,
                          ResearchModel.State state) {

    /** 校验公司、项目表、容量与纯核状态。 */
    public ResearchRun {
        Objects.requireNonNull(companyPartyId, "研发公司");
        if (companyPartyId.isBlank()) {
            throw new IllegalArgumentException("研发公司不能为空");
        }
        Objects.requireNonNull(projects, "冻结项目表");
        projects = Map.copyOf(new LinkedHashMap<>(projects));
        for (Map.Entry<String, ResearchModel.ProjectSpec> entry : projects.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().projectId())) {
                throw new IllegalArgumentException("项目键与标识不一致：" + entry.getKey());
            }
        }
        if (maxActiveStages <= 0) {
            throw new IllegalArgumentException("承包方容量必须为正");
        }
        Objects.requireNonNull(state, "研发纯核状态");
    }

    /**
     * 开局：按唯一账户表建立空的研发状态（初始权益永不重置）。
     *
     * @param companyPartyId 公司
     * @param specs          冻结项目表
     * @param maxActiveStages 承包方容量
     * @param config         纯核配置（公司所有者/账户/既有资产）
     * @param accounts       唯一账户表
     * @return 初始研发状态
     */
    public static ResearchRun open(String companyPartyId, List<ResearchModel.ProjectSpec> specs,
            int maxActiveStages, ResearchModel.Config config,
            Map<String, CashLedger.CashAccount> accounts) {
        Map<String, ResearchModel.ProjectSpec> map = new LinkedHashMap<>();
        for (ResearchModel.ProjectSpec spec : specs) {
            if (map.put(spec.projectId(), spec) != null) {
                throw new IllegalArgumentException("重复项目标识：" + spec.projectId());
            }
        }
        return new ResearchRun(companyPartyId, map, maxActiveStages,
                ResearchModel.open(config, accounts));
    }

    /** @param next 新纯核状态 @return 同配置的新研发状态 */
    public ResearchRun withState(ResearchModel.State next) {
        return new ResearchRun(companyPartyId, projects, maxActiveStages, next);
    }

    /** @return 冻结项目；不存在返回 null */
    public ResearchModel.ProjectSpec project(String projectId) {
        return projects.get(projectId);
    }

    /** @return 当前全部项目的在途阶段数（容量真实占用） */
    public int activeStages() {
        int active = 0;
        for (ResearchModel.Project project : state.projects().values()) {
            for (ResearchModel.StageRecord stage : project.stages()) {
                if (stage.status() == ResearchModel.StageStatus.IN_PROGRESS) {
                    active++;
                }
            }
        }
        return active;
    }

    /** @return 已登记项目（按标识排序） */
    public List<String> registeredProjectIds() {
        return List.copyOf(state.projects().keySet());
    }
}
