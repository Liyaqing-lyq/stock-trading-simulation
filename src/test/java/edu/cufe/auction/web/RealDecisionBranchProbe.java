package edu.cufe.auction.web;

import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchRejectedException;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.WorldRuntime;

import java.nio.file.Path;

/**
 * 真实"已结算决定"父运行的分支探针（不是单元测试）：打开一份真实 groups20 存档副本，
 * 在该边界上创建分支，检查子档对继承决定的处理。
 *
 * <pre>
 * java ... RealDecisionBranchProbe &lt;journalCopy&gt; &lt;childRunId&gt;
 * </pre>
 */
public final class RealDecisionBranchProbe {

    private RealDecisionBranchProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <journalCopy> <childRunId>}
     * @throws Exception 打开失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "用法：RealDecisionBranchProbe <journalCopy> <childRunId>");
        }
        Path parentFile = Path.of(args[0]).toAbsolutePath();
        String childId = args[1];
        long cut;
        String firstAction = null;
        String firstAttempt = null;
        boolean sessionOpen;
        int decisions;
        int unsettled;
        WorldRuntime.BranchReceipt receipt;
        try (WorldRuntime parent = WorldWebScenario.groupsRuntime(parentFile)) {
            WorldRuntime.Snapshot snapshot = parent.snapshot();
            cut = snapshot.position();
            DecisionModel.State state = snapshot.decisions().orElseThrow()
                    .state();
            decisions = state.decisions().size();
            sessionOpen = state.sessionOpen();
            unsettled = 0;
            for (DecisionModel.Decision decision : state.decisions()) {
                for (DecisionModel.Attempt attempt : decision.attempts()) {
                    if (!attempt.settledDone()) {
                        unsettled++;
                    }
                    if (firstAction == null) {
                        firstAction = decision.actionId();
                        firstAttempt = attempt.attemptId();
                    }
                }
            }
            System.out.println("PARENT cut=" + cut + " decisions=" + decisions
                    + " sessionOpen=" + sessionOpen + " attempts=" + unsettled
                    + " firstAction=" + firstAction + " firstAttempt=" + firstAttempt);
            receipt = parent.createBranch(childId, cut, BranchOrigin.Kind.OPERATOR_BRANCH,
                    "已结算决定继承");
            System.out.println("CREATED child=" + receipt.childRunId() + " first="
                    + receipt.firstPosition() + " sealed=" + receipt.sealedPrefix());
        }
        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? receipt.sealedPrefix() : null,
                parentProfile(parentFile))) {
            DecisionModel.State state = child.snapshot().decisions().orElseThrow().state();
            StringBuilder inherited = new StringBuilder();
            for (DecisionModel.Decision decision : state.decisions()) {
                inherited.append(decision.actionId()).append('/').append(decision.status())
                        .append('/').append(decision.attempts().size()).append(' ');
            }
            System.out.println("CHILD position=" + child.snapshot().position()
                    + " inheritedDecisions=" + state.decisions().size()
                    + " detail=" + inherited.toString().trim()
                    + " sessionOpen=" + state.sessionOpen());
            if (firstAction != null) {
                try {
                    child.openDecisions("child-reopen", java.util.List.of(
                            new edu.cufe.auction.world.DecisionRun.Request("bob",
                                    firstAction, "SCHEDULED")));
                    System.out.println("REOPEN sameAction=ACCEPTED(异常:不该重问)");
                } catch (RuntimeException | java.io.IOException rejected) {
                    System.out.println("REOPEN sameAction=REJECTED " + rejected.getMessage());
                }
                child.beginDecisionSession("child-open");
                String reply;
                try {
                    reply = child.acceptDecisionReply("child-stale",
                            new DecisionModel.Reply(receipt.parentRunId(), firstAction,
                                    firstAttempt, 1, 1, "{}", null, ""));
                } catch (RuntimeException | java.io.IOException failed) {
                    reply = "THREW:" + failed.getMessage();
                }
                System.out.println("STALE_REPLY=" + reply);
                try {
                    child.openDecisions("child-new", java.util.List.of(
                            new edu.cufe.auction.world.DecisionRun.Request("bob",
                                    "child-new-action", "SCHEDULED")));
                    System.out.println("NEW_ACTION=DECIDING");
                } catch (RuntimeException | java.io.IOException rejected) {
                    System.out.println("NEW_ACTION=REJECTED " + rejected.getMessage());
                }
            }
        }
    }

    private static edu.cufe.auction.world.BranchProfile parentProfile(Path parentFile)
            throws Exception {
        try (WorldRuntime probe = WorldWebScenario.groupsRuntime(parentFile)) {
            return probe.profile();
        }
    }
}
