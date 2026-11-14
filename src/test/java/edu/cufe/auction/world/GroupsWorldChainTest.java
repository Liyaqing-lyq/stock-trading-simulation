package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema20 真实 World 接入：群发帖→投递→成员实际读、退出止新读但既有读保留、
 * 平台发布→真实决定输入绑定呈现且只查目录不算曝光。
 *
 * <p>全部使用真实 WorldRuntime/WorldJournal 与真实任务帧，不使用纯核桩冒充端到端；
 * 决定只用 live 收件箱注入确定性回执，0 真实 provider 调用。</p>
 */
class GroupsWorldChainTest {

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "groups-world-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static List<InformationModel.CatalogEntry> catalogOf(WorldRuntime world, String person)
            throws Exception {
        return world.openRoleSession(person).view().profile().catalog();
    }

    private static boolean catalogHas(WorldRuntime world, String person, String docId)
            throws Exception {
        return catalogOf(world, person).stream().anyMatch(entry -> entry.docId().equals(docId));
    }

    private static GroupModel.Post postOf(GroupsRun run, String docId) {
        for (GroupModel.Post post : run.state().posts()) {
            if (post.docId().equals(docId)) {
                return post;
            }
        }
        return null;
    }

    @Test
    void groupPostDeliversThenMembersActuallyRead() throws Exception {
        Path file = journal("post-read");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            WorldRoleSession alice = world.openRoleSession("alice");
            long now = world.snapshot().timeMillis();
            String scheduled = alice.postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "季度进展", "三人群可见的正文", List.of()));
            assertEquals("SCHEDULED:gpost-t", scheduled);
            world.advanceTo(now + 20);

            GroupsRun finished = world.groupsRun();
            GroupModel.Post queued = finished.state().posts().get(0);
            assertEquals(GroupModel.PostStatus.QUEUED, queued.status(),
                    "完成帧只排队，尚未发布");
            assertEquals(now + 25, queued.publishAt(), "发布时刻 = 完成 + 冻结投递等待");

            world.advanceTo(queued.publishAt());
            GroupsRun published = world.groupsRun();
            GroupModel.Post post = postOf(published, queued.docId());
            assertNotNull(post, "投递帧未留下帖子；reports=" + published.reports());
            assertEquals(GroupModel.PostStatus.PUBLISHED, post.status(),
                    "系统投递帧必须真正发布：" + published.reports());

            // 正文与受众唯一落在 InformationModel，受众是 GROUP + 真实群标识
            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            InformationModel.Document document = null;
            for (InformationModel.Document candidate : info.documents()) {
                if (candidate.docId().equals(post.docId())) {
                    document = candidate;
                }
            }
            assertNotNull(document, "投递必须生成真实资料");
            assertEquals(InformationModel.AudienceKind.GROUP, document.audience().kind());
            assertEquals(WorldWebScenario.GROUPS_OPEN, document.audience().partyId());
            assertEquals("alice", document.publisherPartyId());

            // 成员可见、外人不可见；未知群也不会被目录暴露
            assertTrue(catalogHas(world, "alice", post.docId()), "群主必须看到群帖");
            assertTrue(catalogHas(world, "bob", post.docId()), "初始成员必须看到群帖");
            assertFalse(catalogHas(world, "dave", post.docId()), "外人不得看到群帖");

            // 成员真实开读并读完
            long at = world.snapshot().timeMillis();
            world.scheduleStartReading("read-bob", "read-bob-t", at + 10, "bob", post.docId(), 1L);
            // 开始门在 at+10，读完在 at+10+冻结阅读耗时
            world.advanceTo(at + 20);
            InformationModel.State afterRead = world.snapshot().information().orElseThrow()
                    .information();
            boolean observed = InformationModel.observations(afterRead, "bob").stream()
                    .anyMatch(item -> item.docId().equals(post.docId()) && item.version() == 1);
            assertTrue(observed, "读完必须留下唯一阅读经历；reports="
                    + world.snapshot().information().orElseThrow().reports());
            assertTrue(InformationModel.canCite(afterRead, "bob", post.docId(), 1L,
                    world.snapshot().timeMillis()), "读完后可引用");
        }
    }

    @Test
    void leavingStopsNewReadsButKeepsPastReadsAndRejoinStartsNewSegment() throws Exception {
        Path file = journal("leave");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            WorldRoleSession alice = world.openRoleSession("alice");
            long now = world.snapshot().timeMillis();
            alice.postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "季度进展", "三人群可见的正文", List.of()));
            world.advanceTo(now + 20);
            String docId = world.groupsRun().state().posts().get(0).docId();
            long publishAt = world.snapshot().timeMillis() + 5;
            world.advanceTo(publishAt);

            // bob 先真实读完
            world.scheduleStartReading("read-1", "read-1-t", publishAt + 10, "bob", docId, 1L);
            world.advanceTo(publishAt + 20);
            InformationModel.State read = world.snapshot().information().orElseThrow()
                    .information();
            int observations = InformationModel.observations(read, "bob").size();
            assertTrue(observations >= 1);

            // 退群：正工时动作，到期才关闭成员段
            WorldRoleSession bob = world.openRoleSession("bob");
            long t = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:leave-t", bob.leaveGroup("leave", "leave-t", t + 10,
                    new WorldRoleSession.GroupLeaveDraft(WorldWebScenario.GROUPS_OPEN)));
            world.advanceTo(t + 10);
            GroupsRun afterLeave = world.groupsRun();
            assertTrue(afterLeave.membershipAt(WorldWebScenario.GROUPS_OPEN, "bob", t + 10)
                    .isEmpty(), "退出后没有开放成员段");

            // 新访问被统一读取门拒绝：目录不再列出
            assertFalse(catalogHas(world, "bob", docId), "退群后不再出现在目录");

            // 既有阅读事实与可回忆正文按 Recall 保留：canCite 仍为真，经历数不变
            InformationModel.State left = world.snapshot().information().orElseThrow()
                    .information();
            assertTrue(InformationModel.canCite(left, "bob", docId, 1L,
                    world.snapshot().timeMillis()), "过去已读不因退群抹除");
            assertEquals(observations, InformationModel.observations(left, "bob").size());

            // 再开读：排程后到期完成必须被拒（不新访问、不新增经历）
            long at = world.snapshot().timeMillis();
            world.scheduleStartReading("read-2", "read-2-t", at + 10, "bob", docId, 1L);
            world.advanceTo(at + 20);
            InformationModel.State blocked = world.snapshot().information().orElseThrow()
                    .information();
            assertEquals(observations, InformationModel.observations(blocked, "bob").size(),
                    "退群后不得新增阅读经历");

            // 重入：新成员段从新加入时刻起，JOIN_TIME 只给段内版本
            long rejoin = world.snapshot().timeMillis();
            bob.joinGroup("join", "join-t", rejoin + 10,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN));
            world.advanceTo(rejoin + 10);
            GroupsRun rejoined = world.groupsRun();
            GroupModel.Membership segment = rejoined
                    .membershipAt(WorldWebScenario.GROUPS_OPEN, "bob", rejoin + 10).orElseThrow();
            assertEquals(rejoin + 10, segment.joinedAt(), "重入开启新段，不恢复旧段");
            assertFalse(catalogHas(world, "bob", docId),
                    "JOIN_TIME 不得把入群前版本当作可读");
            assertTrue(InformationModel.canCite(world.snapshot().information().orElseThrow()
                    .information(), "bob", docId, 1L, world.snapshot().timeMillis()),
                    "旧读仍可回忆，但不等于新访问权限");
        }
    }

    @Test
    void platformPostBindsRealDecisionInputAndRecordsPresentationOnce() throws Exception {
        Path file = journal("platform");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            WorldRoleSession alice = world.openRoleSession("alice");
            long now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:ppost-t", alice.postToPlatform("ppost", "ppost-t", now + 15,
                    new WorldRoleSession.PlatformPostDraft("平台公告", "公开正文", List.of())));
            world.advanceTo(now + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            GroupsRun run = world.groupsRun();
            assertFalse(run.publication().platform().posts().isEmpty(),
                    "平台帖未生成；reports=" + run.reports());
            PlatformPostModel.Post post = run.publication().platform().posts().get(0);
            assertEquals(PlatformPostModel.PostStatus.PUBLISHED, post.status(),
                    "平台投递必须真正发布：" + run.reports());

            // 只查目录/会话视图不是曝光：呈现帧数不变
            int before = PublicationRun.projectedTo(run.publication(), post.docId(), 1L);
            assertEquals(0, before);
            assertTrue(catalogHas(world, "bob", post.docId()), "平台公开帖必须进目录");
            assertEquals(before, PublicationRun.projectedTo(world.groupsRun().publication(),
                    post.docId(), 1L), "查目录不得写成曝光");

            // 真实决定：输入必须含本人可见的平台目录，并落一条与真实 action/attempt/digest 绑定的帧
            world.beginDecisionSession("open-p");
            world.openDecisions("d-p", List.of(new DecisionRun.Request("bob", "r-group", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                            1_000_000, 1_024, 1))));
            DecisionRun.Call call = world.dispatchLiveDecision("cmd-p", "r-group").call();
            assertNotNull(call);
            String input = call.inputText();
            assertTrue(input.contains("\"groups\""), "v6 输入必须带群组/平台投影");
            assertTrue(input.contains(post.docId()), "真实目录必须进入输入：" + input);
            assertTrue(input.contains("\"rank\":1"), "目录必须带冻结排名");

            GroupsRun presented = world.groupsRun();
            assertEquals(1, PublicationRun.projectedTo(presented.publication(), post.docId(), 1L),
                    "同一真实尝试只记一帧");
            PublicationRun.PresentationFrame frame = presented.publication().frames().get(0);
            assertEquals("r-group", frame.actionId());
            DecisionModel.Decision decision = DecisionModel.decision(
                    world.snapshot().decisions().orElseThrow().state(), "r-group").orElseThrow();
            assertEquals(decision.inputDigest(), frame.inputDigest(),
                    "帧必须绑定真实输入摘要");
            assertEquals(decision.lastAttempt().attemptId(), frame.attemptId(),
                    "帧必须绑定真实尝试");

            // 再次查询目录与另一人视图都不新增帧
            catalogOf(world, "bob");
            catalogOf(world, "alice");
            assertEquals(1, PublicationRun.projectedTo(world.groupsRun().publication(),
                    post.docId(), 1L), "查询不得伪造曝光");
        }
    }

    @Test
    void outsiderCannotLearnInviteOnlyGroupByGuessing() throws Exception {
        Path file = journal("outsider");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            WorldRoleSession carol = world.openRoleSession("carol");
            long now = world.snapshot().timeMillis();
            // 猜私群 ID 与猜不存在的 ID 必须给出同一个结果（不得成为存在性 oracle）
            String guessedPrivate = carol.joinGroup("guess", "guess-t", now + 10,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_INVITE_ONLY));
            long after = world.snapshot().timeMillis();
            String guessedUnknown = carol.joinGroup("guess2", "guess2-t", after + 10,
                    new WorldRoleSession.GroupJoinDraft("group:no-such"));
            assertEquals("NOT_AVAILABLE", guessedPrivate);
            assertEquals(guessedUnknown, guessedPrivate,
                    "私群与未知群必须同一结果，不能泄露存在性");
            // 两种拒绝都不留下成员段，也不把群写进本人目录
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_INVITE_ONLY,
                    "carol", after + 10).isEmpty());
            assertFalse(catalogHas(world, "carol", "group:no-such"));
            // 本人输入不出现任何未授权私群标识
            String input = world.openRoleSession("carol").view().toString();
            assertFalse(input.contains(WorldWebScenario.GROUPS_INVITE_ONLY),
                    "外人输入不得出现私群标识");
        }
    }

    @Test
    void legitimateMembersAndInviteesStillSeeTheirGroup() throws Exception {
        Path file = journal("legit");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            // 公群：任何人可真实加入，存在性按公开设计可见
            WorldRoleSession dave = world.openRoleSession("dave");
            long now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:join-dave-t", dave.joinGroup("join-dave", "join-dave-t",
                    now + 10, new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN)));
            world.advanceTo(now + 10);
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_OPEN, "dave",
                    now + 10).isPresent());
            // 私群成员 bob 仍能邀请；受邀人 dave 的输入出现该邀请（合法知情）
            WorldRoleSession bob = world.openRoleSession("bob");
            long t = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:inv-t", bob.inviteToGroup("inv", "inv-t", t + 10,
                    new WorldRoleSession.GroupInviteDraft(WorldWebScenario.GROUPS_INVITE_ONLY,
                            "dave")));
            world.advanceTo(t + 10);
            GroupModel.Invitation invitation = world.groupsRun().state().invitations().get(0);
            assertEquals("dave", invitation.invitee(), "受邀人保留合法知情");
            // 受邀人可以用真实邀请进入私群（能力不被隐藏规则削弱）
            long r = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:resp-t", dave.respondInvitation("resp", "resp-t", r + 10,
                    new WorldRoleSession.GroupRespondDraft(invitation.inviteId(), true)));
            world.advanceTo(r + 10);
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_INVITE_ONLY,
                    "dave", r + 10).isPresent(), "接受邀请后建立成员段");
        }
    }

    @Test
    void groupsSurviveRealCrossProcessRecovery() throws Exception {
        Path file = journal("cross-process");
        String phase1 = runGroupsProcess(file, "post");
        assertTrue(phase1.contains("PHASE1 status=QUEUED"), phase1);
        String phase2 = runGroupsProcess(file, "publish");
        assertTrue(phase2.contains("PHASE2 status=PUBLISHED"), phase2);
        assertTrue(phase2.contains("observations=1"), phase2);
        String phase3 = runGroupsProcess(file, "verify");
        assertTrue(phase3.contains("PHASE3"), phase3);
        assertTrue(phase3.contains("catalog=true"), phase3);
    }

    /** 真跨进程：另起 JVM 跑探针（不 close/open 同一进程冒充）。 */
    private static String runGroupsProcess(Path journal, String phase) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "edu.cufe.auction.world.GroupsCrossProcessProbe", journal.toString(), phase);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "子进程失败：" + output);
        return output;
    }

    @Test
    void publicRepostOfGroupPostKeepsSingleRootAndDoesNotLeakGroupText() throws Exception {
        Path file = journal("repost");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            // alice 在群内发原帖（无来源，自身即独立陈述）
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "群内原帖", "只应留在群内的正文", List.of()));
            world.advanceTo(now + 20);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            String groupDoc = world.groupsRun().state().posts().get(0).docId();

            // bob 合法读完群帖后，在平台公开转述（同源）
            long t = world.snapshot().timeMillis();
            world.scheduleStartReading("read-g", "read-g-t", t + 10, "bob", groupDoc, 1L);
            world.advanceTo(t + 20);
            long p = world.snapshot().timeMillis();
            world.openRoleSession("bob").postToPlatform("repost", "repost-t", p + 15,
                    new WorldRoleSession.PlatformPostDraft("公开转述", "公开可见的转述文本",
                            List.of(new WorldRoleSession.Citation(groupDoc, 1L))));
            world.advanceTo(p + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            PlatformPostModel.Post repost = world.groupsRun().publication().platform()
                    .posts().get(0);
            assertEquals(PlatformPostModel.PostStatus.PUBLISHED, repost.status(),
                    "reports=" + world.groupsRun().reports());
            assertEquals(1, repost.originRefs().size(), "同源转述只保留一个原始根");
            assertEquals(groupDoc, repost.originRefs().get(0).docId());

            // 公开读者只知公开文：目录有转述、没有群帖；不能引用群帖原文
            assertTrue(catalogHas(world, "dave", repost.docId()), "公开转述对所有人可见");
            assertFalse(catalogHas(world, "dave", groupDoc), "公开读者不得看到群内原文");
            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            assertFalse(InformationModel.canCite(info, "dave", groupDoc, 1L,
                    world.snapshot().timeMillis()), "公开读者不得取得私源引用权");

            // 更正转述仍指向同一原始根：不成为新的独立证据
            long c = world.snapshot().timeMillis();
            world.openRoleSession("bob").correctPlatformPost("fix", "fix-t", c + 15,
                    new WorldRoleSession.PlatformCorrectDraft(repost.docId(), 1L, "公开转述（更正）",
                            "更正后的公开文本",
                            List.of(new WorldRoleSession.Citation(groupDoc, 1L))));
            world.advanceTo(c + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            PlatformPostModel.Post corrected = null;
            for (PlatformPostModel.Post post : world.groupsRun().publication().platform()
                    .posts()) {
                if (post.docId().equals(repost.docId())) {
                    corrected = post;
                }
            }
            assertNotNull(corrected);
            assertEquals(2L, corrected.version());
            assertEquals(repost.originRefs(), corrected.originRefs(),
                    "更正不产生第二个独立来源");
        }
    }

    @Test
    void correctionCannotRevealWhetherOthersPostExists() throws Exception {
        Path file = journal("correct-oracle");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "他人帖子", "正文", List.of()));
            world.advanceTo(now + 20);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            String docId = world.groupsRun().state().posts().get(0).docId();

            // dave 公开加入该 OPEN 群（群名可知），但入群晚于发布：JOIN_TIME 不得读旧帖
            WorldRoleSession dave = world.openRoleSession("dave");
            long t = world.snapshot().timeMillis();
            dave.joinGroup("join-d", "join-d-t", t + 10,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN));
            world.advanceTo(t + 10);
            assertFalse(catalogHas(world, "dave", docId), "新加入者不得读入群前旧帖");

            long c1 = world.snapshot().timeMillis();
            String existing = dave.correctGroupPost("c1", "c1-t", c1 + 20,
                    new WorldRoleSession.GroupCorrectDraft(docId, 1L, "改", "改", List.of()));
            long c2 = world.snapshot().timeMillis();
            String unknown = dave.correctGroupPost("c2", "c2-t", c2 + 20,
                    new WorldRoleSession.GroupCorrectDraft("GDOC-forged-guess", 1L, "改", "改",
                            List.of()));
            // 非作者：存在帖与不存在帖必须同一结果（不泄露帖子存在性）
            assertEquals("NOT_AVAILABLE", existing);
            assertEquals(existing, unknown, "不得用原因码区分他人帖子是否存在");
            // 作者本人仍拿到可执行原因（自己的帖子可更正或看到真实冲突）
            WorldRoleSession alice = world.openRoleSession("alice");
            long c3 = world.snapshot().timeMillis();
            String mine = alice.correctGroupPost("c3", "c3-t", c3 + 20,
                    new WorldRoleSession.GroupCorrectDraft(docId, 1L, "改", "改", List.of()));
            assertEquals("SCHEDULED:c3-t", mine, "作者本人更正是正常能力");
        }
    }

    @Test
    void groupSourceFlowsThroughRealMediaEditorialChain() throws Exception {
        Path file = journal("media-chain");
        // 记者 rita 作为公开群初始成员（明确开局禀赋），其余登记/任期与默认群组档一致
        GroupModel.Config config = new GroupModel.Config(List.of(
                new GroupModel.Group(WorldWebScenario.GROUPS_OPEN, "alice",
                        GroupModel.JoinPolicy.OPEN, GroupModel.HistoryPolicy.JOIN_TIME, 0L,
                        List.of("bob", "carol", "rita")),
                new GroupModel.Group(WorldWebScenario.GROUPS_INVITE_ONLY, "bob",
                        GroupModel.JoinPolicy.INVITE_ONLY, GroupModel.HistoryPolicy.JOIN_TIME, 0L,
                        List.of("alice"))),
                WorldWebScenario.GROUPS_JOIN_MILLIS, WorldWebScenario.GROUPS_INVITE_MILLIS,
                WorldWebScenario.GROUPS_RESPOND_MILLIS, WorldWebScenario.GROUPS_LEAVE_MILLIS,
                WorldWebScenario.GROUPS_POST_MILLIS, WorldWebScenario.GROUPS_DELIVERY_MILLIS);
        MediaRun.Config media = WorldWebScenario.mediaConfig();
        MediaModel.Policy policy = media.policies().stream()
                .filter(candidate -> candidate.mediaOrganizationPartyId().equals("wire"))
                .findFirst().orElseThrow();
        String org = policy.mediaOrganizationPartyId();
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file, config)) {
            // 群内原帖（真实发布）
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "群内线索", "群内可见的原始叙述", List.of()));
            world.advanceTo(now + 20);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            String groupDoc = world.groupsRun().state().posts().get(0).docId();

            // 记者真实读完群帖（唯一正文仍在 Info）
            long t = world.snapshot().timeMillis();
            world.scheduleStartReading("read-r", "read-r-t", t + 10, "rita", groupDoc, 1L);
            world.advanceTo(t + 20);

            // 待办 → 撰稿 → 提交 → 审阅 → 发布（既有 Media 采编链）
            long p = world.snapshot().timeMillis();
            world.openRoleSession("rita").proposeStory("p1", "p1-t", p + media.proposeMillis(),
                    new WorldRoleSession.MediaProposeDraft("story-1", "WIRE", "群内线索解读"));
            world.advanceTo(p + media.proposeMillis());
            long s = world.snapshot().timeMillis();
            world.openRoleSession("rita").submitStory("s1", "s1-t", s + policy.writeMillis(),
                    new WorldRoleSession.MediaSubmitDraft(
                            WorldRuntime.mediaStoryKey("rita", "story-1"), 0, "公开快讯",
                            "公开成稿正文", "据群内讨论",
                            List.of(new WorldRoleSession.MediaCitation(groupDoc, 1L, 1L))));
            world.advanceTo(s + policy.writeMillis());
            MediaModel.Story submitted = null;
            for (MediaModel.Story story : world.snapshot().media().orElseThrow()
                    .stateOf(org).stories()) {
                if (story.storyId().equals(WorldRuntime.mediaStoryKey("rita", "story-1"))) {
                    submitted = story;
                }
            }
            assertNotNull(submitted, "群来源稿件必须被采编链接受："
                    + world.snapshot().media().orElseThrow().reports());
            assertEquals(MediaModel.Status.SUBMITTED, submitted.status(),
                    "群来源稿件必须被采编链接受：" + world.snapshot().media().orElseThrow()
                            .reports());

            // 编辑真实读完私稿后批准并发布
            long r = world.snapshot().timeMillis();
            world.scheduleStartReading("read-ed", "read-ed-t", r + 10, "ed",
                    submitted.draftDocId(), 1L);
            world.advanceTo(r + 10 + policy.draftReadMillis());
            long v = world.snapshot().timeMillis();
            world.openRoleSession("ed").reviewStory("rv", "rv-t", v + policy.reviewMillis(),
                    new WorldRoleSession.MediaReviewDraft(
                            WorldRuntime.mediaStoryKey("rita", "story-1"), 1, true, ""));
            world.advanceTo(v + policy.reviewMillis());
            long b = world.snapshot().timeMillis();
            world.openRoleSession("ed").publishStory("pb", "pb-t", b + policy.publishMillis(),
                    new WorldRoleSession.MediaStoryDraft(
                            WorldRuntime.mediaStoryKey("rita", "story-1"), 1));
            world.advanceTo(b + policy.publishMillis());
            MediaModel.Story published = null;
            for (MediaModel.Story story : world.snapshot().media().orElseThrow()
                    .stateOf(org).stories()) {
                if (story.storyId().equals(WorldRuntime.mediaStoryKey("rita", "story-1"))) {
                    published = story;
                }
            }
            assertNotNull(published, world.snapshot().media().orElseThrow().reports().toString());
            assertEquals(MediaModel.Status.PUBLISHED, published.status());
            // 公开读者只拿到公开成稿正文，不取得群内原文/私标识
            String publicDoc = published.publicDocId();
            assertTrue(catalogHas(world, "dave", publicDoc), "公开成稿对所有人可见");
            assertFalse(catalogHas(world, "dave", groupDoc), "公开读者不得看到群内原文");
            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            assertFalse(InformationModel.canCite(info, "dave", groupDoc, 1L,
                    world.snapshot().timeMillis()), "公开读者不得取得群内原文引用权");
        }
    }

    @Test
    void invitationRequiresRealMemberInviterAndTheNamedInvitee() throws Exception {
        Path file = journal("invite");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            WorldRoleSession bob = world.openRoleSession("bob");
            long now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:inv-t", bob.inviteToGroup("inv", "inv-t", now + 10,
                    new WorldRoleSession.GroupInviteDraft(WorldWebScenario.GROUPS_INVITE_ONLY,
                            "dave")));
            world.advanceTo(now + 10);
            GroupModel.Invitation invitation = world.groupsRun().state().invitations().get(0);
            assertEquals(GroupModel.InvitationStatus.PENDING, invitation.status());

            // 受邀人以外的人不能处理该邀请
            WorldRoleSession carol = world.openRoleSession("carol");
            long t = world.snapshot().timeMillis();
            String foreign = carol.respondInvitation("resp-x", "resp-x-t", t + 10,
                    new WorldRoleSession.GroupRespondDraft(invitation.inviteId(), true));
            assertFalse(foreign.startsWith("SCHEDULED"), "非受邀人不得接受：" + foreign);
            world.advanceTo(t + 10);
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_INVITE_ONLY,
                    "carol", t + 10).isEmpty());

            // 邀请人先退群：后续邀请必须被拒，且不留下新邀请
            int invitations = world.groupsRun().state().invitations().size();
            bob.leaveGroup("leave-bob", "leave-bob-t", t + 20,
                    new WorldRoleSession.GroupLeaveDraft(WorldWebScenario.GROUPS_INVITE_ONLY));
            world.advanceTo(t + 20);
            String afterLeave = bob.inviteToGroup("inv2", "inv2-t", t + 30,
                    new WorldRoleSession.GroupInviteDraft(WorldWebScenario.GROUPS_INVITE_ONLY,
                            "erin"));
            assertFalse(afterLeave.startsWith("SCHEDULED"), "非成员不得再邀请：" + afterLeave);
            assertEquals(invitations, world.groupsRun().state().invitations().size());
        }
    }

    @Test
    void groupActionBlocksSamePersonReadingButNotOthers() throws Exception {
        Path file = journal("focus");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            // 先有一条公开平台帖，供阅读目标
            WorldRoleSession alice = world.openRoleSession("alice");
            long now = world.snapshot().timeMillis();
            alice.postToPlatform("ppost", "ppost-t", now + 15,
                    new WorldRoleSession.PlatformPostDraft("平台公告", "公开正文", List.of()));
            world.advanceTo(now + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            String docId = world.groupsRun().publication().platform().posts().get(0).docId();

            long t = world.snapshot().timeMillis();
            WorldRoleSession dave = world.openRoleSession("dave");
            dave.joinGroup("join-dave", "join-dave-t", t + 10,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN));
            // 同人同刻起阅读：本人焦点被群组动作占用，必须拒绝
            IllegalArgumentException busy = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> world.scheduleStartReading("read-c", "read-c-t", t + 10, "dave",
                            docId, 1L));
            assertTrue(busy.getMessage().contains("群组"), busy.getMessage());
            // 不同人物不受影响
            world.scheduleStartReading("read-d", "read-d-t", t + 10, "bob", docId, 1L);
        }
    }

    @Test
    void correctionPublishesNewVersionAndJournalReopens() throws Exception {
        Path file = journal("correction");
        long position;
        String docId;
        InformationModel.State finalInfo;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            WorldRoleSession alice = world.openRoleSession("alice");
            long now = world.snapshot().timeMillis();
            alice.postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "原始标题", "原始正文", List.of()));
            world.advanceTo(now + 20);
            docId = world.groupsRun().state().posts().get(0).docId();
            world.advanceTo(world.snapshot().timeMillis() + 5);

            // bob 读完 v1
            long t = world.snapshot().timeMillis();
            world.scheduleStartReading("read-v1", "read-v1-t", t + 10, "bob", docId, 1L);
            world.advanceTo(t + 20);

            // 作者更正：新版本必须真实发布，旧版本与旧经历保留
            long c = world.snapshot().timeMillis();
            alice.correctGroupPost("corr", "corr-t", c + 20,
                    new WorldRoleSession.GroupCorrectDraft(docId, 1L, "更正标题", "更正正文",
                            List.of()));
            world.advanceTo(c + 20);
            GroupsRun queued = world.groupsRun();
            GroupModel.PostVersion version = queued.state().postVersions().get(0);
            assertEquals(2L, version.version());
            assertEquals(GroupModel.PostStatus.QUEUED, version.status(),
                    "reports=" + queued.reports());
            world.advanceTo(version.publishAt());

            finalInfo = world.snapshot().information().orElseThrow().information();
            assertTrue(InformationModel.canCite(finalInfo, "bob", docId, 1L, c + 60),
                    "旧版阅读经历保留");
            InformationModel.Document document = null;
            for (InformationModel.Document candidate : finalInfo.documents()) {
                if (candidate.docId().equals(docId)) {
                    document = candidate;
                }
            }
            assertNotNull(document);
            assertEquals(2, document.versions().size(), "更正必须追加真实版本");
            assertEquals("更正标题", document.versions().get(1).title());
            position = world.snapshot().position();
        }
        // 新 JVM：同一 journal 逐帧复原，群组配置与状态逐字一致
        try (WorldRuntime reopened = WorldWebScenario.groupsRuntime(file)) {
            assertEquals(position, reopened.snapshot().position());
            GroupsRun groups = reopened.groupsRun();
            GroupModel.PostVersion version = groups.state().postVersions().get(0);
            assertEquals(GroupModel.PostStatus.PUBLISHED, version.status());
            assertEquals(docId, version.docId());
            assertEquals(finalInfo, reopened.snapshot().information().orElseThrow()
                    .information());
        }
    }
}
