package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bankprobe.BankWorldFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class FinancialBankReadTest {
    @TempDir Path dir;

    @Test
    void completeNamedCashInputsAndWorldIdentityControlReuse() throws Exception {
        try (var first = BankWorldFixture.open(dir.resolve("first.journal"));
             var second = BankWorldFixture.open(dir.resolve("second.journal"))) {
            var bank = first.bankOf().orElseThrow();
            var accounts = new TreeMap<>(first.snapshot().accounts());
            var initial = first.verifiedFinancialBank(bank, accounts);
            var equalBank = new BankRun(bank.runId(), bank.configFingerprint(), bank.profileFingerprint(),
                    bank.configJson(), bank.stateJson(), bank.custodyJson());
            assertSame(initial, first.verifiedFinancialBank(equalBank, new TreeMap<>(accounts)));
            assertNotSame(initial, second.verifiedFinancialBank(bank, accounts));

            // 同批两户反向调账，总现金没有改变；仍须基于新的具名余额重新解码。
            var acme = accounts.get("acme-cash");
            var firm = accounts.get("firm-cash");
            accounts.put("acme-cash", new CashLedger.CashAccount(acme.ownerId(),
                    acme.cash().subtract(BigDecimal.ONE), acme.source()));
            accounts.put("firm-cash", new CashLedger.CashAccount(firm.ownerId(),
                    firm.cash().add(BigDecimal.ONE), firm.source()));
            var transferred = first.verifiedFinancialBank(bank, accounts);
            assertNotSame(initial, transferred);
            assertSame(transferred, first.verifiedFinancialBank(bank, new TreeMap<>(accounts)));

            accounts.put("acme-cash", acme);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> first.verifiedFinancialBank(bank, accounts));
            assertEquals(assertThrows(IllegalArgumentException.class, () -> bank.decode(accounts))
                    .getMessage(), failure.getMessage());

            var reserve = accounts.get("rsv-north");
            accounts.put("rsv-north", new CashLedger.CashAccount(reserve.ownerId(),
                    reserve.cash().subtract(BigDecimal.ONE), reserve.source()));
            var mismatch = assertThrows(IllegalArgumentException.class,
                    () -> first.verifiedFinancialBank(bank, accounts));
            assertTrue(mismatch.getMessage().contains("RESERVE_GL_MISMATCH"));
        }
    }

    @Test
    void warmReadRejectsInvalidBankOrCustodyAndKeepsTheVerifiedEntry() throws Exception {
        try (var world = BankWorldFixture.open(dir.resolve("bank.journal"))) {
            var bank = world.bankOf().orElseThrow();
            var accounts = world.snapshot().accounts();
            var good = world.verifiedFinancialBank(bank, accounts);
            var badBank = new BankRun(bank.runId(), bank.configFingerprint(), bank.profileFingerprint(),
                    bank.configJson(), "{}", bank.custodyJson());
            var badCustody = new BankRun(bank.runId(), bank.configFingerprint(), bank.profileFingerprint(),
                    bank.configJson(), bank.stateJson(), "{}");
            var badConfig = new BankRun(bank.runId(), bank.configFingerprint(), bank.profileFingerprint(),
                    "{}", bank.stateJson(), bank.custodyJson());
            for (var bad : java.util.List.of(badBank, badCustody, badConfig)) {
                var expected = assertThrows(RuntimeException.class, () -> bad.decode(accounts));
                var actual = assertThrows(expected.getClass(), () -> world.verifiedFinancialBank(bad, accounts));
                assertEquals(expected.getMessage(), actual.getMessage());
                assertSame(good, world.verifiedFinancialBank(bank, accounts));
            }
        }
    }
}
