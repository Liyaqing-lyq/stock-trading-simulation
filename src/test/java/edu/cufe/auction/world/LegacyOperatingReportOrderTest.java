package edu.cufe.auction.world;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.TreeMap;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import static org.junit.jupiter.api.Assertions.*;

/** 只允许可达旧 map 表示，所有内容仍来自生成行。 */
class LegacyOperatingReportOrderTest {
    @TempDir Path dir;
    private String reconstruct(String generated, String historical, long cutoff) throws IOException {
        return LegacyOperatingReportOrder.reconstruct(generated,historical,cutoff,LegacyOperatingReportOrder.ALL_PATHS);
    }
    private void requireBatch(List<LegacyOperatingReportOrder.InputPair> inputs, long cutoff) throws IOException {
        LegacyOperatingReportOrder.requireCommonBatch(inputs,cutoff,LegacyOperatingReportOrder.ALL_PATHS);
    }
    private String row(String id, long position) {
        return "{\"taskId\":\"" + id + "\",\"position\":\"" + position + "\",\"reason\":\"中文😀\",\"amount\":\"1.00\"}";
    }
    private String input(String reports) {
        return "{\"outside\":1e+2, \"engineering\":{\"hydroOperations\":{\"reports\":[" + reports + "]}},\"tail\":\"literal reports [ ]\"}";
    }
    @Test void acceptsOriginalCanonicalAndMixedPrefixWithoutChangingOtherBytes() throws Exception {
        String z=row("z",10),a=row("a",20),m=row("m",30);
        String generated=input(String.join(",",List.of(a,m,z)));
        for(String order:List.of(String.join(",",List.of(z,a,m)),String.join(",",List.of(a,z,m)),String.join(",",List.of(a,m,z)))) {
            String stored=input(order); assertEquals(stored,reconstruct(generated,stored,40));
        }
        assertThrows(IOException.class,()->reconstruct(generated,input(String.join(",",List.of(m,z,a))),40));
    }
    @Test void refusesInvalidMembershipAndUsesNoStoredRowValues() throws Exception {
        String a=row("a",10),z=row("z",20),generated=input(a+","+z);
        for(String bad:List.of(input(a),input(a+","+a),input(a+","+row("foreign",20)),input("null,"+a)))
            assertThrows(IOException.class,()->reconstruct(generated,bad,30));
        String tampered=input(z+","+a).replace("1.00","999.00");
        // z,a 不是本例可达排列；另用位置反序输入证明只取可信值。
        String trusted=input(row("z",10)+","+row("a",20));
        String historical=input(row("a",20)+","+row("z",10)).replace("1.00","999.00");
        String reconstructed=reconstruct(trusted,historical,30);
        assertFalse(reconstructed.contains("999.00")); assertNotEquals(historical,reconstructed);
    }
    @Test void allCompanyArraysMustShareOnePossibleRestartCut() throws Exception {
        String one=String.join(",",List.of(row("z",10),row("a",20),row("m",30)));
        String two=String.join(",",List.of(row("z2",5),row("a2",15),row("m2",25)));
        String original="{\"engineering\":{\"companyOperations\":[{\"reports\":["+one+"]},{\"reports\":["+two+"]}]}}";
        String impossible=original.replace(one,String.join(",",List.of(row("a",20),row("z",10),row("m",30))));
        assertThrows(IOException.class,()->reconstruct(original,impossible,40));
        String compatible=impossible.replace(two,String.join(",",List.of(row("a2",15),row("z2",5),row("m2",25))));
        assertEquals(compatible,reconstruct(original,compatible,40));
    }

    @Test void batchIncludesAlreadyEqualInputsInOneGlobalCut() throws Exception {
        String generatedA=input(row("aA",30)+","+row("zA",10));
        String historicalA=input(row("zA",10)+","+row("aA",30));
        String generatedB=input(row("aB",40)+","+row("zB",20));
        assertEquals(historicalA,reconstruct(generatedA,historicalA,50));
        assertEquals(generatedB,reconstruct(generatedB,generatedB,50));
        assertThrows(IOException.class,()->requireBatch(List.of(
                new LegacyOperatingReportOrder.InputPair(generatedA,historicalA),
                new LegacyOperatingReportOrder.InputPair(generatedB,generatedB)),50));
        requireBatch(List.of(
                new LegacyOperatingReportOrder.InputPair(generatedA,generatedA),
                new LegacyOperatingReportOrder.InputPair(generatedB,generatedB)),50);
    }

    @Test void manifestFixedManufacturingOrderIsNeitherReorderedNorUsedAsRestartEvidence() throws Exception {
        try (var connected=ComposedIndustryScenario.openConnected(dir.resolve("connected.journal"));
             var informed=ComposedIndustryScenario.openInformedOperating(dir.resolve("informed.journal"))) {
            var original=informed.profile().manifest(); var legacyData=new TreeMap<>(original.dataVersions());
            legacyData.remove("operating-report-order");
            var legacyManager=new RunManifest(original.runId(),original.seed(),original.rulesVersion(),original.moduleVersions(),legacyData,original.modelConfigVersion());
            for(var manifest:List.of(connected.profile().manifest(),legacyManager)) {
                var paths=LegacyOperatingReportOrder.affectedPaths(manifest);
                assertFalse(paths.contains("companyOperations"));assertTrue(paths.contains("hydroOperations"));
                String stable=row("aM",40)+","+row("zM",30);
                String hydroGenerated=row("aH",20)+","+row("zH",10);
                String hydroHistorical=row("zH",10)+","+row("aH",20);
                String generated="{\"engineering\":{\"hydroOperations\":{\"reports\":["+hydroGenerated+"]},\"companyOperations\":[{\"reports\":["+stable+"]}]}}";
                String historical=generated.replace(hydroGenerated,hydroHistorical);
                assertEquals(historical,LegacyOperatingReportOrder.reconstruct(generated,historical,50,paths));
                LegacyOperatingReportOrder.requireCommonBatch(List.of(new LegacyOperatingReportOrder.InputPair(generated,historical)),50,paths);
                String changedStable=historical.replace(stable,row("zM",30)+","+row("aM",40));
                assertNotEquals(changedStable,LegacyOperatingReportOrder.reconstruct(generated,changedStable,50,paths));
            }
        }
    }
}
