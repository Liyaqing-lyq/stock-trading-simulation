package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedCompanyReport;
import java.math.BigDecimal;
import java.util.*;

/** 三制造网页的同帧投影和真实会话命令；未来情景仅对管理员开放。 */
final class OperationsWeb {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new SimpleModule()
            .addSerializer(BigDecimal.class, ToStringSerializer.instance)
            .addSerializer(Long.class, ToStringSerializer.instance)
            .addSerializer(Long.TYPE, ToStringSerializer.instance));
    private OperationsWeb() { }

    record Command(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
                   long durationMillis, String companyId, CompanyOperationsRun.Action action,
                   CompanyOperationsRun.Fields fields) { }

    record ResourceCommand(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
            long durationMillis, String companyId, edu.cufe.auction.world.ResourceOperations.Request request) { }

    static String scheduleResource(WorldRuntime world, String person, ResourceCommand command) throws Exception {
        if (world.snapshot().position() != command.expectedPosition()) throw new WorldWebHost.PositionMismatch(world.snapshot().position());
        var resource = world.operationsRun().orElseThrow().resource();
        if (resource == null || !resource.companyPartyId().equals(command.companyId()) || person == null
                || !world.isActiveManager(command.companyId(), person, world.snapshot().timeMillis())) throw new IllegalArgumentException("本人没有该资源公司经理任职");
        return world.openRoleSession(person).resourceAction(command.commandId(), command.taskId(), command.dueTimeMillis(),
                command.companyId(), command.request(), command.durationMillis());
    }

    record HydroCommand(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
            long durationMillis, String companyId, edu.cufe.auction.world.HydroOperations.Request request) { }

    static String scheduleHydro(WorldRuntime world, String person, HydroCommand command) throws Exception {
        if (world.snapshot().position() != command.expectedPosition()) throw new WorldWebHost.PositionMismatch(world.snapshot().position());
        var hydro = world.operationsRun().orElseThrow().hydro();
        if (hydro == null || !hydro.companyPartyId().equals(command.companyId()) || person == null
                || !world.isActiveManager(command.companyId(), person, world.snapshot().timeMillis())) throw new IllegalArgumentException("本人没有该水电公司经理任职");
        return world.openRoleSession(person).hydroAction(command.commandId(), command.taskId(), command.dueTimeMillis(),
                command.companyId(), command.request(), command.durationMillis());
    }

    record DigitalCommand(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
            long durationMillis, String companyId, edu.cufe.auction.world.DigitalOperations.Request request) { }

    static String scheduleDigital(WorldRuntime world, String person, DigitalCommand command) throws Exception {
        if (world.snapshot().position() != command.expectedPosition()) throw new WorldWebHost.PositionMismatch(world.snapshot().position());
        var digital = world.operationsRun().orElseThrow().digital();
        if (digital == null || !digital.companyPartyId().equals(command.companyId()) || person == null
                || !world.isActiveManager(command.companyId(), person, world.snapshot().timeMillis())) throw new IllegalArgumentException("本人没有该数字服务公司经理任职");
        return world.openRoleSession(person).digitalAction(command.commandId(), command.taskId(), command.dueTimeMillis(),
                command.companyId(), command.request(), command.durationMillis());
    }

    record AgedCommand(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
            long durationMillis, String companyId, edu.cufe.auction.world.AgedOperations.Request request) { }

    static String scheduleAged(WorldRuntime world, String person, AgedCommand command) throws Exception {
        if (world.snapshot().position() != command.expectedPosition()) throw new WorldWebHost.PositionMismatch(world.snapshot().position());
        var aged = world.operationsRun().orElseThrow().aged();
        if (aged == null || !aged.companyPartyId().equals(command.companyId()) || person == null
                || !world.isActiveManager(command.companyId(), person, world.snapshot().timeMillis())) throw new IllegalArgumentException("本人没有该陈酿公司经理任职");
        return world.openRoleSession(person).agedAction(command.commandId(), command.taskId(), command.dueTimeMillis(),
                command.companyId(), command.request(), command.durationMillis());
    }

    static Map<String, Object> view(WorldRuntime world, String person) {
        var snapshot = world.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("position", Long.toString(snapshot.position()));
        result.put("timeMillis", Long.toString(snapshot.timeMillis()));
        List<Map<String, Object>> companies = new ArrayList<>();
        var run = world.operationsRun().orElse(null);
        if (run != null) for (var company : run.companies()) {
            String id = company.companyPartyId();
            boolean manager = person != null && world.isActiveManager(id, person, snapshot.timeMillis());
            if (person != null && !manager) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("companyId", id);
            row.put("name", world.openingRegistry().orElseThrow().parties().stream()
                    .filter(party -> party.partyId().equals(id)).map(party -> party.name()).findFirst().orElse(id));
            row.put("actions", manager ? world.operationsActions(id) : List.of());
            row.put("plan", JSON.valueToTree(world.operationsView(id).orElseThrow()));
            row.put("financial", JSON.valueToTree(ComposedCompanyReport.of(world, id)));
            var ledger = company.ledger();
            row.put("materials", JSON.valueToTree(ledger.materials()));
            row.put("products", JSON.valueToTree(ledger.products()));
            row.put("purchases", JSON.valueToTree(ledger.purchases()));
            row.put("batches", JSON.valueToTree(ledger.batches()));
            row.put("contracts", JSON.valueToTree(ledger.contracts()));
            row.put("receivables", JSON.valueToTree(ledger.receivables()));
            row.put("trials", JSON.valueToTree(ledger.trials()));
            row.put("expansions", JSON.valueToTree(ledger.expansions()));
            row.put("reports", JSON.valueToTree(run.reports().values().stream()
                    .filter(report -> report.companyPartyId().equals(id)).toList()));
            if (person == null) row.put("scenarioTruth", JSON.valueToTree(company));
            companies.add(row);
        }
        if (run != null && run.resource() != null) {
            var resource = run.resource(); String id = resource.companyPartyId();
            boolean manager = person != null && world.isActiveManager(id, person, snapshot.timeMillis());
            if (person == null || manager) {
                var reports = run.reports().values().stream().filter(report -> id.equals(report.companyPartyId())).toList();
                var plan = edu.cufe.auction.world.ResourceDecisionTools.observation(resource, world.boardOfCompany(id).orElseThrow(),
                        snapshot.timeMillis(), snapshot.accounts().get(resource.ledger().config().companyAccountId()).cash(), reports);
                List<String> actions = new ArrayList<>(); plan.path("actionFields").fieldNames().forEachRemaining(actions::add);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", "RESOURCE"); row.put("companyId", id); row.put("name", "资源公司：煤炭与发电一体化");
                row.put("actions", manager ? actions : List.of()); row.put("plan", plan);
                row.put("financial", JSON.valueToTree(ComposedCompanyReport.of(world, id)));
                row.put("jobs", JSON.valueToTree(resource.ledger().jobs())); row.put("receivables", JSON.valueToTree(resource.ledger().receivables()));
                row.put("reports", JSON.valueToTree(reports));
                if (person == null) row.put("scenarioTruth", JSON.valueToTree(resource));
                companies.add(row);
            }
        }
        result.put("enabled", !companies.isEmpty());
        if (run != null && run.hydro() != null) {
            var hydro = run.hydro(); String id = hydro.companyPartyId();
            boolean manager = person != null && world.isActiveManager(id, person, snapshot.timeMillis());
            if (person == null || manager) {
                var reports = run.reports().values().stream().filter(report -> id.equals(report.companyPartyId())).toList();
                var plan = edu.cufe.auction.world.HydroDecisionTools.observation(hydro, world.boardOfCompany(id).orElseThrow(),
                        snapshot.accounts().get(hydro.ledger().config().companyAccountId()).cash(), reports);
                List<String> actions = new ArrayList<>(); plan.path("actionFields").fieldNames().forEachRemaining(actions::add);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", "HYDRO"); row.put("companyId", id); row.put("name", "水电公司：有限来水与受约束发电");
                row.put("actions", manager ? actions : List.of()); row.put("plan", plan);
                row.put("financial", JSON.valueToTree(ComposedCompanyReport.of(world, id)));
                row.put("plant", JSON.valueToTree(hydro.ledger().plant()));
                row.put("reports", JSON.valueToTree(reports));
                if (person == null) row.put("scenarioTruth", JSON.valueToTree(hydro));
                companies.add(row);
            }
        }
        result.put("enabled", !companies.isEmpty());
        if(run!=null && run.digital()!=null) {
            var company=run.digital();String id=company.companyPartyId();
            boolean manager=person!=null && world.isActiveManager(id,person,snapshot.timeMillis());
            if(person==null || manager) {
                var reports=run.reports().values().stream().filter(r->id.equals(r.companyPartyId())).toList();
                var board=world.boardOfCompany(id).orElseThrow().board();long period=snapshot.timeMillis()/edu.cufe.auction.world.ManufacturingTemplateModel.PERIOD_MILLIS;
                var category=edu.cufe.auction.world.BoardModel.BudgetCategory.PRODUCTION_LABOR;
                BigDecimal budget=board.budget(period,category)==null?null:edu.cufe.auction.world.BoardModel.availableBudget(board,period,category);
                var bank=world.bankOf().orElseThrow().decode(snapshot.accounts());var plan=edu.cufe.auction.world.DigitalDecisionTools.observation(company,snapshot.timeMillis(),snapshot.accounts().get(company.config().model().companyAccountId()).cash(),reports,budget);
                List<String> actions=new ArrayList<>();plan.path("actionFields").fieldNames().forEachRemaining(actions::add);
                Map<String,Object> row=new LinkedHashMap<>();row.put("kind","DIGITAL");row.put("companyId",id);row.put("name","数字服务公司");row.put("actions",manager?actions:List.of());row.put("plan",plan);
                row.put("financial",JSON.valueToTree(ComposedCompanyReport.of(world,id)));row.put("reports",JSON.valueToTree(reports));
                if(person==null) row.put("scenarioTruth",JSON.valueToTree(company));companies.add(row);
            }
        }
        if(run!=null && run.aged()!=null) {
            var company=run.aged();String id=company.companyPartyId();
            boolean manager=person!=null && world.isActiveManager(id,person,snapshot.timeMillis());
            if(person==null || manager) {
                var reports=run.reports().values().stream().filter(r->id.equals(r.companyPartyId())).toList();
                var board=world.boardOfCompany(id).orElseThrow().board();long period=snapshot.timeMillis()/edu.cufe.auction.world.ManufacturingTemplateModel.PERIOD_MILLIS;
                var category=edu.cufe.auction.world.BoardModel.BudgetCategory.PRODUCTION_LABOR;
                BigDecimal budget=board.budget(period,category)==null?null:edu.cufe.auction.world.BoardModel.availableBudget(board,period,category);
                var bank=world.bankOf().orElseThrow().decode(snapshot.accounts());var plan=edu.cufe.auction.world.AgedDecisionTools.observation(company,new edu.cufe.auction.world.bank.FundState(snapshot.accounts(),bank.state(),bank.book()),snapshot.timeMillis(),reports,budget);
                List<String> actions=new ArrayList<>();plan.path("actionFields").fieldNames().forEachRemaining(actions::add);
                Map<String,Object> row=new LinkedHashMap<>();row.put("kind","AGED");row.put("companyId",id);row.put("name","陈酿公司");row.put("actions",manager?actions:List.of());row.put("plan",plan);
                row.put("financial",JSON.valueToTree(ComposedCompanyReport.of(world,id)));row.put("reports",JSON.valueToTree(reports));
                if(person==null) row.put("scenarioTruth",JSON.valueToTree(company));companies.add(row);
            }
        }
        result.put("enabled", !companies.isEmpty());
        result.put("companies", companies);
        result.put("note", person == null ? "上帝审计含冻结情景；经理只看本公司已发生台账和公开方案。"
                : "仅显示本人当前管理的公司。排程不等于完成，开始与完成时均复核任职和工时。");
        return result;
    }

    static String schedule(WorldRuntime world, String person, Command command) throws Exception {
        if (world.snapshot().position() != command.expectedPosition()) {
            throw new WorldWebHost.PositionMismatch(world.snapshot().position());
        }
        if (person == null || !world.isActiveManager(command.companyId(), person, world.snapshot().timeMillis())) {
            throw new IllegalArgumentException("本人没有该公司有效经理任职");
        }
        return world.openRoleSession(person).operationsAction(command.commandId(), command.taskId(),
                command.dueTimeMillis(), command.companyId(), command.action(), command.fields(), command.durationMillis());
    }

    static CompanyOperationsRun.Fields fields(JsonNode node, CompanyOperationsRun.Action action) throws Exception {
        return edu.cufe.auction.world.CompanyOperationsDecisionTools.fields(node, action);
    }
}
