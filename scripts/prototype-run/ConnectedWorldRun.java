import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** 互通世界可重复运行入口：新档开局授权，后续全部由Web同一规则唤醒，不用固定交易日历。 */
public final class ConnectedWorldRun {
    /** 参数：新目录、推进实验日数；拒绝覆盖已有档案。 */
    public static void main(String[] args)throws Exception {
        Path folder=Path.of(args[0]);Files.createDirectories(folder);Path file=folder.resolve("world-connected-v1.journal");
        if(Files.exists(file))throw new IllegalArgumentException("已有运行不得覆盖");int days=Integer.parseInt(args[1]);
        var json=new ObjectMapper();
        try(var w=ComposedIndustryScenario.openConnected(file)){
            for(var e:ConnectedWorldLoop.managers().entrySet())ConnectedWorldBootstrap.grantBudgetAndAppoint(w,e.getKey(),w.boardOfCompany(e.getKey()).orElseThrow().board().charter().seats().stream().map(BoardModel.Seat::partyId).toList(),e.getValue(),"opening-"+e.getKey(),List.of(0L,1L,2L));
            Files.writeString(folder.resolve("opening-accounts.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(w.snapshot().accounts()));
            for(int day=1;day<=days;day++){ConnectedWorldLoop.advanceTo(w,day*86_400_000L);System.out.println("DAY="+day+" POSITION="+w.snapshot().position()+" TRADES="+w.snapshot().exchange().orElseThrow().trades().size());}
            Files.writeString(folder.resolve("activities.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(ConnectedWorldLoop.audit(w)));
            Files.writeString(folder.resolve("accounts.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(w.snapshot().accounts()));
            Files.writeString(folder.resolve("exchange.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(w.snapshot().exchange().orElseThrow()));
            Files.writeString(folder.resolve("operations.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(w.operationsRun().orElseThrow()));
        }
        try(var w=ComposedIndustryScenario.openConnected(file)){System.out.println("RECOVERED="+w.snapshot().position()+" TIME="+w.snapshot().timeMillis());}
    }
}
