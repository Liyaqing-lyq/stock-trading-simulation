import edu.cufe.auction.world.*;
import edu.cufe.auction.web.WorldWebScenario;
import java.math.BigDecimal;
import java.util.*;
/** 季度实验的显式规则初始化；真实阅读、会议、回避、表决和实施，无状态直写。 */
final class PrototypeGovernance {
    static final long DAY = 86_400_000L;
    static long grantBudgetAndAppoint(WorldRuntime world, String company, List<String> directors,
            String manager, String prefix, List<Long> periods) throws Exception {
        long now = Math.max(world.snapshot().timeMillis(), 1L);
        String procurement = company.equals("pharma") ? "1000.00" : "3000.00";
        String labor = company.equals("digital-company") ? "6000.00" : company.equals("pharma") ? "900.00" : "3000.00";
        String budgetText = "EXP季度预算：各30日期采购上限"+procurement+"、生产人工上限"+labor+"；授权不是现金；真实支付仍核现金、供给、权限与工时。不为展示补钱。";
        String budgetDoc = prefix + "-budget-doc";
        String appointmentDoc = prefix + "-appt-doc";
        world.schedulePublishDocument(prefix + "-pub-b", prefix + "-pub-b-task", now + 10L,
                budgetDoc, WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "经营预算材料",
                budgetText, budgetText, 10L, List.of());
        world.schedulePublishDocument(prefix + "-pub-a", prefix + "-pub-a-task", now + 10L,
                appointmentDoc, WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "任免材料",
                "EXP规则初始化：提名" + manager + "为" + company + "经理，任期90实验日；负责有限采购、经营和信息披露，不可跳过预算或客户验收。", "EXP规则初始化：提名" + manager + "为" + company + "经理，任期90实验日；负责有限采购、经营和信息披露，不可跳过预算或客户验收。", 10L, List.of());
        List<String> readers = new ArrayList<>(directors);
        if (!readers.contains(manager)) readers.add(manager);
        int index = 0;
        for (String reader : readers) {
            world.scheduleStartReading(prefix + "-rb-" + reader, prefix + "-rb-task-" + reader,
                    now + 20L + index * 2L, reader, budgetDoc, 1L);
            world.scheduleStartReading(prefix + "-ra-" + reader, prefix + "-ra-task-" + reader,
                    now + 60L + index * 2L, reader, appointmentDoc, 1L);
            index++;
        }
        long convene = now + 1_000L;
        List<String> voters = directors.stream().filter(p -> !p.equals(manager)).limit(4).toList();
        if (!company.equals("north")) {
        convene(world, prefix + "-bc", prefix + "-bc-task", convene, company,
                directors.get(0), prefix + "-budget-meeting", convene + 50L, convene + 100L,
                convene + 3_000L, false);
        for (int i = 0; i < voters.size(); i++) {
            attend(world, prefix + "-ba-" + i, prefix + "-ba-task-" + i,
                    convene + 10L + i, company, voters.get(i), prefix + "-budget-meeting");
        }
        material(world, prefix + "-bm", prefix + "-bm-task", convene + 30L,
                company, directors.get(0), prefix + "-budget-meeting", "m1", budgetDoc, 1L);
        List<BoardModel.BudgetLine> lines = new ArrayList<>();
        for (long period : periods) {
            lines.add(new BoardModel.BudgetLine(period, BoardModel.BudgetCategory.PROCUREMENT,
                    new BigDecimal(procurement)));
            lines.add(new BoardModel.BudgetLine(period, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                    new BigDecimal(labor)));
        }
        budget(world, prefix + "-bp", prefix + "-bp-task",
                convene + 40L, company, directors.get(0), prefix + "-budget-meeting", lines);
        for (int i = 0; i < voters.size(); i++) {
            vote(world, prefix + "-bv-" + i, prefix + "-bv-task-" + i,
                    convene + 2_000L + i, company, voters.get(i), prefix + "-budget-meeting",
                    BoardModel.Vote.YES, 1_000L);
        }
        implement(world, prefix + "-bi", prefix + "-bi-task", convene + 4_000L,
                company, directors.get(0), prefix + "-budget-meeting");

        }
        long appointAt = convene + 5_000L;
        long startsAt = appointAt + 6_000L;
        convene(world, prefix + "-ac", prefix + "-ac-task", appointAt, company,
                directors.get(0), prefix + "-appt-meeting", appointAt + 50L, appointAt + 100L,
                appointAt + 3_000L, true);
        for (int i = 0; i < voters.size(); i++) {
            attend(world, prefix + "-aa-" + i, prefix + "-aa-task-" + i,
                    appointAt + 10L + i, company, voters.get(i), prefix + "-appt-meeting");
        }
        material(world, prefix + "-am", prefix + "-am-task", appointAt + 30L,
                company, directors.get(0), prefix + "-appt-meeting", "m1", appointmentDoc, 1L);
        appoint(world, prefix + "-ap", prefix + "-ap-task",
                appointAt + 40L, company, directors.get(0), prefix + "-appt-meeting",
                new BoardModel.AppointmentRequest(prefix + "-mgr", manager, company,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        startsAt, startsAt + 90L * DAY));
        for (int i = 0; i < voters.size(); i++) {
            vote(world, prefix + "-av-" + i, prefix + "-av-task-" + i,
                    appointAt + 2_000L + i, company, voters.get(i), prefix + "-appt-meeting",
                    BoardModel.Vote.YES, 1_000L);
        }
        implement(world, prefix + "-ai", prefix + "-ai-task", appointAt + 4_000L,
                company, directors.get(0), prefix + "-appt-meeting");
        world.advanceTo(startsAt);
        return startsAt;
    }


    static void convene(WorldRuntime w,String c,String t,long at,String company,String person,String meeting,long material,long voting,long deadline,boolean appointment) throws Exception {
        if (!company.equals("eng-a")) w.scheduleCompanyBoardConvene(c,t,at,company,person,meeting,material,voting,deadline,appointment);
        else if(appointment) w.scheduleEngineeringAppointmentConvene(c,t,at,company,person,meeting,material,voting,deadline);
        else w.scheduleEngineeringConvene(c,t,at,company,person,meeting,material,voting,deadline);
    }
    static void attend(WorldRuntime w,String c,String t,long at,String company,String person,String meeting) throws Exception {
        if(company.equals("eng-a"))w.scheduleEngineeringAttend(c,t,at,company,person,meeting,false);else w.scheduleCompanyBoardAttend(c,t,at,company,person,meeting);
    }
    static void material(WorldRuntime w,String c,String t,long at,String company,String person,String meeting,String material,String doc,long version) throws Exception {
        if(company.equals("eng-a"))w.scheduleEngineeringMaterial(c,t,at,company,person,meeting,material,doc,version);else w.scheduleCompanyBoardMaterial(c,t,at,company,person,meeting,material,doc,version);
    }
    static void budget(WorldRuntime w,String c,String t,long at,String company,String person,String meeting,List<BoardModel.BudgetLine> lines) throws Exception {
        if(company.equals("eng-a"))w.scheduleEngineeringBudgetProposal(c,t,at,company,person,meeting,lines);else w.scheduleCompanyBoardBudgetProposal(c,t,at,company,person,meeting,lines);
    }
    static void vote(WorldRuntime w,String c,String t,long at,String company,String person,String meeting,BoardModel.Vote vote,long duration) throws Exception {
        if(company.equals("eng-a"))w.scheduleEngineeringVote(c,t,at,company,person,meeting,vote,duration);else w.scheduleCompanyBoardVote(c,t,at,company,person,meeting,vote,duration);
    }
    static void implement(WorldRuntime w,String c,String t,long at,String company,String person,String meeting) throws Exception {
        if(company.equals("eng-a"))w.scheduleEngineeringImplement(c,t,at,company,person,meeting);else w.scheduleCompanyBoardImplement(c,t,at,company,person,meeting);
    }
    static void appoint(WorldRuntime w,String c,String t,long at,String company,String person,String meeting,BoardModel.AppointmentRequest request) throws Exception {
        if(company.equals("eng-a"))w.scheduleEngineeringAppointmentProposal(c,t,at,company,person,meeting,List.of("ea5"),request);else w.scheduleCompanyBoardAppointmentProposal(c,t,at,company,person,meeting,request);
    }
}
