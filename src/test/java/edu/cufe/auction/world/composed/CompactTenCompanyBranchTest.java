package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompactTenCompanyBranchTest {
    @TempDir Path dir;

    @Test void compactChildSettlesInheritedUpgradeWhileParentOnlyResumesItsPerson() throws Exception {
        var file=dir.resolve("parent.journal");
        var draft=new ManufacturingUpgrade.Draft("active",ManufacturingUpgrade.ORIGINAL,ManufacturingUpgrade.EXTENDED,List.of("mfg-tech","mfg-struct"));
        WorldRuntime.BranchReceipt receipt;
        WorldRuntime.Snapshot parentEnd, childEnd;
        try(var parent=ComposedIndustryScenario.openManufacturingUpgradeOperating(file)) {
            parent.openRoleSession("investor").bankAction("pending-funds","pending-funds",2_000,100,
                    new WorldRoleSession.BankActionDraft("funds",BankActions.Kind.DEPOSIT,"north","",new java.math.BigDecimal("11.00"),0,0,"","","",""));
            parent.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(parent.snapshot().position(),"investor",true));
            parent.manufacturingUpgrade(new ManufacturingUpgrade.Request(parent.snapshot().position(),ManufacturingUpgrade.Stage.PREPARE,draft));
            long cut=parent.snapshot().position(); var atCut=parent.snapshot();
            parent.advanceTo(parent.snapshot().timeMillis()+500);
            assertTrue(parent.branchSupport(cut).supported(),parent.branchSupport(cut).toString());
            receipt=parent.createBranch("separate-world",cut,BranchOrigin.Kind.OPERATOR_BRANCH,"采购条款");
            assertEquals(cut,receipt.parentPosition());
            try(var child=new BranchRepository(dir.resolve("runs")).open("separate-world",parent.profile())) {
                assertEquals("separate-world",child.profile().manifest().runId());
                assertEquals(parent.profile().manifest().policyRunId(),child.profile().manifest().policyRunId());
                assertEquals(cut+1,child.snapshot().position());
                assertEquals(atCut.accounts(),child.snapshot().accounts());
                assertEquals(atCut.pending(),child.snapshot().pending());
                assertEquals(atCut,child.branchHistoryFrame(cut).frame().snapshot());
                assertEquals(WorldRuntime.HistorySource.PARENT_PREFIX,child.branchHistoryFrame(cut).source());
                assertEquals(WorldRuntime.HistorySource.CHILD,child.branchHistoryFrame(cut+1).source());
                assertTrue(child.personDecisionsPaused("investor")); assertTrue(child.systemPublisherOnly("research-system"));
                assertEquals(parent.engineeringCustomerMandateView(EngineeringCustomerPolicy.REPRESENTATIVE).isPresent(),
                        child.engineeringCustomerMandateView(EngineeringCustomerPolicy.REPRESENTATIVE).isPresent());
                assertTrue(child.engineeringCustomerMandateView(EngineeringCustomerPolicy.REPRESENTATIVE).isPresent());
                child.manufacturingUpgrade(new ManufacturingUpgrade.Request(child.snapshot().position(),ManufacturingUpgrade.Stage.APPLY,draft));
                child.advanceTo(child.snapshot().timeMillis()+2_000);
                assertEquals("11.00",child.valuation("investor").bankClaims().deposits().toPlainString());
                assertEquals("0.00",parent.valuation("investor").bankClaims().deposits().toPlainString());
                assertEquals(ManufacturingUpgrade.EXTENDED,ManufacturingUpgrade.templateVersion(child.operationsRun().orElseThrow(),"mfg-tech"));
                assertEquals(ManufacturingUpgrade.ORIGINAL,ManufacturingUpgrade.templateVersion(parent.operationsRun().orElseThrow(),"mfg-tech"));
                assertFalse(child.branchSupport(child.snapshot().position()).supported());
                parent.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(parent.snapshot().position(),"investor",false));
                parent.advanceTo(parent.snapshot().timeMillis()+3_000);
                assertTrue(child.personDecisionsPaused("investor")); assertFalse(parent.personDecisionsPaused("investor"));
                assertEquals("11.00",parent.valuation("investor").bankClaims().deposits().toPlainString());
                assertEquals("11.00",child.valuation("investor").bankClaims().deposits().toPlainString());
                childEnd=child.snapshot();
            }
            parentEnd=parent.snapshot();
        }
        try(var parent=ComposedIndustryScenario.openManufacturingUpgradeOperating(file);
            var child=new BranchRepository(dir.resolve("runs")).open("separate-world",parent.profile())) {
            assertEquals(parentEnd,parent.snapshot()); assertEquals(childEnd,child.snapshot());
            assertEquals(parent.profile().manifest().runId(),child.branchOrigin().orElseThrow().parentRunId());
        }
        byte[] seal=Files.readAllBytes(receipt.sealedPrefix());
        Files.write(receipt.sealedPrefix(),java.util.Arrays.copyOf(seal,seal.length-1));
        try(var parent=ComposedIndustryScenario.openManufacturingUpgradeOperating(file)) {
            assertThrows(java.io.IOException.class,()->new BranchRepository(dir.resolve("runs")).open("separate-world",parent.profile()));
            assertEquals(seal.length-1,Files.size(receipt.sealedPrefix())); assertEquals(parentEnd,parent.snapshot());
        }
    }

    @Test void unversionedCompactArchiveStillRefusesBranching() throws Exception {
        try(var old=ComposedIndustryScenario.openIntegratedOperating(dir.resolve("old"))) {
            long cut=old.snapshot().position();
            assertEquals(List.of("COMPACT_BRANCH_UNSUPPORTED"), old.branchSupport(cut).reasons());
            assertEquals("COMPACT_BRANCH_UNSUPPORTED",assertThrows(BranchRejectedException.class,
                    ()->old.createBranch("no-upgrade",cut,BranchOrigin.Kind.OPERATOR_BRANCH,"")).code());
            assertFalse(Files.exists(dir.resolve("runs")));
        }
    }

    @Test void childOpeningEventMustMatchTheRecordedParentAndBranchPosition() throws Exception {
        try (var parent=ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("parent"))) {
            long cut=parent.snapshot().position();
            var branch=parent.createBranch("original-child",cut,BranchOrigin.Kind.OPERATOR_BRANCH,"来源核对");
            var altered=dir.resolve("changed");
            try(var source=new WorldJournal(branch.childFile(),"original-child",cut);
                var target=new WorldJournal(altered,"original-child",cut)) {
                var first=source.commits().get(0);
                var events=new java.util.ArrayList<WorldEvent>();
                for(var event:first.events()) {
                    if (!"STATE_DELTA_V1".equals(event.type())) {
                        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                        var origin=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(event.payload());
                        origin.put("parentPosition",cut-1);
                        event=new WorldEvent(event.eventId(),event.type(),origin.toString());
                    }
                    events.add(event);
                }
                target.commit(first.commandId(),first.commandContent(),first.result(),events);
            }
            var error=assertThrows(java.io.IOException.class,()->WorldRuntime.openBranch(altered,id->branch.sealedPrefix(),parent.profile()));
            assertTrue(error.getMessage().contains("来源事件"),error.toString());
        }
    }
}
