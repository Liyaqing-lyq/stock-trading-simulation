package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 机构四岗的不可变工作流。现金、持仓、成交和冻结由世界账提供，额度按真实订单派生。
 * 调用方将本模块与交易及资料候选在同一世界批次确认。
 */
public final class RoleTradingModel {

    /** 此实验档案的组织研究报告阅读耗时，一模拟分钟。 */
    public static final long RESEARCH_READ_MILLIS = 60_000L;
    /** 证据未实际读完。 */
    public static final String REASON_EVIDENCE_NOT_READ = "EVIDENCE_NOT_READ";
    /** 同账户标的已有在处理的目标。 */
    public static final String REASON_DUPLICATE_TARGET = "DUPLICATE_TARGET";
    /** 需要有效风控审核。 */
    public static final String REASON_REVIEW_REQUIRED = "REVIEW_REQUIRED";
    /** 审核后需要经理确认。 */
    public static final String REASON_NOT_CONFIRMED = "NOT_CONFIRMED";
    /** 总量、单笔或实际目标剩余额度不足。 */
    public static final String REASON_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    /** 任务已暂停。 */
    public static final String REASON_HALTED = "HALTED";
    /** 风控约束不符合经理目标。 */
    public static final String REASON_INVALID_LIMITS = "INVALID_LIMITS";
    /** 目标或审核版本已失效。 */
    public static final String REASON_STALE_VERSION = "STALE_VERSION";
    /** 已到任务或审核截止。 */
    public static final String REASON_EXPIRED = "EXPIRED";
    /** 未知及无权范围的统一结果。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RoleTradingModel() {
    }

    /** 目标工作流的当前阶段。 */
    public enum Status {
        /** 等待审核或经理确认。 */
        PENDING_REVIEW,
        /** 可以在有效约束内执行。 */
        ACTIVE,
        /** 停止新单，等待旧单撤净后建立新基点。 */
        UPDATING,
        /** 风险暂停，旧单须由运行桥实际撤销。 */
        HALTED,
        /** 已取消，保留历史。 */
        CANCELLED,
        /** 已到截止，保留历史。 */
        EXPIRED,
        /** 建立或更新时实际持仓已达到目标。 */
        COMPLETED
    }

    /** 已读资料的固定版本引用。 */
    public record Citation(String docId, long version) {
        /** 校验资料及版本。 */
        public Citation {
            text(docId);
            positive(version);
        }
    }

    /** 研究元数据，正文只保存在引用的组织文档中。 */
    public record Research(String researchId, String organizationPartyId, String productOwnerId,
                           String symbol, String authorPersonPartyId, String authorAppointmentId,
                           String docId, long docVersion, List<Citation> citations,
                           long createdAtMillis) {
        /** 冻结作者、范围与证据。 */
        public Research {
            texts(researchId, organizationPartyId, productOwnerId, symbol,
                    authorPersonPartyId, authorAppointmentId, docId);
            positive(docVersion);
            nonNegative(createdAtMillis);
            citations = List.copyOf(citations);
            if (citations.isEmpty()) {
                throw new IllegalArgumentException("研究必须保留证据");
            }
        }
    }

    /** 一次执行版本，baseQuantity是建立该版本时的真实持仓。 */
    public record TargetVersion(int version, long absoluteTargetQuantity, BigDecimal priceLimit,
                                long deadlineMillis, String sourceResearchId, long baseQuantity,
                                long createdAtMillis) {
        /** 校验数量、价格及右开截止。 */
        public TargetVersion {
            positive(version);
            nonNegative(absoluteTargetQuantity);
            nonNegative(baseQuantity);
            nonNegative(createdAtMillis);
            text(sourceResearchId);
            priceLimit = price(priceLimit);
            if (deadlineMillis <= createdAtMillis) {
                throw new IllegalArgumentException("目标截止必须晚于版本建立时刻");
            }
        }

        /** @return 该版本的执行方向，已经达到目标时为空 */
        public Side side() {
            return absoluteTargetQuantity == baseQuantity ? null
                    : absoluteTargetQuantity > baseQuantity ? Side.BUY : Side.SELL;
        }

        /** @return 相对实际基点的最大变动量 */
        public long changeQuantity() {
            return absoluteTargetQuantity >= baseQuantity
                    ? absoluteTargetQuantity - baseQuantity : baseQuantity - absoluteTargetQuantity;
        }
    }

    /** 等待撤净旧单的目标修改请求。 */
    public record PendingUpdate(int version, long absoluteTargetQuantity, BigDecimal priceLimit,
                                long deadlineMillis, String sourceResearchId,
                                long requestedAtMillis) {
        /** 保留原始修改内容。 */
        public PendingUpdate {
            positive(version);
            nonNegative(absoluteTargetQuantity);
            nonNegative(requestedAtMillis);
            text(sourceResearchId);
            priceLimit = price(priceLimit);
            if (deadlineMillis <= requestedAtMillis) {
                throw new IllegalArgumentException("修改目标的截止必须在未来");
            }
        }
    }

    /** 一个账户标的的目标历史及当前阶段。 */
    public record Target(String targetId, String organizationPartyId, String productOwnerId,
                         String accountId, String symbol, List<TargetVersion> versions,
                         PendingUpdate pending, Status status, long haltedUntilMillis) {
        /** 冻结连续版本，保留停止后的修改请求。 */
        public Target {
            texts(targetId, organizationPartyId, productOwnerId, accountId, symbol);
            versions = List.copyOf(versions);
            Objects.requireNonNull(status, "status");
            nonNegative(haltedUntilMillis);
            if (versions.isEmpty()) {
                throw new IllegalArgumentException("目标必须有首版本");
            }
            long previousTime = -1;
            for (int index = 0; index < versions.size(); index++) {
                TargetVersion version = versions.get(index);
                if (version.version() != index + 1 || version.createdAtMillis() < previousTime) {
                    throw new IllegalArgumentException("目标版本必须连续且时间不倒退");
                }
                previousTime = version.createdAtMillis();
            }
            TargetVersion current = versions.get(versions.size() - 1);
            if ((status == Status.HALTED && (haltedUntilMillis <= current.createdAtMillis()
                    || haltedUntilMillis > current.deadlineMillis()))
                    || (status != Status.HALTED && haltedUntilMillis != 0)) {
                throw new IllegalArgumentException("目标阶段与暂停期限不一致");
            }
            if (status == Status.UPDATING && pending == null) {
                throw new IllegalArgumentException("更新阶段缺少原请求");
            }
            if (pending != null && (pending.version() != versions.size() + 1
                    || (status != Status.UPDATING && status != Status.CANCELLED
                    && status != Status.EXPIRED))) {
                throw new IllegalArgumentException("待更新版本与阶段不一致");
            }
        }

        /** @return 当前已经建立的执行版本 */
        public TargetVersion current() {
            return versions.get(versions.size() - 1);
        }
    }

    /** 固定目标版本的一次风控审核。 */
    public record RiskReview(String targetId, int targetVersion, int revision, boolean approved,
                             long maxTotalQuantity, long maxChildQuantity, BigDecimal priceLimit,
                             long validUntilMillis, String reason, String officerPersonPartyId,
                             String officerAppointmentId, long reviewedAtMillis) {
        /** 校验审核字段。 */
        public RiskReview {
            texts(targetId, reason, officerPersonPartyId, officerAppointmentId);
            positive(targetVersion);
            positive(revision);
            nonNegative(maxTotalQuantity);
            nonNegative(maxChildQuantity);
            nonNegative(reviewedAtMillis);
            priceLimit = price(priceLimit);
            if (validUntilMillis <= reviewedAtMillis
                    || (approved && (maxChildQuantity == 0 || maxChildQuantity > maxTotalQuantity))) {
                throw new IllegalArgumentException("审核额度或有效期非法");
            }
        }
    }

    /** 有明确期限和责任人的风险暂停记录。 */
    public record Halt(String haltId, String targetId, int targetVersion, long untilMillis,
                       String reason, String officerPersonPartyId, String officerAppointmentId,
                       long issuedAtMillis) {
        /** 校验暂停记录。 */
        public Halt {
            texts(haltId, targetId, reason, officerPersonPartyId, officerAppointmentId);
            positive(targetVersion);
            nonNegative(issuedAtMillis);
            if (untilMillis <= issuedAtMillis) {
                throw new IllegalArgumentException("暂停截止必须在未来");
            }
        }
    }

    /** 已受理真实订单与人物、目标、审核版本之间的链接。 */
    public record ChildLink(long orderId, String targetId, int targetVersion, int riskRevision,
                            String personPartyId, String appointmentId, String childId,
                            String taskId) {
        /** 校验链接标识。 */
        public ChildLink {
            positive(orderId);
            positive(targetVersion);
            positive(riskRevision);
            texts(targetId, personPartyId, appointmentId, childId, taskId);
        }
    }

    /** 唯一工作流状态，所有列表及其记录不可变。 */
    public record State(List<Research> research, List<Target> targets, List<RiskReview> reviews,
                        List<Halt> halts, List<ChildLink> children) {
        /** 冻结列表并核对元数据引用。 */
        public State {
            research = List.copyOf(research);
            targets = List.copyOf(targets);
            reviews = List.copyOf(reviews);
            halts = List.copyOf(halts);
            children = List.copyOf(children);
            Set<String> researchIds = new HashSet<>();
            Set<String> documentIds = new HashSet<>();
            for (Research note : research) {
                unique(researchIds, note.researchId());
                unique(documentIds, note.docId());
            }
            Set<String> targetIds = new HashSet<>();
            Set<List<String>> currentScopes = new HashSet<>();
            for (Target target : targets) {
                unique(targetIds, target.targetId());
                if (!terminal(target.status())
                        && !currentScopes.add(List.of(target.accountId(), target.symbol()))) {
                    throw new IllegalArgumentException("同一账户标的存在多个当前目标");
                }
                for (TargetVersion version : target.versions()) {
                    Research note = findResearch(research, version.sourceResearchId());
                    if (note == null || !note.organizationPartyId().equals(target.organizationPartyId())
                            || !note.productOwnerId().equals(target.productOwnerId())
                            || !note.symbol().equals(target.symbol())) {
                        throw new IllegalArgumentException("目标研究来源与范围不一致");
                    }
                }
            }
            Set<String> reviewKeys = new HashSet<>();
            Map<String, Integer> revisions = new HashMap<>();
            for (RiskReview review : reviews) {
                Target parent = findTarget(targets, review.targetId());
                if (parent == null || review.targetVersion() > parent.versions().size()) {
                    throw new IllegalArgumentException("审核引用了未知目标版本");
                }
                String revisionKey = review.targetId() + ":" + review.targetVersion();
                int expectedRevision = Math.addExact(revisions.getOrDefault(revisionKey, 0), 1);
                if (review.revision() != expectedRevision) {
                    throw new IllegalArgumentException("审核版本必须从1连续递增");
                }
                revisions.put(revisionKey, expectedRevision);
                TargetVersion version = parent.versions().get(review.targetVersion() - 1);
                if (review.reviewedAtMillis() < version.createdAtMillis()
                        || review.validUntilMillis() > version.deadlineMillis()
                        || (review.approved() && (review.maxTotalQuantity() > version.changeQuantity()
                        || !withinPrice(version.side(), review.priceLimit(), version.priceLimit())))) {
                    throw new IllegalArgumentException("审核超出目标版本的数量、价格或期限");
                }
                unique(reviewKeys, review.targetId() + ":" + review.targetVersion() + ":" + review.revision());
            }
            Set<String> haltIds = new HashSet<>();
            for (Halt halt : halts) {
                unique(haltIds, halt.haltId());
                Target parent = findTarget(targets, halt.targetId());
                if (parent == null || halt.targetVersion() > parent.versions().size()) {
                    throw new IllegalArgumentException("暂停引用了未知目标版本");
                }
            }
            Set<Long> orderIds = new HashSet<>();
            Set<List<String>> childIds = new HashSet<>();
            for (ChildLink child : children) {
                Target parent = findTarget(targets, child.targetId());
                if (!orderIds.add(child.orderId())
                        || !childIds.add(List.of(child.personPartyId(), child.childId()))
                        || parent == null || child.targetVersion() > parent.versions().size()
                        || reviews.stream().noneMatch(review -> review.targetId().equals(child.targetId())
                        && review.targetVersion() == child.targetVersion()
                        && review.revision() == child.riskRevision() && review.approved())) {
                    throw new IllegalArgumentException("子单链接重复或缺少有效目标审核");
                }
            }
        }
    }

    /** 研究发表意图，其人物字段由可信运行桥绑定。 */
    public record ResearchRequest(String researchId, String organizationPartyId,
                                  String productOwnerId, String symbol, String authorPersonPartyId,
                                  String authorAppointmentId, String docId, String title, String body,
                                  List<Citation> citations) {
        /** 冻结业务请求。 */
        public ResearchRequest {
            texts(researchId, organizationPartyId, productOwnerId, symbol,
                    authorPersonPartyId, authorAppointmentId, docId, title, body);
            citations = List.copyOf(citations);
        }
    }

    /** 建立或修改绝对目标的经理意图。 */
    public record TargetRequest(String targetId, int expectedVersion, String organizationPartyId,
                                String productOwnerId, String accountId, String symbol,
                                long absoluteTargetQuantity, BigDecimal priceLimit,
                                long deadlineMillis, String sourceResearchId,
                                String managerPersonPartyId, String managerAppointmentId) {
        /** 校验目标请求静态字段。 */
        public TargetRequest {
            texts(targetId, organizationPartyId, productOwnerId, accountId, symbol,
                    sourceResearchId, managerPersonPartyId, managerAppointmentId);
            nonNegative(expectedVersion);
            nonNegative(absoluteTargetQuantity);
            nonNegative(deadlineMillis);
            priceLimit = price(priceLimit);
        }
    }

    /** 风控在指定目标版本上给出的审核。 */
    public record ReviewRequest(String targetId, int expectedVersion, boolean approved,
                                long maxTotalQuantity, long maxChildQuantity, BigDecimal priceLimit,
                                long validUntilMillis, String reason, String officerPersonPartyId,
                                String officerAppointmentId) {
        /** 校验输入，动态额度关系在审核时裁决。 */
        public ReviewRequest {
            texts(targetId, reason, officerPersonPartyId, officerAppointmentId);
            positive(expectedVersion);
            nonNegative(maxTotalQuantity);
            nonNegative(maxChildQuantity);
            nonNegative(validUntilMillis);
            priceLimit = price(priceLimit);
        }
    }

    /** 子单意图，账户、标的及方向从目标继承。 */
    public record ChildRequest(String targetId, int parentVersion, int riskRevision,
                               String childId, String personPartyId, String appointmentId,
                               long quantity, BigDecimal limitPrice, String taskId) {
        /** 校验输入。 */
        public ChildRequest {
            texts(targetId, childId, personPartyId, appointmentId, taskId);
            positive(parentVersion);
            positive(riskRevision);
            positive(quantity);
            limitPrice = price(limitPrice);
        }
    }

    /** 工作流业务裁决及不可变候选。 */
    public record Result(boolean accepted, String reason, State state) {
    }

    /** 研究元数据和资料候选由运行桥同批确认。 */
    public record ResearchResult(boolean accepted, String reason, State state,
                                 InformationModel.State information, String researchId) {
    }

    /** @return 没有研究、任务或资产副本的空工作流 */
    public static State open() {
        return new State(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 根据本人已读证据发表固定组织研究文档。 */
    public static ResearchResult submitResearch(State state, RoleAccess.State access,
            InformationModel.State information, OpeningRegistry registry,
            ResearchRequest request, long nowMillis) {
        nonNegative(nowMillis);
        String reason = "OK";
        if (!authorized(access, request.authorPersonPartyId(), request.authorAppointmentId(),
                RoleAccess.Action.WRITE_RESEARCH,
                RoleAccess.Target.organization(request.organizationPartyId()), nowMillis)
                || !hasProduct(access, request.organizationPartyId(), request.productOwnerId())
                || registry.security(request.symbol()) == null) {
            reason = REASON_NOT_AUTHORIZED;
        } else if (findResearch(state.research(), request.researchId()) != null
                || information.documents().stream().anyMatch(doc -> doc.docId().equals(request.docId()))) {
            reason = "DUPLICATE_RESEARCH";
        } else if (request.citations().isEmpty() || request.citations().stream().anyMatch(citation ->
                !InformationModel.canCite(information, request.authorPersonPartyId(),
                        citation.docId(), citation.version(), nowMillis))) {
            reason = REASON_EVIDENCE_NOT_READ;
        }
        if (!reason.equals("OK")) {
            return new ResearchResult(false, reason, state, information, request.researchId());
        }
        InformationModel.State published = InformationModel.publish(information, request.docId(),
                request.authorPersonPartyId(), InformationModel.Audience.organization(request.organizationPartyId()),
                new InformationModel.VersionDraft(request.title(), request.body(), "组织研究报告",
                        nowMillis, RESEARCH_READ_MILLIS, List.of()));
        List<Research> notes = new ArrayList<>(state.research());
        notes.add(new Research(request.researchId(), request.organizationPartyId(), request.productOwnerId(),
                request.symbol(), request.authorPersonPartyId(), request.authorAppointmentId(),
                request.docId(), 1, request.citations(), nowMillis));
        return new ResearchResult(true, "OK", new State(notes, state.targets(), state.reviews(),
                state.halts(), state.children()), published, request.researchId());
    }

    /** 经理建立目标或进入等待撤单的更新阶段。 */
    public static Result setTarget(State state, RoleAccess.State access,
            InformationModel.State information, SecuritiesLedger.State securities,
            TargetRequest request, long nowMillis) {
        nonNegative(nowMillis);
        RoleAccess.Target scope = RoleAccess.Target.account(request.organizationPartyId(),
                request.productOwnerId(), request.accountId());
        if (!authorized(access, request.managerPersonPartyId(), request.managerAppointmentId(),
                RoleAccess.Action.SET_PORTFOLIO_TARGET,
                RoleAccess.Target.product(request.organizationPartyId(), request.productOwnerId()), nowMillis)
                || RoleAccess.authorize(access, request.managerPersonPartyId(),
                RoleAccess.Action.READ_PRODUCT_ACCOUNT, scope, nowMillis) != RoleAccess.Decision.ALLOW) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        Research note = findResearch(state.research(), request.sourceResearchId());
        if (note == null || !note.organizationPartyId().equals(request.organizationPartyId())
                || !note.productOwnerId().equals(request.productOwnerId())
                || !note.symbol().equals(request.symbol())) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        if (!InformationModel.canCite(information, request.managerPersonPartyId(), note.docId(),
                note.docVersion(), nowMillis)) {
            return reject(state, REASON_EVIDENCE_NOT_READ);
        }
        if (request.deadlineMillis() <= nowMillis) {
            return reject(state, REASON_EXPIRED);
        }
        if (state.targets().stream().anyMatch(existing -> !existing.targetId().equals(request.targetId())
                && !terminal(existing.status()) && existing.accountId().equals(request.accountId())
                && existing.symbol().equals(request.symbol()))) {
            return reject(state, REASON_DUPLICATE_TARGET);
        }
        Target previous = target(state, request.targetId());
        if (previous != null) {
            if (!sameScope(previous, request)) {
                return reject(state, REASON_NOT_AUTHORIZED);
            }
            if (request.expectedVersion() != previous.current().version()) {
                return reject(state, REASON_STALE_VERSION);
            }
            if (previous.status() == Status.UPDATING) {
                return reject(state, "UPDATE_PENDING");
            }
            PendingUpdate pending = new PendingUpdate(Math.addExact(previous.current().version(), 1),
                    request.absoluteTargetQuantity(), request.priceLimit(), request.deadlineMillis(),
                    request.sourceResearchId(), nowMillis);
            return accept(replace(state, new Target(previous.targetId(), previous.organizationPartyId(),
                    previous.productOwnerId(), previous.accountId(), previous.symbol(), previous.versions(),
                    pending, Status.UPDATING, 0)));
        }
        if (request.expectedVersion() != 0) {
            return reject(state, REASON_STALE_VERSION);
        }
        long base = held(securities, request.accountId(), request.symbol());
        TargetVersion version = new TargetVersion(1, request.absoluteTargetQuantity(), request.priceLimit(),
                request.deadlineMillis(), request.sourceResearchId(), base, nowMillis);
        Target created = new Target(request.targetId(), request.organizationPartyId(), request.productOwnerId(),
                request.accountId(), request.symbol(), List.of(version), null,
                version.changeQuantity() == 0 ? Status.COMPLETED : Status.PENDING_REVIEW, 0);
        List<Target> targets = new ArrayList<>(state.targets());
        targets.add(created);
        return accept(new State(state.research(), targets, state.reviews(), state.halts(), state.children()));
    }

    /** 风控给出显式数量、价格和期限限制，确认权仍由经理持有。 */
    public static Result reviewTarget(State state, RoleAccess.State access,
            SecuritiesLedger.State securities, ReviewRequest request, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, request.targetId());
        if (!canAct(access, parent, request.officerPersonPartyId(), request.officerAppointmentId(),
                RoleAccess.Action.ISSUE_RISK_HALT, nowMillis)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        TargetVersion version = parent.current();
        if (version.version() != request.expectedVersion()) {
            return reject(state, REASON_STALE_VERSION);
        }
        if (terminal(parent.status()) || parent.status() == Status.UPDATING) {
            return reject(state, "INVALID_STATE");
        }
        long current = held(securities, parent.accountId(), parent.symbol());
        long executed = version.side() == Side.BUY ? Math.max(0, current - version.baseQuantity())
                : Math.max(0, version.baseQuantity() - current);
        if (nowMillis < version.createdAtMillis() || request.validUntilMillis() <= nowMillis
                || request.validUntilMillis() > version.deadlineMillis()
                || (request.approved() && (request.maxTotalQuantity() > version.changeQuantity()
                || request.maxTotalQuantity() < executed || request.maxChildQuantity() == 0
                || request.maxChildQuantity() > request.maxTotalQuantity()
                || !withinPrice(version.side(), request.priceLimit(), version.priceLimit())))) {
            return reject(state, REASON_INVALID_LIMITS);
        }
        RiskReview old = currentReview(state, parent.targetId(), version.version());
        RiskReview review = new RiskReview(parent.targetId(), version.version(), old == null ? 1
                : Math.addExact(old.revision(), 1), request.approved(), request.maxTotalQuantity(),
                request.maxChildQuantity(), request.priceLimit(), request.validUntilMillis(), request.reason(),
                request.officerPersonPartyId(), request.officerAppointmentId(), nowMillis);
        List<RiskReview> reviews = new ArrayList<>(state.reviews());
        reviews.add(review);
        State changed = replace(state, stage(parent, Status.PENDING_REVIEW, 0));
        return accept(new State(changed.research(), changed.targets(), reviews, changed.halts(), changed.children()));
    }

    /** 经理确认本版本最新的有效审核。 */
    public static Result confirmTarget(State state, RoleAccess.State access, String targetId,
            int expectedVersion, int riskRevision, String managerPersonPartyId,
            String managerAppointmentId, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, targetId);
        if (!canAct(access, parent, managerPersonPartyId, managerAppointmentId,
                RoleAccess.Action.SET_PORTFOLIO_TARGET, nowMillis)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        if (parent.current().version() != expectedVersion) {
            return reject(state, REASON_STALE_VERSION);
        }
        RiskReview review = currentReview(state, targetId, expectedVersion);
        if (review == null || !review.approved()) {
            return reject(state, REASON_REVIEW_REQUIRED);
        }
        if (review.revision() != riskRevision) {
            return reject(state, REASON_STALE_VERSION);
        }
        if (nowMillis >= review.validUntilMillis() || nowMillis >= parent.current().deadlineMillis()) {
            return reject(state, REASON_EXPIRED);
        }
        if (parent.status() != Status.PENDING_REVIEW || nowMillis < review.reviewedAtMillis()) {
            return reject(state, "INVALID_STATE");
        }
        return accept(replace(state, stage(parent, Status.ACTIVE, 0)));
    }

    /** 风控关闭新子单入口，运行桥随后确认真实撤单。 */
    public static Result haltTarget(State state, RoleAccess.State access, String haltId,
            String targetId, int expectedVersion, long untilMillis, String reason,
            String officerPersonPartyId, String officerAppointmentId, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, targetId);
        if (!canAct(access, parent, officerPersonPartyId, officerAppointmentId,
                RoleAccess.Action.ISSUE_RISK_HALT, nowMillis)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        if (parent.current().version() != expectedVersion) {
            return reject(state, REASON_STALE_VERSION);
        }
        if (terminal(parent.status()) || parent.status() == Status.UPDATING || untilMillis <= nowMillis
                || untilMillis > parent.current().deadlineMillis()
                || state.halts().stream().anyMatch(halt -> halt.haltId().equals(haltId))) {
            return reject(state, "INVALID_STATE");
        }
        List<Halt> halts = new ArrayList<>(state.halts());
        halts.add(new Halt(haltId, targetId, expectedVersion, untilMillis, reason,
                officerPersonPartyId, officerAppointmentId, nowMillis));
        State changed = replace(state, stage(parent, Status.HALTED, untilMillis));
        return accept(new State(changed.research(), changed.targets(), changed.reviews(), halts, changed.children()));
    }

    /** 暂停期限结束后回到待审核阶段。 */
    public static Result releaseHalt(State state, String targetId, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, targetId);
        if (parent == null || parent.status() != Status.HALTED || nowMillis < parent.haltedUntilMillis()) {
            return reject(state, "NOT_DUE");
        }
        return accept(replace(state, stage(parent, Status.PENDING_REVIEW, 0)));
    }

    /** 当前经理停止目标，运行桥负责实际撤单及保留成交。 */
    public static Result cancelTarget(State state, RoleAccess.State access, String targetId,
            int expectedVersion, String managerPersonPartyId, String managerAppointmentId, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, targetId);
        if (!canAct(access, parent, managerPersonPartyId, managerAppointmentId,
                RoleAccess.Action.SET_PORTFOLIO_TARGET, nowMillis)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        if (parent.current().version() != expectedVersion) {
            return reject(state, REASON_STALE_VERSION);
        }
        return accept(replace(state, stage(parent, Status.CANCELLED, 0)));
    }

    /** 处理一个目标的右开截止，保留尚未建立的新版本请求。 */
    public static Result expireTarget(State state, String targetId, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, targetId);
        if (parent == null) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        long deadline = parent.pending() == null ? parent.current().deadlineMillis()
                : parent.pending().deadlineMillis();
        if (nowMillis < deadline || terminal(parent.status())) {
            return reject(state, "NOT_DUE");
        }
        return accept(replace(state, stage(parent, Status.EXPIRED, 0)));
    }

    /** 旧子单已实际撤净后，用最新持仓建立新执行基点。 */
    public static Result settleUpdate(State state, WorldExchange.State exchange,
            SecuritiesLedger.State securities, String targetId, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, targetId);
        if (parent == null || parent.status() != Status.UPDATING) {
            return reject(state, "INVALID_STATE");
        }
        for (ChildLink child : state.children()) {
            if (child.targetId().equals(targetId) && order(exchange, child.orderId()).live()) {
                return reject(state, "CANCEL_PENDING");
            }
        }
        PendingUpdate update = parent.pending();
        if (nowMillis >= update.deadlineMillis()) {
            return expireTarget(state, targetId, nowMillis);
        }
        if (nowMillis < update.requestedAtMillis()) {
            return reject(state, "INVALID_TIME");
        }
        TargetVersion version = new TargetVersion(update.version(), update.absoluteTargetQuantity(),
                update.priceLimit(), update.deadlineMillis(), update.sourceResearchId(),
                held(securities, parent.accountId(), parent.symbol()), nowMillis);
        List<TargetVersion> versions = new ArrayList<>(parent.versions());
        versions.add(version);
        return accept(replace(state, new Target(parent.targetId(), parent.organizationPartyId(),
                parent.productOwnerId(), parent.accountId(), parent.symbol(), versions, null,
                version.changeQuantity() == 0 ? Status.COMPLETED : Status.PENDING_REVIEW, 0)));
    }

    /** 复核任职、目标、审核、价格及真实额度，受理后仍须同批提交交易并链接。 */
    public static Result admitChild(State state, RoleAccess.State access,
            WorldExchange.State exchange, SecuritiesLedger.State securities,
            ChildRequest request, long nowMillis) {
        nonNegative(nowMillis);
        Target parent = target(state, request.targetId());
        if (parent == null || !authorized(access, request.personPartyId(), request.appointmentId(),
                RoleAccess.Action.EXECUTE_ORDER, RoleAccess.Target.account(parent.organizationPartyId(),
                        parent.productOwnerId(), parent.accountId()), nowMillis)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        TargetVersion version = parent.current();
        if (version.version() != request.parentVersion()) {
            return reject(state, REASON_STALE_VERSION);
        }
        if (nowMillis < version.createdAtMillis()) {
            return reject(state, "INVALID_TIME");
        }
        if (nowMillis >= version.deadlineMillis()) {
            return reject(state, REASON_EXPIRED);
        }
        if (parent.status() == Status.HALTED) {
            return reject(state, REASON_HALTED);
        }
        RiskReview review = currentReview(state, parent.targetId(), version.version());
        if (review == null || !review.approved()) {
            return reject(state, REASON_REVIEW_REQUIRED);
        }
        if (review.revision() != request.riskRevision()) {
            return reject(state, REASON_STALE_VERSION);
        }
        if (nowMillis < review.reviewedAtMillis()) {
            return reject(state, "INVALID_TIME");
        }
        if (parent.status() != Status.ACTIVE) {
            return reject(state, REASON_NOT_CONFIRMED);
        }
        if (nowMillis >= review.validUntilMillis()) {
            return reject(state, REASON_EXPIRED);
        }
        if (!withinPrice(version.side(), request.limitPrice(), version.priceLimit())
                || !withinPrice(version.side(), request.limitPrice(), review.priceLimit())) {
            return reject(state, "PRICE_LIMIT");
        }
        long used = used(state, exchange, parent.targetId(), version.side());
        if (used > review.maxTotalQuantity() || request.quantity() > review.maxChildQuantity()
                || request.quantity() > review.maxTotalQuantity() - used
                || request.quantity() > positionCapacity(parent, version.side(), exchange, securities)) {
            return reject(state, REASON_QUOTA_EXCEEDED);
        }
        if (state.children().stream().anyMatch(child -> child.personPartyId().equals(request.personPartyId())
                && child.childId().equals(request.childId()))) {
            return reject(state, "DUPLICATE_CHILD");
        }
        return accept(state);
    }

    /** 核对真实订单、任职及原始任务后加入链接，拒单不产生链接。 */
    public static Result linkOrder(State state, RoleAccess.State access, WorldExchange.State exchange,
                                    ChildRequest request, long orderId) {
        Target parent = target(state, request.targetId());
        if (parent == null || parent.current().version() != request.parentVersion()) {
            return reject(state, REASON_STALE_VERSION);
        }
        WorldExchange.Order actual = exchange.orders().stream()
                .filter(order -> order.orderId() == orderId).findFirst().orElse(null);
        if (actual == null) {
            return reject(state, "INVALID_ORDER_LINK");
        }
        long acceptedAt = actual.acceptedAtMillis();
        if (!authorized(access, request.personPartyId(), request.appointmentId(),
                RoleAccess.Action.EXECUTE_ORDER, RoleAccess.Target.account(parent.organizationPartyId(),
                        parent.productOwnerId(), parent.accountId()), acceptedAt)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        TargetVersion version = parent.current();
        RiskReview review = currentReview(state, parent.targetId(), version.version());
        if (parent.status() != Status.ACTIVE || review == null || !review.approved()
                || review.revision() != request.riskRevision()) {
            return reject(state, REASON_STALE_VERSION);
        }
        if (!actual.accountId().equals(parent.accountId()) || !actual.symbol().equals(parent.symbol())
                || actual.side() != version.side() || actual.type() != edu.cufe.auction.model.OrderType.LIMIT
                || actual.quantity() != request.quantity() || !actual.limitPrice().equals(request.limitPrice())
                || !actual.taskId().equals(request.taskId())
                || !withinPrice(version.side(), actual.limitPrice(), version.priceLimit())
                || !withinPrice(version.side(), actual.limitPrice(), review.priceLimit())
                || acceptedAt < version.createdAtMillis() || acceptedAt < review.reviewedAtMillis()
                || acceptedAt >= version.deadlineMillis() || acceptedAt >= review.validUntilMillis()) {
            return reject(state, "INVALID_ORDER_LINK");
        }
        if (state.children().stream().anyMatch(child -> child.orderId() == orderId
                || (child.personPartyId().equals(request.personPartyId()) && child.childId().equals(request.childId())))) {
            return reject(state, "DUPLICATE_CHILD");
        }
        long occupied = used(state, exchange, parent.targetId(), version.side());
        long contribution = actual.live() ? actual.quantity() : actual.filled();
        if (request.quantity() > review.maxChildQuantity() || occupied > review.maxTotalQuantity()
                || contribution > review.maxTotalQuantity() - occupied) {
            return reject(state, REASON_QUOTA_EXCEEDED);
        }
        List<ChildLink> children = new ArrayList<>(state.children());
        children.add(new ChildLink(orderId, request.targetId(), request.parentVersion(), request.riskRevision(),
                request.personPartyId(), request.appointmentId(), request.childId(), request.taskId()));
        return accept(new State(state.research(), state.targets(), state.reviews(), state.halts(), children));
    }

    /** @return 当前执行版本的真实成交量与仍活动剩余量之和 */
    public static long used(State state, WorldExchange.State exchange, String targetId, Side side) {
        Target parent = target(state, targetId);
        if (parent == null) {
            throw new IllegalArgumentException("未知目标");
        }
        long total = 0;
        for (ChildLink child : state.children()) {
            if (child.targetId().equals(targetId) && child.targetVersion() == parent.current().version()) {
                WorldExchange.Order order = order(exchange, child.orderId());
                if (order.side() == side) {
                    total = Math.addExact(total, order.filled());
                    if (order.live()) {
                        total = Math.addExact(total, order.remaining());
                    }
                }
            }
        }
        return total;
    }

    /** @return 唯一证券账中该账户标的的全部剩余持仓，包含冻结及当日买入 */
    public static long held(SecuritiesLedger.State securities, String accountId, String symbol) {
        long total = 0;
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            if (lot.accountId().equals(accountId) && lot.symbol().equals(symbol)) {
                total = Math.addExact(total, lot.remaining());
            }
        }
        return total;
    }

    /** @return 指定目标，未登记时为空，供可信桥先授权再投影 */
    public static Target target(State state, String targetId) {
        return findTarget(state.targets(), targetId);
    }

    /** @return 目标版本最新一次审核，没有审核时为空 */
    public static RiskReview currentReview(State state, String targetId, int version) {
        for (int index = state.reviews().size() - 1; index >= 0; index--) {
            RiskReview review = state.reviews().get(index);
            if (review.targetId().equals(targetId) && review.targetVersion() == version) {
                return review;
            }
        }
        return null;
    }

    /** @return 保留版本与引用的稳定JSON */
    public static String toJson(State state) {
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("无法序列化交易工作流", failure);
        }
    }

    /** 从JSON还原并执行记录构造校验。 */
    public static State fromJson(String json) {
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("无效交易工作流JSON", failure);
        }
    }

    private static long positionCapacity(Target parent, Side side, WorldExchange.State exchange,
                                         SecuritiesLedger.State securities) {
        long holding = held(securities, parent.accountId(), parent.symbol());
        long target = parent.current().absoluteTargetQuantity();
        long gap = side == Side.BUY ? Math.max(0, target - holding) : Math.max(0, holding - target);
        for (WorldExchange.Order order : exchange.orders()) {
            if (order.live() && order.accountId().equals(parent.accountId())
                    && order.symbol().equals(parent.symbol()) && order.side() == side) {
                if (order.remaining() >= gap) {
                    return 0;
                }
                gap -= order.remaining();
            }
        }
        return gap;
    }

    private static boolean authorized(RoleAccess.State access, String person, String appointmentId,
                                      RoleAccess.Action action, RoleAccess.Target scope, long now) {
        boolean originalAppointment = access.appointments().stream().anyMatch(appointment ->
                appointment.id().equals(appointmentId)
                        && appointment.personPartyId().equals(person)
                        && appointment.organizationPartyId().equals(scope.organizationPartyId())
                        && appointment.activeAt(now));
        return originalAppointment
                && RoleAccess.authorize(access, person, action, scope, now) == RoleAccess.Decision.ALLOW;
    }

    private static boolean canAct(RoleAccess.State access, Target parent, String person,
                                   String appointmentId, RoleAccess.Action action, long now) {
        return parent != null && authorized(access, person, appointmentId, action,
                RoleAccess.Target.product(parent.organizationPartyId(), parent.productOwnerId()), now);
    }

    private static boolean hasProduct(RoleAccess.State access, String organization, String product) {
        return access.mandates().stream().anyMatch(mandate -> mandate.organizationPartyId().equals(organization)
                && mandate.productOwnerId().equals(product));
    }

    private static boolean withinPrice(Side side, BigDecimal offered, BigDecimal boundary) {
        return side == Side.BUY ? offered.compareTo(boundary) <= 0 : offered.compareTo(boundary) >= 0;
    }

    private static boolean sameScope(Target target, TargetRequest request) {
        return target.organizationPartyId().equals(request.organizationPartyId())
                && target.productOwnerId().equals(request.productOwnerId())
                && target.accountId().equals(request.accountId()) && target.symbol().equals(request.symbol());
    }

    private static boolean terminal(Status status) {
        return status == Status.CANCELLED || status == Status.EXPIRED || status == Status.COMPLETED;
    }

    private static Target stage(Target parent, Status status, long until) {
        return new Target(parent.targetId(), parent.organizationPartyId(), parent.productOwnerId(),
                parent.accountId(), parent.symbol(), parent.versions(), parent.pending(), status, until);
    }

    private static State replace(State state, Target replacement) {
        List<Target> targets = new ArrayList<>(state.targets());
        for (int index = 0; index < targets.size(); index++) {
            if (targets.get(index).targetId().equals(replacement.targetId())) {
                targets.set(index, replacement);
                return new State(state.research(), targets, state.reviews(), state.halts(), state.children());
            }
        }
        throw new IllegalArgumentException("替换了未登记目标");
    }

    private static Research findResearch(List<Research> research, String id) {
        return research.stream().filter(note -> note.researchId().equals(id)).findFirst().orElse(null);
    }

    private static Target findTarget(List<Target> targets, String id) {
        return targets.stream().filter(target -> target.targetId().equals(id)).findFirst().orElse(null);
    }

    private static WorldExchange.Order order(WorldExchange.State exchange, long id) {
        return exchange.orders().stream().filter(order -> order.orderId() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("子单引用了未登记订单"));
    }

    private static Result accept(State state) {
        return new Result(true, "OK", state);
    }

    private static Result reject(State state, String reason) {
        return new Result(false, reason, state);
    }

    private static BigDecimal price(BigDecimal value) {
        Objects.requireNonNull(value, "price");
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidPrecision) {
            throw new IllegalArgumentException("价格必须精确到分", invalidPrecision);
        }
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("价格必须为正");
        }
        return normalized;
    }

    private static void texts(String... values) {
        for (String value : values) {
            text(value);
        }
    }

    private static void text(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("业务标识或内容不能为空");
        }
    }

    private static void positive(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("数量或版本必须为正");
        }
    }

    private static void nonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("数量、版本或时间不能为负");
        }
    }

    private static void unique(Set<String> seen, String value) {
        if (!seen.add(value)) {
            throw new IllegalArgumentException("业务标识重复");
        }
    }
}
