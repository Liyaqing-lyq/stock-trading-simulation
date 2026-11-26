package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OwnerPortfolioRoleTest {
    @TempDir Path dir;
    private static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");

    @Test void modelAndRoleSeeTheSamePrivateBankBalancesAtTheSavedCut() throws Exception {
        Path journal = dir.resolve("subject.journal");
        var json = new ObjectMapper();
        String saved;
        try (var world = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, DRAFT)) {
            long due = world.snapshot().timeMillis() + 3;
            var deposit = new WorldRoleSession.BankActionDraft("personal-deposit", BankActions.Kind.DEPOSIT,
                    "north", "", new BigDecimal("175.00"), 0, 0, "", "", "", "");
            world.openRoleSession("investor").bankAction("deposit", "deposit-task", due, 1, deposit);
            world.advanceTo(due);
            var portfolio = world.roleView("investor").portfolios();
            assertEquals(1, portfolio.size());
            assertEquals("175.00", portfolio.get(0).bankClaims().deposits());
            assertEquals("4825.00", portfolio.get(0).cash());
            assertEquals("11350.00", portfolio.get(0).netWorth());
            var model = world.autonomyRoleObservation("investor").at("/profile/portfolios");
            assertEquals(json.valueToTree(portfolio), model);
            assertFalse(model.toString().contains("position"));
            assertFalse(world.autonomyRoleObservation("nina").at("/profile/portfolios").toString().contains("175.00"));
            assertFalse(world.autonomyRoleObservation("investor").at("/profile/portfolios").toString().contains("fund-a"));
            world.beginDecisionSession("session");
            world.openDecisions("open", List.of(new DecisionRun.Request("investor", "own-assets", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1, 20, 1_000_000, 1024, 1))));
            saved = world.snapshot().decisions().orElseThrow().state().decisions().get(0).inputText();
            assertEquals(model, json.readTree(saved).at("/profile/portfolios"));
        }
        try (var world = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, null)) {
            assertEquals(saved, world.snapshot().decisions().orElseThrow().state().decisions().get(0).inputText());
            assertEquals("175.00", world.roleView("investor").portfolios().get(0).bankClaims().deposits());
        }
        try (var world = ComposedIndustryScenario.openOpeningCopiesOperating(dir.resolve("old.journal"), DRAFT)) {
            assertNull(world.roleView("investor").portfolios());
            assertTrue(world.autonomyRoleObservation("investor").at("/profile/portfolios").isMissingNode());
        }
    }
}
