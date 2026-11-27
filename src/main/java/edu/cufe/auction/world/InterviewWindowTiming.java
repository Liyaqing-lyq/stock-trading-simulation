package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 本人预约时钟的投影与已确认窗口拒绝的解释；业务校验仍由 InterviewModel 执行。 */
final class InterviewWindowTiming {
    private InterviewWindowTiming() { }

    static void describe(ObjectNode window, long executedAt, long lead) {
        window.put("executionMillis", executedAt).put("minLeadMillis", lead);
        try {
            window.put("earliestWindowStartMillis", Math.addExact(executedAt, lead));
        } catch (ArithmeticException beyondClock) {
            window.put("timingStatus", "TIME_OVERFLOW");
        }
    }

    static void completePlanning(ObjectNode input, ObjectNode actions) {
        if (!(input.at("/interview/inviteWindow") instanceof ObjectNode window)) return;
        var finish = actions.path(RoleTradingView.TOOL_INTERVIEW_INVITE).path("earliestCompletionMillis");
        if (finish.isIntegralNumber()) describe(window, finish.longValue(), window.required("minLeadMillis").longValue());
    }

    static InterviewRun.WindowFailure rejection(long executedAt, long proposed, long earliest, long latest,
                                                boolean invitation) {
        boolean early = proposed < earliest;
        long delta = early ? earliest - proposed : proposed - latest;
        String detail = early
                ? (invitation ? "早于邀请执行时刻加本社最小提前量" : "早于回应执行时刻或预约窗口起点")
                : "整段采访超出预约窗口终点";
        return new InterviewRun.WindowFailure(executedAt, proposed, earliest, latest, delta, detail + "，相差" + delta + "毫秒。");
    }
}
