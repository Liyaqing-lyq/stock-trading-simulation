package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 基金执行员自己的子单。原始子单按受理顺序追加，编号在同一执行员名下唯一。 */
final class FundChildHistory {
    private FundChildHistory() { }

    private static List<String> closed(RoleTradingView view) {
        var ids = new ArrayList<String>();
        for (var child : view.children())
            if (child.leaves() == 0) ids.add(child.ref().childId());
        Collections.reverse(ids);
        return ids;
    }

    static ParticipantHistory.Selection select(RoleTradingView view, ParticipantHistory.Request request) {
        var ids = request.id() == null ? closed(view) : view.children().stream().map(child -> child.ref().childId()).toList();
        return ParticipantHistory.selectIds(request, ids);
    }

    static List<RoleTradingView.ChildView> visible(ObjectNode root, RoleTradingView view) {
        if (!(root.at("/engineering/historyLookup") instanceof ObjectNode history)) return view.children();
        var closed = closed(view);
        var all = view.children().stream().map(child -> child.ref().childId()).toList();
        var selected = MediaRoleHistory.preview(history, "fund-children", closed, all);
        if (!view.children().isEmpty()) {
            history.put("closedFundChildCount", closed.size()).put("fundChildren",
                    "children 保留本人全部仍有余量的基金子单，以及按受理顺序最近4笔已结子单。"
                    + "查更早的已结子单可用 role.history.lookup，section 填 fund-children，id 可填原 childId。");
        }
        return view.children().stream().filter(child -> child.leaves() > 0 || selected.contains(child.ref().childId())).toList();
    }
}
