package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BankOpeningOwnershipTest {
    @TempDir Path dir;

    @Test void conservingTotalDepositsDoesNotPermitReassigningAnOpeningClaim() throws Exception {
        var original = BankFundsFixture.endowment();
        var changed = new BankOpening.DeclaredEndowment(original.banks(), original.loans(),
                List.of(new BankOpening.DepositEndowment("north", "acme", new BigDecimal("900.00"), null),
                        new BankOpening.DepositEndowment("north", "firm", new BigDecimal("300.00"), null)),
                original.facilities(), original.collaterals(), original.origins(), original.providerOpenings(), original.source());
        var request = BankOpeningRequest.of(BankFundsFixture.config(), changed, Map.of(), Map.of());
        var error = assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = new WorldRuntime(dir.resolve("conflict"), BankFundsFixture.manifest(),
                    BankFundsFixture.openings(), BankFundsFixture.registry(), BankFundsFixture.roles(),
                    BankFundsFixture.information(), request)) {
                fail("两户合计不变不能改变存款的原始权属");
            }
        });
        assertEquals("OPENING_DEPOSIT_MISMATCH:north|acme", error.getMessage());
        Path file = dir.resolve("valid");
        try (var world = BankFundsFixture.open(file)) {
            assertEquals(1L, world.snapshot().position());
        }
        try (var restored = BankFundsFixture.open(file)) {
            var ledger = restored.bankOf().orElseThrow().decode(restored.snapshot().accounts()).state();
            assertEquals(new BigDecimal("1200.00"), ledger.depositOf("north", "acme"));
            assertEquals(new BigDecimal("0.00"), ledger.depositOf("north", "firm"));
        }
    }
}
