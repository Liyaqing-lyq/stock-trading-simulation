package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 人物已结束的采编事项与岗位回报。只使用当前时点通过岗位、本人身份过滤后的视图。 */
final class MediaRoleHistory {
    private MediaRoleHistory() { }

    record Visible(List<RoleTradingView.MediaStory> media, List<RoleTradingView.Notice> notices) { }

    private static boolean closed(RoleTradingView.MediaStory story) {
        return "PUBLISHED".equals(story.status()) || "WITHDRAWN".equals(story.status());
    }

    private static List<String> closedStories(RoleTradingView view) {
        if (view.media() == null) return List.of();
        return view.media().stream().filter(MediaRoleHistory::closed)
                .sorted(Comparator.comparingLong(RoleTradingView.MediaStory::updatedAtMillis).reversed()
                        .thenComparing(RoleTradingView.MediaStory::storyId))
                .map(RoleTradingView.MediaStory::storyId).toList();
    }

    private static List<String> notices(RoleTradingView view) {
        // 原回报视图按真正提交位置升序；同一毫秒也保留业务发生顺序。
        var ids = new ArrayList<String>(view.notices().size());
        for (var row : view.notices()) ids.add(row.clientTaskId());
        Collections.reverse(ids);
        return ids;
    }

    static ParticipantHistory.Selection select(RoleTradingView view, ParticipantHistory.Request request) {
        var rows = switch (request.section()) {
            case "media" -> request.id() != null && view.media() != null
                    ? view.media().stream().map(RoleTradingView.MediaStory::storyId).toList() : closedStories(view);
            case "notices" -> notices(view);
            default -> throw new IllegalArgumentException("未知采编/回报区域");
        };
        return ParticipantHistory.selectIds(request,rows);
    }

    static Visible project(ObjectNode root, RoleTradingView view) {
        if (!(root.at("/engineering/historyLookup") instanceof ObjectNode info))
            return new Visible(view.media(),view.notices());
        var stories = closedStories(view);
        var receipts = notices(view);
        info.put("closedMediaCount",stories.size()).put("noticeCount",receipts.size())
                .put("mediaAndNotices","media 保留所有在办稿和最近4份已刊发或撤回稿；notices 保留本人最近4份回报。"
                        +"用 role.history.lookup 查更早事项。命中项在原栏目显示，稿件按当前岗位权限可更正或撤回，正文须按原权限阅读。");
        var allMedia = view.media() == null ? List.<String>of() : view.media().stream().map(RoleTradingView.MediaStory::storyId).toList();
        var archived = preview(info,"media",stories,allMedia);
        var recent = preview(info,"notices",receipts,receipts);
        var media = view.media() == null ? null : view.media().stream()
                .filter(story -> !closed(story) || archived.contains(story.storyId())).toList();
        return new Visible(media,view.notices().stream().filter(row -> recent.contains(row.clientTaskId())).toList());
    }

    static Set<String> preview(ObjectNode info, String section, List<String> closed, List<String> authorized) {
        var shown = new HashSet<>(closed.subList(0,Math.min(closed.size(),ParticipantHistory.PAGE_SIZE)));
        if (!(info.get("lastResult") instanceof ObjectNode result) || !section.equals(result.path("section").asText())) return shown;
        var current = new HashSet<>(authorized);
        var original = result.path("ids");
        var retained = result.putArray("ids");
        for (var id : original) if (current.contains(id.asText())) {
            retained.add(id.asText());
            shown.add(id.asText());
        }
        for (var field : List.of("id","before","nextBefore"))
            if (result.has(field) && !current.contains(result.path(field).asText())) result.remove(field);
        return shown;
    }
}
