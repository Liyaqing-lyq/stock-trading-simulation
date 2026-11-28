package edu.cufe.auction.world;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** 本人可发起普通调查与交流的实验时段，日历以第零日为周一。 */
public enum PersonalDutyWindows {
    /** 有生效机构任职者共 300 分钟。 */
    EMPLOYED(540, 720, 780, 900),
    /** 其余在席董事或有本人持股者共 150 分钟。 */
    ACTIVE(570, 660, 840, 900),
    /** 其余人物共 40 分钟。 */
    PART_TIME(570, 590, 880, 900);

    private final int start1, end1, start2, end2;

    PersonalDutyWindows(int start1, int end1, int start2, int end2) {
        this.start1 = start1;
        this.end1 = end1;
        this.start2 = start2;
        this.end2 = end2;
    }

    boolean routineOpen(long now) {
        if (now / MarketSessions.DAY % 7 >= 5) return false;
        long minute = now % MarketSessions.DAY / MarketSessions.MINUTE;
        return start1 <= minute && minute < end1 || start2 <= minute && minute < end2;
    }

    /** 一项有时限的本人职责。目标只列固定对象；其余参数仍由相应业务合同校验。 */
    public record DutyAction(String tool, Map<String,Object> target) {
        /** 固定字段顺序，使相同职责在独立重开后生成相同输入。 */
        public DutyAction { target = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(target)); }

        boolean permits(String name, JsonNode args) {
            if (!tool.equals(name)) return false;
            for (var entry : target.entrySet()) {
                var actual = args.path(entry.getKey());
                var expected = entry.getValue();
                if (expected instanceof Number number) {
                    if (!actual.isIntegralNumber() || !actual.canConvertToLong() || actual.longValue() != number.longValue()) return false;
                } else if (!expected.equals(actual.textValue())) return false;
            }
            return true;
        }
    }

    /** 当日时段使用本地零时起算的分钟，右端点表示关闭。 */
    public record View(String tier, List<Integer> morningMinutes, List<Integer> afternoonMinutes, int minutesPerWeekday,
                       boolean routineOpen, String duty, List<DutyAction> dutyActions, String terms) {
        boolean restricted() { return !routineOpen && !dutyActions.isEmpty(); }
    }

    View view(long now, String duty, List<DutyAction> actions) {
        return new View(name(), List.of(start1, end1), List.of(start2, end2), end1-start1+end2-start2,
                routineOpen(now), duty, List.copyOf(new java.util.LinkedHashSet<>(actions)), "第零日周一。每个工作日按本地零时起算的分钟列出两个时段（左闭右开）。"
                + "同一人兼任职务只用一份日程。时段允许发起普通自主活动，也可观望。已排程工作照常完成；休息中来信照常投递。");
    }

    /** 自主推进不会跨过普通人物活动时段的开关边界，周末到下一周一再开窗。 */
    public static long nextBoundary(long now, long limit) {
        long sinceMidnight = now % MarketSessions.DAY;
        long weekday = now / MarketSessions.DAY % 7;
        long distance = (weekday >= 4 ? 7 - weekday : 1) * MarketSessions.DAY - sinceMidnight
                + EMPLOYED.start1 * MarketSessions.MINUTE;
        if (weekday < 5) {
            for (var tier : values()) {
                for (int minute : new int[]{tier.start1, tier.end1, tier.start2, tier.end2}) {
                    long span = minute * MarketSessions.MINUTE - sinceMidnight;
                    if (span > 0) distance = Math.min(distance, span);
                }
            }
        }
        return distance < limit - now ? now + distance : limit;
    }
}
