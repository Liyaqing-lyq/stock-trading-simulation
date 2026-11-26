package edu.cufe.auction.world;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** 管理员显式升级运行中的制造模板。已签采购合同的价格、数量和到货日留在原采购单。 */
public final class ManufacturingUpgrade {
    /** 运行清单中的升级协议。 */
    public static final String KEY = "manufacturing-template-upgrade", VERSION = "supplier-lead-v1";
    /** 已登记的原始模板与升级目标。 */
    public static final String ORIGINAL = "manufacturing-v2", EXTENDED = "manufacturing-v3";
    private ManufacturingUpgrade() {}

    /** 草稿试算、确认待办、在安全点同时切换。 */
    public enum Stage { PREVIEW, PREPARE, APPLY }

    /** 确认批次号、版本及固定公司集合。 */
    public record Draft(String batchId, String fromVersion, String toVersion, List<String> companyIds) {
        /** 拒绝重复公司、未知版本和不稳定的批次号。 */
        public Draft {
            if (batchId == null || !batchId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
                throw new IllegalArgumentException("批次号需为1到80个字母、数字、点、横线或下划线");
            if (!ORIGINAL.equals(fromVersion) || !EXTENDED.equals(toVersion))
                throw new IllegalArgumentException("制造模板升级版本未登记");
            if (companyIds == null || companyIds.isEmpty()) throw new IllegalArgumentException("请选择实际制造公司");
            var checked = new TreeSet<String>();
            for (var company : companyIds)
                if (company == null || company.isBlank() || !checked.add(company))
                    throw new IllegalArgumentException("公司列表不能有空项或重复项");
            companyIds = List.copyOf(checked);
        }
    }

    /** 同一确认位置的一次管理请求。 */
    public record Request(long expectedPosition, Stage stage, Draft draft) {
        /** 只接受正式操作及完整草稿。 */
        public Request {
            if (expectedPosition < 1 || stage == null || draft == null) throw new IllegalArgumentException("制造模板升级请求不完整");
        }
    }

    /** 已登记的批次，appliedPosition 为零表示尚未切换。 */
    public record Batch(Draft draft, long preparedPosition, long appliedPosition) {
        /** 生效必须晚于草稿登记。 */
        public Batch {
            Objects.requireNonNull(draft);
            if (preparedPosition < 1 || (appliedPosition != 0 && appliedPosition <= preparedPosition))
                throw new IllegalArgumentException("模板升级位置不合法");
        }
    }

    /** 管理员收到的确认位置、进度及导致等待的目标公司。 */
    public record View(long position, String phase, List<String> blockers, Draft draft) {}

    /** 查询指定公司在当前确认位置生效的采购模板版本。 */
    public static String templateVersion(CompanyOperationsRun run, String company) {
        for (var b : run.upgradeBatches().values()) {
            if (b.appliedPosition() > 0 && b.draft().companyIds().contains(company)) return b.draft().toVersion();
        }
        return ORIGINAL;
    }

    /** 新签供应合同从下单到到货的实验时长。 */
    public static long leadMillis(CompanyOperationsRun run, String company) {
        return (EXTENDED.equals(templateVersion(run, company)) ? 2L : 1L) * ManufacturingTemplateModel.DAY_MILLIS;
    }

    /** 检查每个目标仍可从所见版本迁移。 */
    static void checkTargets(CompanyOperationsRun run, Draft draft) {
        for (String id : draft.companyIds()) {
            if (run.company(id) == null || !draft.fromVersion().equals(templateVersion(run, id)))
                throw new IllegalArgumentException("目标公司不存在或其采购模板版本已改变：" + id);
        }
    }
}
