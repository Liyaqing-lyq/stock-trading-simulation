package edu.cufe.auction.web;

import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchRejectedException;
import edu.cufe.auction.world.PerformanceModel;
import edu.cufe.auction.world.WorldRuntime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实运行父界点的分支探针（不是单元测试）：打开一份真实的 groups20 存档副本，逐位置报告
 * "能不能切"的原因码，再在最后一个可用位置创建分支并对照父子报表。
 *
 * <pre>
 * java ... RealParentBranchProbe &lt;journalCopy&gt; &lt;childRunId&gt;
 * </pre>
 */
public final class RealParentBranchProbe {

    private RealParentBranchProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <journalCopy> <childRunId>}
     * @throws Exception 打开失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：RealParentBranchProbe <journalCopy> <childRunId>");
        }
        Path parentFile = Path.of(args[0]).toAbsolutePath();
        String childId = args[1];
        List<Integer> supported = new ArrayList<>();
        List<WorldRuntime.CapitalFlowReport> parentFlows;
        List<WorldRuntime.DividendReport> parentDividends;
        List<PerformanceModel.Segment> parentSegments;
        long cut;
        String parentRun;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = WorldWebScenario.groupsRuntime(parentFile)) {
            parentRun = parent.branchOrigin().isPresent() ? "-" : "groups20";
            long last = parent.snapshot().position();
            for (long position = 1; position <= last; position++) {
                try {
                    parent.createBranch("probe-" + position, position,
                            BranchOrigin.Kind.OPERATOR_BRANCH, "可用性探测");
                    supported.add((int) position);
                } catch (BranchRejectedException rejected) {
                    System.out.println("POSITION " + position + " rejected=" + rejected.code());
                }
            }
            System.out.println("SUPPORTED positions=" + supported);
            if (supported.isEmpty()) {
                System.out.println("RESULT no-splittable-position parent=" + parentRun);
                return;
            }
            cut = supported.get(supported.size() - 1);
            for (String candidate : List.of("customer", "investor", "company", "supplier",
                    "ops", "alice", "bob")) {
                try {
                    System.out.println("OWNER " + candidate + " flows="
                            + parent.capitalFlows(candidate).size());
                } catch (RuntimeException unknownOwner) {
                    System.out.println("OWNER " + candidate + " unknown="
                            + unknownOwner.getClass().getSimpleName());
                }
            }
            String owner = "customer";
            parentFlows = parent.capitalFlows(owner);
            parentDividends = parent.dividendReports();
            parentSegments = parent.performance(owner, 1, cut).segments();
            System.out.println("PARENT cut=" + cut + " flows=" + parentFlows.size()
                    + " dividends=" + parentDividends.size()
                    + " segments=" + parentSegments.size());
            receipt = parent.createBranch(childId, cut, BranchOrigin.Kind.OPERATOR_BRANCH,
                    "真实父界点派生");
            System.out.println("CREATED child=" + receipt.childRunId() + " first="
                    + receipt.firstPosition() + " prefixSha=" + receipt.parentPrefixSha256());
        }
        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? parentFile : null,
                parentProfile(parentFile))) {
            System.out.println("CHILD position=" + child.snapshot().position()
                    + " runId=" + child.branchOrigin().orElseThrow().childRunId()
                    + " flows=" + child.capitalFlows("investor").size()
                    + " dividends=" + child.dividendReports().size()
                    + " segments=" + child.performance("investor", 1, cut).segments().size());
            System.out.println("MATCH flows=" + parentFlows.equals(child.capitalFlows("investor"))
                    + " dividends=" + parentDividends.equals(child.dividendReports())
                    + " segments=" + parentSegments.equals(
                            child.performance("investor", 1, cut).segments()));
        }
    }

    private static edu.cufe.auction.world.BranchProfile parentProfile(Path parentFile)
            throws Exception {
        try (WorldRuntime probe = WorldWebScenario.groupsRuntime(parentFile)) {
            return probe.profile();
        }
    }
}
