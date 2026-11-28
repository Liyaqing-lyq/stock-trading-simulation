package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 十公司实验的现场采访接待合同。接待人承担一次不可退的往返车票。 */
public final class OnsiteVisit {
    public static final String TOOL = "interview.siteVisit";
    static final long ACTION = 1_000, LEG = 10_000;
    static final BigDecimal FARE = new BigDecimal("12.00");
    static final String HOST = "investor", WALLET = "INV", CARRIER = "VISIT-CARRIER";
    private OnsiteVisit() {}

    /** 一位参与者对本人预约采取的行动。 */
    public record Draft(String requestId, String action) {
        public Draft {
            if (requestId == null || requestId.isBlank() || action==null || !List.of("HOST_CONSENT","REVOKE","DEPART","RETURN").contains(action))
                throw new IllegalArgumentException("请选择本人预约的现场接待行动");
        }
    }
    /** 已确认的现场接待记录。负一表示相应事件尚未发生。 */
    public record Trip(String requestId, String stage, long departed, long observedAt, long nextAt, long returnedAt) {
        Trip change(String next, long due) { return new Trip(requestId,next,departed,observedAt,due,returnedAt); }
        boolean traveling() { return "OUTBOUND".equals(stage) || "ONSITE".equals(stage) || "RETURNING".equals(stage); }
    }
    /** 本人可见的现场预约和仍可办理的动作。 */
    public record Row(String requestId,String reporter,String host,String organization,String stage,long startMillis,
                      long endMillis,long departMillis,long returnMillis,String fare,String farePaid,boolean observed,
                      List<String> actions,long observedAtMillis) {}
    /** 本人的接待行程和出行占用。 */
    public record View(boolean enabled,boolean traveling,List<Row> appointments) {}

    static List<String> actions(InterviewModel.Invite invite, Trip trip, String person, boolean journalist, long finish) {
        boolean host = HOST.equals(person) && HOST.equals(invite.toPartyId());
        boolean reporter = invite.fromPartyId().equals(person);
        boolean accepted = invite.status() == InterviewModel.Status.ACCEPTED;
        var actions = new ArrayList<String>();
        if (host && trip == null && accepted && finish < invite.startMillis()) actions.add("HOST_CONSENT");
        if (host && trip != null && List.of("CONSENTED","BOOKED","OUTBOUND","ONSITE").contains(trip.stage())) actions.add("REVOKE");
        if (reporter && journalist && accepted && trip != null && "CONSENTED".equals(trip.stage())
                && finish < invite.startMillis()
                && finish + LEG < invite.scheduledEnd()) actions.add("DEPART");
        if (reporter && trip != null && List.of("BOOKED","OUTBOUND","ONSITE").contains(trip.stage())) actions.add("RETURN");
        return List.copyOf(actions);
    }
    static Trip apply(InterviewModel.Invite invite, Trip old, String action, long now) {
        return switch(action) {
            case "HOST_CONSENT" -> new Trip(invite.requestId(),"CONSENTED",-1,-1,-1,-1);
            case "DEPART" -> now < invite.startMillis()-LEG
                    ? old.change("BOOKED",invite.startMillis()-LEG)
                    : new Trip(invite.requestId(),"OUTBOUND",now,-1,now+LEG,-1);
            case "REVOKE","RETURN" -> old.departed()<0 ? old.change("REVOKED",-1) : old.change("RETURNING",now+LEG);
            default -> throw new IllegalArgumentException("未知行程动作");
        };
    }
    static Trip atGate(Trip old, InterviewModel.Invite invite, boolean eligible, long now) {
        if (old == null || old.nextAt() != now) return old;
        return switch(old.stage()) {
            case "OUTBOUND" -> eligible && invite.status() == InterviewModel.Status.ACTIVE && now < invite.scheduledEnd()
                    ? new Trip(old.requestId(),"ONSITE",old.departed(),now,invite.scheduledEnd(),-1)
                    : old.change("RETURNING",now+LEG);
            case "ONSITE" -> old.change("RETURNING",now+LEG);
            case "RETURNING" -> new Trip(old.requestId(),"HOME",old.departed(),old.observedAt(),-1,now);
            default -> old;
        };
    }
    static Row row(InterviewModel.Invite invite, Trip trip, String person, boolean authorized, long finish) {
        long returned = trip == null ? -1 : trip.returnedAt() >= 0 ? trip.returnedAt() :
                "RETURNING".equals(trip.stage()) ? trip.nextAt() : -1;
        return new Row(invite.requestId(),invite.fromPartyId(),invite.toPartyId(),invite.organizationPartyId(),
                trip == null ? "APPOINTMENT" : trip.stage(),invite.startMillis(),invite.scheduledEnd(),trip==null?-1:trip.departed(),
                returned,FARE.toPlainString(),trip!=null && trip.departed()>=0?FARE.toPlainString():"0.00",
                trip!=null && trip.observedAt()>=0,actions(invite,trip,person,authorized,finish),trip==null?-1:trip.observedAt());
    }
}
