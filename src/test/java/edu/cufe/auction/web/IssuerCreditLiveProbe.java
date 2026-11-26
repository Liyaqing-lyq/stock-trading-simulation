package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.app.WorldWebApp;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.composed.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 限定两次真实提供方调用的授信诊断，董事任命和已经接受的样本申请是夹具。 */
public final class IssuerCreditLiveProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param args 唯一新建的证据目录；第二参可指定五槽凭据文件 */
    public static void main(String[] args) throws Exception {
        Path out = Files.createDirectory(Path.of(args[0]));
        var provider = new LiveDecisionProvider(KeySlots.load(Path.of(args.length > 1 ? args[1] : WorldWebApp.DEFAULT_KEY_FILE)));
        String bank = ComposedWorldScenario.BANK, company = ComposedManufacturingThreeScenario.TECH;
        String manager = ComposedManufacturingThreeScenario.TECH_MANAGER;
        try (var world = ComposedIndustryScenario.openIssuerCreditDecisionsOperating(out.resolve("world.journal"))) {
            for (var entry : List.of(List.of(company,manager), List.of(bank,"erin")))
                ComposedWorldSelfTest.appoint(world, world.snapshot().timeMillis()+1, entry.get(0), entry.get(1),
                        "office-"+entry.get(0), "opening-material-"+entry.get(0));
            for (var kind : List.of(BankActions.Kind.LOAN_REQUEST, BankActions.Kind.LOAN_ACCEPT)) {
                long due=world.snapshot().timeMillis()+2;
                var draft=new WorldRoleSession.BankActionDraft("seed-"+kind,kind,bank,company,new BigDecimal("200.00"),
                        20000L,30*86400000L,"补充材料采购周转资金","","","working-capital");
                world.openRoleSession(manager).bankAction("seed-"+kind,"seed-"+kind,due,1,draft);
                world.advanceTo(due);
                if (!"OK".equals(world.taskCommitResult(WorldRuntime.roleTaskId(manager,"seed-"+kind)+":finish")))
                    throw new IllegalStateException("开局申请未成功");
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("opening.json").toFile(),world.bankBook());
            var summary=JSON.createArrayNode();
            for (String who : List.of("erin",manager)) {
                String id = "credit-diagnostic-"+who;
                world.beginDecisionSession("session-"+id);
                world.openDecisions("open-"+id,List.of(new DecisionRun.Request(who,id,"wake",
                        new DecisionModel.Route("governance","key-1","deepseek-flash",1L,2,200000,8192,1,
                                "lifecycle-v1","deepseek-thinking-high-v1"))));
                var call=world.dispatchLiveDecision("dispatch-"+id,id).call();
                if (call==null) throw new IllegalStateException("未成功派发："+who);
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve(who+"-input.json").toFile(),call);
                var actual=provider.evaluateAudited(call,60_000L);
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve(who+"-reply.json").toFile(),actual);
                world.offerLiveDecisionCompletion(actual.completion());
                world.drainDecisionCallbacks();
                var ref=world.snapshot().decisions().orElseThrow().actionRef(id);
                if (ref!=null) world.advanceTo(ref.dueMillis()+60_000L);
                var item=summary.addObject().put("person",who);
                item.set("decision",JSON.valueToTree(DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),id).orElseThrow().outcome()));
                item.set("action",JSON.valueToTree(ref));
                item.set("result",world.autonomyRoleObservation(who).at("/engineering/deliberation/lastActionResult"));
                item.set("loans",JSON.valueToTree(world.bankBook()));
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("results.json").toFile(),summary);
            }
        }
    }
}
