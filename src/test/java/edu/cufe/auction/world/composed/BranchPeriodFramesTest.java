package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRepository;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class BranchPeriodFramesTest {
    private static final long DAY = 86_400_000L;
    @TempDir Path dir;

    @Test
    void branchKeepsItsVerifiedMonthsAcrossTwoNewPeriodsAndStrictReopen() throws Exception {
        String id = "month-child";
        Path file = dir.resolve("world.journal");
        var repo = new BranchRepository(dir.resolve("runs"));
        BranchProfile profile;
        String parentDigest;
        WorldRuntime.BranchReceipt branch;
        try (var parent = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            parent.advanceTo(60 * DAY + 1);
            profile = parent.profile();
            parentDigest = parent.confirmedPeriodStatement("pharma", 1).statementDigest();
            branch = parent.createBranch(id, parent.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "月末核验");
        }
        String finalDigest;
        try (var child = repo.open(id, profile)) {
            assertAnchor(child, "priorPeriodAnchor", profile.manifest().runId(), 30 * DAY);
            assertAnchor(child, "currentPeriodAnchor", profile.manifest().runId(), 60 * DAY);
            assertEquals(0L, field(child, "verifiedInformationPosition"));
            assertEquals(parentDigest, child.confirmedPeriodStatement("pharma", 1).statementDigest());
            assertThrows(IllegalArgumentException.class, () -> child.confirmedPeriodStatement("pharma", 3));
            child.advanceTo(90 * DAY + 1);
            assertAnchor(child, "currentPeriodAnchor", id, 90 * DAY);
            assertAnchor(child, "priorPeriodAnchor", profile.manifest().runId(), 60 * DAY);
            child.advanceTo(120 * DAY + 1);
            assertAnchor(child, "priorPeriodAnchor", id, 90 * DAY);
            assertAnchor(child, "currentPeriodAnchor", id, 120 * DAY);
            assertEquals(parentDigest, child.confirmedPeriodStatement("pharma", 1).statementDigest());
            finalDigest = child.confirmedPeriodStatement("pharma", 4).statementDigest();
        }
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(branch.childFile()));
        try (var child = repo.open(id, profile);
             var other = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(dir.resolve("other.journal"))) {
            assertAnchor(child, "priorPeriodAnchor", id, 90 * DAY);
            assertAnchor(child, "currentPeriodAnchor", id, 120 * DAY);
            assertEquals(finalDigest, child.confirmedPeriodStatement("pharma", 4).statementDigest());
            assertEquals(0L, field(child, "verifiedInformationPosition"));
            var source = WorldRuntime.class.getDeclaredField("commitSource");
            source.setAccessible(true);
            Object original = source.get(child);
            try {
                source.set(child, source.get(other));
                assertThrows(IOException.class, () -> child.confirmedPeriodStatement("pharma", 1));
            } finally {
                source.set(child, original);
            }
            assertEquals(parentDigest, child.confirmedPeriodStatement("pharma", 1).statementDigest());
        }
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(branch.childFile())));
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void assertAnchor(WorldRuntime world, String slot, String run, long time) throws Exception {
        Object anchor = field(world, slot);
        assertNotNull(anchor);
        assertEquals(run, field(anchor, "runId"));
        assertEquals(time, field(anchor, "timeMillis"));
    }
}
