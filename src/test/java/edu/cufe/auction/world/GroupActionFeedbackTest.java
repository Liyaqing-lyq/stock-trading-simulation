package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class GroupActionFeedbackTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String POST = "{\"tool\":\"platform.post\",\"args\":{\"title\":\"本人平台帖\",\"body\":\"公开正文\",\"cites\":[]}}";
    static WorldRuntime open(Path file) throws Exception { return edu.cufe.auction.world.composed.ComposedIndustryScenario.openAccountableAutonomous(file); }
    static DecisionRun.ActionRef decide(WorldRuntime world, String person, String action, String body) throws Exception {
        world.beginDecisionSession("session-"+action);
        world.openDecisions("open-"+action, List.of(new DecisionRun.Request(person, action,"wake",new DecisionModel.Route("governance","key-1","deepseek-flash",1L,20,1_000_000,1_024,1))));
        var call = world.dispatchLiveDecision("dispatch-"+action,action).call();
        assertNotNull(call);
        var reply = (ObjectNode) JSON.readTree(body);
        var objectives = JSON.readTree(call.inputText()).path("engineering").path("deliberation").path("objectives");
        reply.putObject("decision").put("objective",objectives.get(0).path("id").asText()).put("basis","本人动作反馈机制验证").put("nextReviewAt",world.snapshot().timeMillis()+60_000);
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,100,30),"test:scripted"))));
        world.drainDecisionCallbacks();
        var ref = world.snapshot().decisions().orElseThrow().actionRef(action);
        if (!reply.path("tool").asText().equals("role.wait")) assertNotNull(ref); return ref;
    }
    static JsonNode result(WorldRuntime world, String person) throws Exception { return world.autonomyRoleObservation(person).path("engineering").path("deliberation").path("lastActionResult"); }
    static PendingTask next(WorldRuntime world,String kind) { return world.snapshot().pending().stream().filter(t->t.kind().equals(kind)).findFirst().orElseThrow(); }
    static void check(WorldRuntime world,String person,DecisionRun.ActionRef ref,String execution,String taskId) throws Exception {
        var report = world.groupsRun().reports().stream().filter(r->r.taskId().equals(taskId)).findFirst().orElseThrow();
        var result = result(world,person);
        assertEquals(execution,result.path("execution").asText());
        assertEquals(ref.taskId(),result.path("boundTaskId").asText());
        assertEquals(DecisionRun.actionCommandId(ref.actionId()),result.path("commandId").asText());
        assertEquals(report.taskId(),result.path("resultTaskId").asText());
        assertEquals(report.kind(),result.path("resultKind").asText());
        assertEquals(report.atMillis(),result.path("resultAt").asLong());
        assertEquals(report.position(),result.path("resultPosition").asLong());
        assertEquals(report.reason(),result.path("reason").asText());
        if (execution.equals("IN_PROGRESS")) assertFalse(result.has("accepted"));
        else assertEquals(execution.equals("APPLIED"),result.path("accepted").asBoolean());
    }
    static WorldRuntime groupFixture(Path file) throws Exception {
        var original=WorldWebScenario.groupsManifest(); var data=new TreeMap<>(original.dataVersions()); data.put("role-context","deliberation-v3");
        var manifest=new RunManifest(original.runId(),original.seed(),original.rulesVersion(),original.moduleVersions(),data,original.modelConfigVersion());
        var factory=WorldWebScenario.class.getDeclaredMethod("groupsRuntime",Path.class,GroupModel.Config.class,PlatformPostModel.Config.class,int.class,long.class,RunManifest.class);
        factory.setAccessible(true);
        return (WorldRuntime) factory.invoke(null,file,WorldWebScenario.groupsConfig(),WorldWebScenario.groupsPlatformConfig(),WorldWebScenario.GROUPS_FEED_LIMIT,WorldWebScenario.GROUPS_READ_MILLIS,manifest);
    }
    static CommitSource source(WorldRuntime world) throws Exception { var f=WorldRuntime.class.getDeclaredField("commitSource");f.setAccessible(true);return (CommitSource) f.get(world); }
    static GroupActionFeedback.TaskIdentity parser(WorldRuntime world) throws Exception {
        var m=WorldRuntime.class.getDeclaredMethod("groupFeedbackIdentity",PendingTask.class,String.class,String.class);m.setAccessible(true);
        return (task,action,person)-> { try { return (GroupActionFeedback.Identity)m.invoke(world,task,action,person); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } };
    }
    static ObjectNode baseline(DecisionRun decisions,String person,String action,List<PendingTask> pending) {
        var root=JSON.createObjectNode();var d=root.putObject("deliberation");d.putArray("recentDecisions").addObject().put("actionId",action);d.putObject("lastActionResult").put("actionId",action);
        RoleActionFeedback.append(root,person,decisions,pending,null,null,null,null);return root;
    }
    static GroupsRun reports(GroupsRun run,List<GroupsRun.GroupReport> reports) { return new GroupsRun(run.config(),run.platformConfig(),run.feedLimit(),run.readMillis(),run.state(),run.publication(),reports); }
    static WorldCommit events(WorldCommit c,List<WorldEvent> events) { return new WorldCommit(c.schemaVersion(),c.position(),c.runId(),c.commandId(),c.commandContent(),c.result(),events); }
    static String execution(ObjectNode root) {return root.path("deliberation").path("lastActionResult").path("execution").asText();}
    @Test void realPlatformPhasesAndFrozenSubsequentInputStrictlyReplay() throws Exception {
        var file=dir.resolve("platform.journal"); String frozen;
        try(var world=open(file)) {
            var ref=decide(world,"mfgtech-d1","post",POST);
            assertEquals("PENDING",result(world,"mfgtech-d1").path("execution").asText());
            world.advanceTo(ref.dueMillis()); check(world,"mfgtech-d1",ref,"IN_PROGRESS",ref.taskId());
            var finish=next(world,GroupsRun.KIND_FINISH); world.advanceTo(finish.dueTimeMillis()); check(world,"mfgtech-d1",ref,"IN_PROGRESS",finish.taskId());
            var publish=next(world,GroupsRun.KIND_PUBLISH); world.advanceTo(publish.dueTimeMillis()); check(world,"mfgtech-d1",ref,"APPLIED",publish.taskId());
            decide(world,"mfgtech-d1","subsequent","{\"tool\":\"role.wait\"}");
            frozen=world.autonomyRoleObservation("mfgtech-d1").toString();
        }
        try(var world=open(file)) { assertEquals(frozen,world.autonomyRoleObservation("mfgtech-d1").toString()); }
    }

    @Test void occupiedChildNamesFollowActualScheduledEdges() throws Exception {
        var file=dir.resolve("collisions.journal");String frozen;
        try(var world=open(file)) {
            var ref=decide(world,"mfgtech-d1","collision",POST); long now=world.snapshot().timeMillis();
            String canonical=ref.taskId()+":finish";
            world.schedulePublishDocument("occupy-finish",canonical,now+999_999,"occupied-finish-doc","mfgtech-d2",InformationModel.Audience.publicAudience(),"占位","占位","机制夹具",10,List.of());
            world.schedulePublishDocument("occupy-publish",canonical+"-2:publish",now+999_999,"occupied-publish-doc","mfgtech-d2",InformationModel.Audience.publicAudience(),"占位","占位","机制夹具",10,List.of());
            world.advanceTo(ref.dueMillis());var finish=next(world,GroupsRun.KIND_FINISH);assertEquals(canonical+"-2",finish.taskId());check(world,"mfgtech-d1",ref,"IN_PROGRESS",ref.taskId());
            world.advanceTo(finish.dueTimeMillis());var publish=next(world,GroupsRun.KIND_PUBLISH);assertEquals(finish.taskId()+":publish-2",publish.taskId());check(world,"mfgtech-d1",ref,"IN_PROGRESS",finish.taskId());
            world.advanceTo(publish.dueTimeMillis());check(world,"mfgtech-d1",ref,"APPLIED",publish.taskId());frozen=world.autonomyRoleObservation("mfgtech-d1").toString();
        }
        try(var world=open(file)) {assertEquals(frozen,world.autonomyRoleObservation("mfgtech-d1").toString());}
    }

    @Test void acceptedJoinAndRejectedBeginAndFinishUseActualReports() throws Exception {
        var file=dir.resolve("join-rejects.journal");String frozen;
        try(var world=groupFixture(file)) {
            var join=decide(world,"dave","join","{\"tool\":\"group.join\",\"args\":{\"groupId\":\"group:open\"}}");
            world.advanceTo(join.dueMillis());check(world,"dave",join,"IN_PROGRESS",join.taskId());
            var finish=next(world,GroupsRun.KIND_FINISH);world.advanceTo(finish.dueTimeMillis());check(world,"dave",join,"APPLIED",finish.taskId());
            var reject=decide(world,"erin","rejected","{\"tool\":\"group.post\",\"args\":{\"groupId\":\"group:open\",\"title\":\"无权\",\"body\":\"无权\",\"cites\":[]}}");
            world.advanceTo(reject.dueMillis());check(world,"erin",reject,"REJECTED",reject.taskId());
            var invite=decide(world,"alice","invite","{\"tool\":\"group.invite\",\"args\":{\"groupId\":\"group:open\",\"inviteePartyId\":\"erin\"}}");
            world.advanceTo(invite.dueMillis());check(world,"alice",invite,"IN_PROGRESS",invite.taskId());
            var ownFinish=world.snapshot().pending().stream().filter(t->t.taskId().equals(invite.taskId()+":finish")).findFirst().orElseThrow();
            String earlier=null;
            for(int i=0;i<1000;i++) {String candidate="other-invite-t-"+i;if(WorldScheduler.fairOrder(world.profile().manifest().seed(),WorldRuntime.roleTaskId("bob",candidate)).compareTo(WorldScheduler.fairOrder(world.profile().manifest().seed(),ownFinish.taskId()))<0) {earlier=candidate;break;}}
            assertNotNull(earlier,"必须找到相同完成时刻先执行的真实竞争任务");
            world.openRoleSession("bob").inviteToGroup("other-invite",earlier,ownFinish.dueTimeMillis(),new WorldRoleSession.GroupInviteDraft("group:open","erin"));
            world.advanceTo(ownFinish.dueTimeMillis());check(world,"alice",invite,"REJECTED",ownFinish.taskId());assertEquals("ALREADY_INVITED",result(world,"alice").path("reason").asText());
            frozen=world.autonomyRoleObservation("alice").toString();
        }
        try(var world=groupFixture(file)) {assertEquals(frozen,world.autonomyRoleObservation("alice").toString());}
    }

    @Test void missingOriginalIsByteExactAndMakesNoCallbacks() throws Exception {
        try(var world=open(dir.resolve("missing-original.journal"))) {
            var ref=decide(world,"mfgtech-d1","missing",POST);world.advanceTo(ref.dueMillis());
            var decisions=world.snapshot().decisions().orElseThrow();var pending=world.snapshot().pending();
            var root=baseline(decisions,"mfgtech-d1",ref.actionId(),pending);String before=root.toString();
            GroupActionFeedback.append(root,"mfgtech-d1",decisions,pending,reports(world.groupsRun(),List.of()),world.snapshot().position(),world.snapshot().timeMillis(),p->{fail("不得读提交");return java.util.Optional.empty();},(t,a,p)->{fail("不得解析任务");return null;});
            assertEquals(before,root.toString());
        }
    }

    @Test void historicalCutCannotBorrowFutureCompletionAndDuplicateRowsReadOnlyThreeFrames() throws Exception {
        var file=dir.resolve("cut.journal");
        try(var world=open(file)) {
            var ref=decide(world,"mfgtech-d1","cut",POST);world.advanceTo(ref.dueMillis());
            long cut=world.snapshot().position(),time=world.snapshot().timeMillis();var pending=world.snapshot().pending();var decisions=world.snapshot().decisions().orElseThrow();
            var stateField=WorldRuntime.class.getDeclaredField("state");stateField.setAccessible(true);var observed=stateField.get(world);
            var inputMethod=WorldRuntime.class.getDeclaredMethod("engineeringInput",stateField.getType(),String.class,long.class);inputMethod.setAccessible(true);
            String before=inputMethod.invoke(world,observed,"mfgtech-d1",time).toString();
            decide(world,"mfgtech-d1","observe-in-progress","{\"tool\":\"role.wait\"}");
            var finish=next(world,GroupsRun.KIND_FINISH);world.advanceTo(finish.dueTimeMillis());var publish=next(world,GroupsRun.KIND_PUBLISH);world.advanceTo(publish.dueTimeMillis());
            assertEquals(before,inputMethod.invoke(world,observed,"mfgtech-d1",time).toString(),"runtime当前位置不应进入历史观察");
            var reads=new java.util.concurrent.atomic.AtomicInteger();var root=baseline(decisions,"mfgtech-d1",ref.actionId(),pending);
            GroupActionFeedback.append(root,"mfgtech-d1",decisions,pending,world.groupsRun(),cut,time,p->{reads.incrementAndGet();return sourceUnchecked(world).nonSnapshotCommitAt(p);},parser(world));
            assertEquals("IN_PROGRESS",execution(root));assertEquals(1,reads.get());
            var terminal=baseline(decisions,"mfgtech-d1",ref.actionId(),List.of());reads.set(0);
            GroupActionFeedback.append(terminal,"mfgtech-d1",decisions,List.of(),world.groupsRun(),world.snapshot().position(),world.snapshot().timeMillis(),p->{reads.incrementAndGet();return sourceUnchecked(world).nonSnapshotCommitAt(p);},parser(world));
            assertEquals("APPLIED",execution(terminal));assertEquals(3,reads.get(),"recent与last不得重复读取相同动作阶段");
        }
        try(var reopened=open(file)) {assertEquals(3,reopened.groupsRun().reports().size(),"含进行中冻结输入的完整历史必须严格重演");}
    }
    static CommitSource sourceUnchecked(WorldRuntime world) {try{return source(world);}catch(Exception e){throw new AssertionError(e);}}

    @Test void onlyExactEdgesAndCompletedPendingTasksCanProveSuccess() throws Exception {
        try(var world=open(dir.resolve("forged.journal"))) {
            var ref=decide(world,"mfgtech-d1","forged",POST);world.advanceTo(ref.dueMillis());var beginPending=world.snapshot().pending();long beginPosition=world.snapshot().position(),beginTime=world.snapshot().timeMillis();
            var finish=next(world,GroupsRun.KIND_FINISH);world.advanceTo(finish.dueTimeMillis());var publish=next(world,GroupsRun.KIND_PUBLISH);world.advanceTo(publish.dueTimeMillis());
            var decisions=world.snapshot().decisions().orElseThrow();var groups=world.groupsRun();long position=world.snapshot().position(),time=world.snapshot().timeMillis();
            var base=source(world);var parse=parser(world);
            for(String mode:List.of("missing-edge","wrong-child-kind","different-command","changed-completed-task","missing-original-event","forged-original-wrapper","lookalike")) {
                var root=baseline(decisions,"mfgtech-d1",ref.actionId(),List.of());
                GroupActionFeedback.append(root,"mfgtech-d1",decisions,List.of(),groups,position,time,p->{
                    var c=base.nonSnapshotCommitAt(p).orElseThrow();var ev=new java.util.ArrayList<WorldEvent>();
                    for(var e:c.events()) {
                        boolean begin=c.commandId().equals("task:"+ref.taskId());
                        if(begin&&mode.equals("missing-edge")&&e.type().equals("TASK_SCHEDULED_V1"))continue;
                        if(begin&&mode.equals("missing-original-event")&&e.type().equals("GROUP_TASK_V1"))continue;
                        try {
                            if(begin&&mode.equals("wrong-child-kind")&&e.type().equals("TASK_SCHEDULED_V1")) {var t=JSON.readValue(e.payload(),PendingTask.class);e=new WorldEvent(e.eventId(),e.type(),JSON.writeValueAsString(new PendingTask(t.taskId(),t.dueTimeMillis(),t.phase(),GroupsRun.KIND_PUBLISH,t.payload())));}
                            if(c.commandId().equals("task:"+finish.taskId())&&e.type().equals("GROUP_TASK_V1")&&(mode.equals("different-command")||mode.equals("changed-completed-task")||mode.equals("lookalike"))) {
                                var t=JSON.readValue(e.payload(),PendingTask.class);String body=t.payload();
                                if(mode.equals("different-command")) {var b=(ObjectNode)JSON.readTree(body);b.put("commandId","another-command");body=b.toString();}
                                if(mode.equals("changed-completed-task"))body=body+" ";
                                String id=mode.equals("lookalike")?t.taskId()+":lookalike":t.taskId();
                                e=new WorldEvent("group-task:"+id,e.type(),JSON.writeValueAsString(new PendingTask(id,t.dueTimeMillis(),t.phase(),t.kind(),body)));
                            }
                            if(begin&&mode.equals("forged-original-wrapper")&&e.type().equals("GROUP_TASK_V1")) {var t=JSON.readValue(e.payload(),PendingTask.class);var b=(ObjectNode)JSON.readTree(t.payload());b.put("clientTaskId","foreign-action");e=new WorldEvent(e.eventId(),e.type(),JSON.writeValueAsString(new PendingTask(t.taskId(),t.dueTimeMillis(),t.phase(),t.kind(),b.toString())));}
                        }catch(Exception ex){throw new AssertionError(ex);}ev.add(e);
                    } if(List.of("different-command","changed-completed-task","forged-original-wrapper").contains(mode)) { for(var e:ev) if(e.type().equals("GROUP_TASK_V1")) {try {var t=JSON.readValue(e.payload(),PendingTask.class);c=new WorldCommit(c.schemaVersion(),c.position(),c.runId(),c.commandId(),t.payload(),c.result(),c.events());}catch(Exception ex){throw new AssertionError(ex);}} } return java.util.Optional.of(events(c,ev));
                },parse);
                assertEquals("UNKNOWN",execution(root),mode);assertFalse(root.path("deliberation").path("lastActionResult").has("accepted"),mode);
            }
            var missing=baseline(decisions,"mfgtech-d1",ref.actionId(),List.of());
            GroupActionFeedback.append(missing,"mfgtech-d1",decisions,List.of(),groups,beginPosition,beginTime,base::nonSnapshotCommitAt,parse);
            assertEquals("UNKNOWN",execution(missing),"待办消失不能冒充成功");
            var changedPending=new PendingTask(finish.taskId(),finish.dueTimeMillis(),finish.phase(),finish.kind(),finish.payload()+" ");
            var changed=baseline(decisions,"mfgtech-d1",ref.actionId(),List.of(changedPending));
            GroupActionFeedback.append(changed,"mfgtech-d1",decisions,List.of(changedPending),groups,beginPosition,beginTime,base::nonSnapshotCommitAt,parse);
            assertEquals("UNKNOWN",execution(changed),"同名但不同完整任务不能证明仍在执行");
            assertFalse(beginPending.isEmpty());
            var corrupt=baseline(decisions,"mfgtech-d1",ref.actionId(),List.of());
            assertThrows(java.io.UncheckedIOException.class,()->GroupActionFeedback.append(corrupt,"mfgtech-d1",decisions,List.of(),groups,position,time,p->{throw new java.io.UncheckedIOException(new java.io.IOException("real disk failure"));},parse));
        }
    }

    /** 仅检验同一准确发布阶段报告的拒绝投影，不宣称自主业务发生了版本冲突。 */
    @Test void exactRejectedPublishReportProjectsItsOwnReasonAndIdentity() throws Exception {
        try(var world=open(dir.resolve("publish-reject-projection.journal"))) {
            var ref=decide(world,"mfgtech-d1","publish-reject",POST);world.advanceTo(ref.dueMillis());var finish=next(world,GroupsRun.KIND_FINISH);world.advanceTo(finish.dueTimeMillis());var publish=next(world,GroupsRun.KIND_PUBLISH);world.advanceTo(publish.dueTimeMillis());
            var changed=world.groupsRun().reports().stream().map(r->r.taskId().equals(publish.taskId())?new GroupsRun.GroupReport(r.taskId(),r.person(),r.kind(),r.action(),false,"STALE_VERSION",r.subject(),r.atMillis(),r.position()):r).toList();
            var decisions=world.snapshot().decisions().orElseThrow();var root=baseline(decisions,"mfgtech-d1",ref.actionId(),List.of());
            GroupActionFeedback.append(root,"mfgtech-d1",decisions,List.of(),reports(world.groupsRun(),changed),world.snapshot().position(),world.snapshot().timeMillis(),source(world)::nonSnapshotCommitAt,parser(world));
            var result=root.path("deliberation").path("lastActionResult");assertEquals("REJECTED",execution(root));assertFalse(result.path("accepted").asBoolean());assertEquals("STALE_VERSION",result.path("businessReject").asText());assertEquals(publish.taskId(),result.path("resultTaskId").asText());assertEquals(GroupsRun.KIND_PUBLISH,result.path("resultKind").asText());
        }
    }

    @Test void inheritedPrefixVerificationReplaysFrozenGroupFeedbackWithoutJournalState() throws Exception {
        WorldRuntime.BranchReceipt receipt;BranchProfile profile;String frozen;
        try(var parent=open(dir.resolve("parent.journal"))) {
            var ref=decide(parent,"mfgtech-d1","parent-post",POST);parent.advanceTo(ref.dueMillis());decide(parent,"mfgtech-d1","parent-in-progress","{\"tool\":\"role.wait\"}");var finish=next(parent,GroupsRun.KIND_FINISH);parent.advanceTo(finish.dueTimeMillis());var publish=next(parent,GroupsRun.KIND_PUBLISH);parent.advanceTo(publish.dueTimeMillis());
            decide(parent,"mfgtech-d1","parent-wait","{\"tool\":\"role.wait\"}");frozen=parent.autonomyRoleObservation("mfgtech-d1").path("engineering").path("deliberation").path("recentDecisions").toString();profile=parent.profile();receipt=parent.createBranch("feedback-child",parent.snapshot().position(),BranchOrigin.Kind.OPERATOR_BRANCH,"群反馈前缀验证");
        }
        try(var child=WorldRuntime.openBranch(receipt.childFile(),id->receipt.sealedPrefix(),profile)) {
            assertEquals(frozen,child.autonomyRoleObservation("mfgtech-d1").path("engineering").path("deliberation").path("recentDecisions").toString());
            assertTrue(source(child) instanceof BranchCommitSource);
            var inherited=source(child).nonSnapshotCommitAt(1).orElseThrow();assertNotEquals(child.profile().manifest().runId(),inherited.runId());
        }
    }
}
