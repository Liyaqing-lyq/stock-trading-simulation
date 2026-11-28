package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 有办理工时的本人历史查阅。页中只引用本人观察时有权查看的原件。 */
final class ParticipantHistory {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    static final String TASK = "ROLE_PERSONAL_HISTORY_V1";
    static final int PAGE_SIZE = 4;
    private static final String RECEIPT_CURSOR = "/prior-receipts/";

    record Request(String section, String id, String before) {
        Request {
            if (section == null || !List.of("interview","media","notices","messages","fund-children","publications").contains(section)
                    || id != null && (id.isBlank() || before != null) || before != null && before.isBlank())
                throw new IllegalArgumentException("区域须为 interview、media、notices、messages、fund-children 或 publications，id 与 before 只能指定一个");
        }
    }

    record Selection(List<String> ids, String nextBefore, List<String> receiptTasks) {
        Selection { ids = List.copyOf(ids); receiptTasks = List.copyOf(receiptTasks); }
    }

    record Page(String section, Request request, long observedAt, Selection selected) { }

    private ParticipantHistory() { }

    static boolean enabled(RunManifest manifest) {
        return RunManifest.PARTICIPANT_HISTORY_VERSION.equals(manifest.dataVersions().get(RunManifest.PARTICIPANT_HISTORY_POLICY));
    }

    static Selection selectIds(Request request, List<String> sorted) {
        if (request.id() != null)
            return new Selection(sorted.contains(request.id()) ? List.of(request.id()) : List.of(),null,List.of());
        int start = 0;
        if (request.before() != null) {
            int previous = sorted.indexOf(request.before());
            if (previous < 0) return new Selection(List.of(),null,List.of());
            start = previous + 1;
        }
        int end = Math.min(start + PAGE_SIZE, sorted.size());
        return new Selection(sorted.subList(start,end),end < sorted.size() ? sorted.get(end-1) : null,List.of());
    }

    private static List<InterviewModel.Invite> own(InterviewRun run, String person) {
        return run.states().stream().flatMap(desk -> desk.invites().stream())
                .filter(row -> person.equals(row.fromPartyId()) || person.equals(row.toPartyId()))
                .sorted(Comparator.comparingLong(InterviewModel.Invite::createdAtMillis).reversed()
                        .thenComparing(InterviewModel.Invite::requestId)).toList();
    }

    private static boolean ongoing(InterviewModel.Invite invite) {
        return switch (invite.status()) {
            case PROPOSED, ACCEPTED, ACTIVE -> true;
            default -> false;
        };
    }

    record InterviewIndex(List<InterviewModel.Invite> rows, List<InterviewRun.Coordination> receipts) { }

    static InterviewIndex index(InterviewRun run, String person) {
        return new InterviewIndex(own(run,person),run.coordinations().values().stream()
                .filter(row -> person.equals(row.personPartyId()) && InterviewRun.COMMAND_KINDS.contains(row.kind())).toList());
    }

    static Selection interview(InterviewIndex index, Request request) {
        var rows = index.rows();
        if (request.id() == null && (request.before() == null || !request.before().startsWith(RECEIPT_CURSOR))) {
            var dates = new java.util.HashMap<String,Long>();
            for (var receipt : index.receipts()) if (!receipt.requestId().isBlank())
                dates.merge(receipt.requestId(),receipt.timeMillis(),Math::min);
            for (var invite : rows) {
                if (ongoing(invite)) dates.remove(invite.requestId());
                else dates.put(invite.requestId(),invite.createdAtMillis());
            }
            var ids = dates.keySet().stream().sorted(Comparator.<String>comparingLong(dates::get).reversed()
                    .thenComparing(Comparator.naturalOrder())).toList();
            return selectIds(request,ids);
        }
        String id = request.id();
        int before = Integer.MAX_VALUE;
        if (id == null) {
            var cursor = request.before().substring(RECEIPT_CURSOR.length());
            int split = cursor.indexOf('/');
            if (split <= 0) return new Selection(List.of(),null,List.of());
            try { before = Integer.parseInt(cursor.substring(0,split)); }
            catch (NumberFormatException invalid) { return new Selection(List.of(),null,List.of()); }
            id = cursor.substring(split+1);
        }
        final String target = id;
        var receipts = index.receipts().stream().filter(row -> target.equals(row.requestId()))
                .sorted(Comparator.comparingLong(InterviewRun.Coordination::position)).toList();
        if (receipts.isEmpty() && rows.stream().noneMatch(row -> target.equals(row.requestId())))
            return new Selection(List.of(),null,List.of());
        if (before <= 0 || before != Integer.MAX_VALUE && before > receipts.size()) return new Selection(List.of(),null,List.of());
        int end = Math.min(receipts.size(),before == Integer.MAX_VALUE ? before : before-1);
        int start = Math.max(0,end-PAGE_SIZE);
        var tasks = new ArrayList<String>();
        for (int i=end-1;i>=start;i--) tasks.add(receipts.get(i).taskId());
        return new Selection(List.of(target),start>0 ? RECEIPT_CURSOR+(start+1)+"/"+target : null,tasks);
    }

    static Set<String> preview(InterviewRun run, String person, java.util.function.Supplier<RoleView> currentProfile, OnsiteVisit.View visits, Page last) {
        var rows = own(run,person);
        Set<String> selected = new HashSet<>();
        rows.stream().filter(ParticipantHistory::ongoing).forEach(row -> selected.add(row.requestId()));
        rows.stream().filter(row -> !ongoing(row)).limit(PAGE_SIZE).forEach(row -> selected.add(row.requestId()));
        if (last != null && "interview".equals(last.section())) selected.addAll(last.selected().ids());
        for (var trip : visits.appointments())
            if (!trip.actions().isEmpty() || List.of("BOOKED","OUTBOUND","ONSITE","RETURNING").contains(trip.stage()))
                selected.add(trip.requestId());
        // 仍在阅读或能引用的原话保留原有具名联系入口；查看会话本身不会登记阅读。
        if (run.states().stream().allMatch(desk -> desk.sessions().isEmpty())) return selected;
        var profile = currentProfile.get();
        var docs = new HashSet<String>();
        profile.readings().forEach(read -> docs.add(read.ref().docId()));
        profile.observations().forEach(read -> docs.add(read.ref().docId()));
        if (!docs.isEmpty()) for (var desk : run.states()) {
            var dialogue = run.dialoguesOf(desk.policy().mediaOrganizationPartyId());
            for (var session : desk.sessions())
                if (dialogue.turnsOf(session.sessionId()).stream().anyMatch(turn -> docs.contains(turn.docId())))
                    selected.add(session.requestId());
        }
        return selected;
    }

    static void show(ObjectNode engineering, Page page, RoleTradingRun.Report completed, InterviewRun run, String person) {
        var meta = engineering.putObject("historyLookup");
        meta.put("contract","role.history.lookup 花费1000毫秒。section 可选 interview（本人采访预约）、media（在岗可见稿件）、notices（本人岗位回报）、messages（以 messageId 查往来私信信封，正文须另读）、fund-children（本人按原 childId 查基金子单）、publications（本人发帖、改版与订阅送达的目录）。"
                +"省略 id 与 before 按最近顺序查4项；id 精确查一项，before 传该区域上页 nextBefore。"
                +"publications 每栏保留办理中与最近4项，按原文号查最近有权版本，或用文号@版次；详项在原栏，阅读另计。"
                +"采访默认保留仍在办理和最近4个已结束预约。查回的案号在 interview 原栏目查看，原话仍须按文号实际阅读。精确查预约还可翻阅本人执行回执。");
        if (page == null) return;
        var last = meta.putObject("lastResult").put("section",page.section()).put("observedAt",page.observedAt())
                .put("completedAt",completed.timeMillis());
        if (page.request().id()!=null) last.put("id",page.request().id());
        if (page.request().before()!=null) last.put("before",page.request().before());
        var ids = last.putArray("ids");
        page.selected().ids().forEach(ids::add);
        if (page.selected().nextBefore()!=null) last.put("nextBefore",page.selected().nextBefore());
        if (!"interview".equals(page.section())) return;
        var receipts = last.putArray("receipts");
        for (String task : page.selected().receiptTasks()) {
            var row = run.coordinations().get(task);
            if (row == null || !person.equals(row.personPartyId()) || !page.selected().ids().contains(row.requestId())) continue;
            var item = receipts.addObject().put("requestId",row.requestId()).put("kind",row.kind())
                    .put("accepted",row.accepted()).put("reason",row.reason()).put("timeMillis",row.timeMillis());
            if (row.windowTiming()!=null) item.set("windowTiming",JSON.valueToTree(row.windowTiming()));
        }
    }
}
