package edu.cufe.auction.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 周期治理的 World 侧受信绑定：把 {@link PeriodicModel.Access} 与 {@link BoardAccess}
 * 落到唯一世界状态上（同一账户/董事会/资料/角色），不复制任何状态。
 *
 * <p><b>为什么单独一层</b>：纯核只定义回调契约，不证明身份；这里才回答“此刻这个人是不是
 * 本公司章程席位、是不是现任经理、是否实读过确切版本且仍能回忆”。所有查询都读给定帧的
 * 真实董事会章程与资料状态，不看 RoleAccess 的组织任职来冒充董事，也不发明席位有效期。</p>
 *
 * <p><b>三个 Root 纠正</b>：</p>
 * <ol>
 *   <li>实读只认终结原因为 {@link InformationModel.CloseReason#COMPLETED} 的阅读；
 *       取消与到期被拒都不算读过。</li>
 *   <li>回忆资格在<b>调用方给出的判定时刻</b>上，用真实冻结阈值与该帧真实已完成经历计算，
 *       不读墙钟/当前状态，也不把回忆输入预算截断当成遗忘。</li>
 *   <li>回避只按本次具体会议（或本批被审的关联事项）判定；不再因同公司另一场并行会议的
 *       回避记录扩大拒绝。</li>
 * </ol>
 *
 * <p><b>边界</b>：本类只读；不写日志、不改状态、不缓存。</p>
 */
public final class PeriodicWorldAccess implements PeriodicModel.Access, BoardAccess {

    private final Map<String, BoardModel.Charter> charters;
    private final Map<String, List<BoardModel.Meeting>> meetings;
    private final InformationModel.State information;
    private final CognitionRecall.Policy recallPolicy;
    private final RoleAccess.State roles;
    private final String meetingFilter;

    /**
     * 绑定到一个已打开世界：每次查询用给定 {@code at} 过滤，读取当前已确认帧。
     *
     * @param world 唯一世界运行时
     */
    public PeriodicWorldAccess(WorldRuntime world) {
        Objects.requireNonNull(world, "世界");
        WorldRuntime.PeriodicFrame frame = world.periodicFrame();
        this.charters = frame.charters();
        this.meetings = frame.meetings();
        this.information = frame.information();
        this.recallPolicy = frame.recallPolicy();
        this.roles = frame.roles();
        this.meetingFilter = null;
    }

    private PeriodicWorldAccess(Map<String, BoardModel.Charter> charters,
                                Map<String, List<BoardModel.Meeting>> meetings,
                                InformationModel.State information,
                                CognitionRecall.Policy recallPolicy, RoleAccess.State roles,
                                String meetingFilter) {
        this.charters = charters == null ? Map.of() : Map.copyOf(charters);
        this.meetings = meetings == null ? Map.of() : Map.copyOf(meetings);
        this.information = information;
        this.recallPolicy = recallPolicy;
        this.roles = roles;
        this.meetingFilter = meetingFilter;
    }

    /**
     * 按一帧真实状态构造绑定（正常执行与逐帧重演共用）。
     *
     * @param charters      公司→冻结章程
     * @param meetings      公司→董事会全部会议
     * @param information   该帧资料状态；未启用传 null
     * @param recallPolicy  冻结回忆参数；未启用传 null
     * @param roles         该帧任职状态；未启用传 null
     * @param meetingFilter 本次动作对应会议标识；null 表示不限定
     * @return 受信绑定
     */
    public static PeriodicWorldAccess onFrame(Map<String, BoardModel.Charter> charters,
                                              Map<String, List<BoardModel.Meeting>> meetings,
                                              InformationModel.State information,
                                              CognitionRecall.Policy recallPolicy,
                                              RoleAccess.State roles, String meetingFilter) {
        return new PeriodicWorldAccess(charters, meetings, information, recallPolicy, roles,
                meetingFilter);
    }

    /**
     * 单公司便捷构造：只绑定一家公司的章程与会议。
     *
     * @param companyPartyId 公司
     * @param charter        冻结章程
     * @param meetings       该公司会议
     * @param information    该帧资料状态
     * @param recallPolicy   冻结回忆参数
     * @param roles          该帧任职状态
     * @param meetingFilter  本次动作对应会议标识
     * @return 受信绑定
     */
    public static PeriodicWorldAccess onFrame(String companyPartyId, BoardModel.Charter charter,
                                              List<BoardModel.Meeting> meetings,
                                              InformationModel.State information,
                                              CognitionRecall.Policy recallPolicy,
                                              RoleAccess.State roles, String meetingFilter) {
        Map<String, BoardModel.Charter> charterMap = new LinkedHashMap<>();
        Map<String, List<BoardModel.Meeting>> meetingMap = new LinkedHashMap<>();
        if (charter != null && companyPartyId != null) {
            charterMap.put(companyPartyId, charter);
            meetingMap.put(companyPartyId, meetings == null ? List.of() : List.copyOf(meetings));
        }
        return new PeriodicWorldAccess(charterMap, meetingMap, information, recallPolicy, roles,
                meetingFilter);
    }

    @Override
    public String seatOf(String companyPartyId, String personPartyId, long at) {
        BoardModel.Charter charter = charters.get(companyPartyId);
        if (charter == null || personPartyId == null) {
            return null;
        }
        // 章程席位是开局冻结的事实；这里只回答“当时绑定是谁”，不引入有效期字段。
        return charter.seatOf(personPartyId);
    }

    @Override
    public boolean hasRead(String personPartyId, String docId, long version, long at) {
        if (information == null || personPartyId == null || docId == null) {
            return false;
        }
        for (InformationModel.ClosedReading closed : information.closed()) {
            if (closed.personPartyId().equals(personPartyId)
                    && closed.docId().equals(docId)
                    && closed.version() == version
                    && closed.reason() == InformationModel.CloseReason.COMPLETED
                    && closed.closedAt() <= at) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean recallEligible(String personPartyId, String docId, long version, long at) {
        if (information == null) {
            return false;
        }
        if (recallPolicy == null) {
            // 没有冻结回忆机制时只承认真实完成经历；不虚构遗忘，也不声称机制外的能力。
            return hasRead(personPartyId, docId, version, at);
        }
        return CognitionRecall.eligible(information, personPartyId, docId, version, at,
                recallPolicy);
    }

    @Override
    public boolean isManager(String companyPartyId, String personPartyId, long at) {
        if (roles == null || personPartyId == null) {
            return false;
        }
        for (RoleAccess.Appointment appointment : roles.appointments()) {
            if (appointment.personPartyId().equals(personPartyId)
                    && appointment.organizationPartyId().equals(companyPartyId)
                    && appointment.role() == RoleAccess.Role.COMPANY_MANAGER
                    && appointment.activeAt(at)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRecused(String companyPartyId, String seatId, long at) {
        if (seatId == null) {
            return false;
        }
        List<BoardModel.Meeting> companyMeetings = meetings.get(companyPartyId);
        if (companyMeetings == null) {
            return false;
        }
        if (meetingFilter != null) {
            for (BoardModel.Meeting meeting : companyMeetings) {
                if (meeting.meetingId().equals(meetingFilter)) {
                    // 同一会议/关联事项的回避与该次会议记录一致，不随窗口结束失效。
                    return meeting.recusedSeatIds().contains(seatId);
                }
            }
            return false;
        }
        for (BoardModel.Meeting meeting : companyMeetings) {
            if (meeting.opensAt() <= at && at < meeting.closesAt()
                    && meeting.recusedSeatIds().contains(seatId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canRead(String companyPartyId, String personPartyId, long at) {
        return seatOf(companyPartyId, personPartyId, at) != null;
    }
}
