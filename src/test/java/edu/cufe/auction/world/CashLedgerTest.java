package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CashLedgerTest {
    @TempDir Path directory;

    @Test
    void transfersConserveCashAndDuplicateCommandsDoNotPayTwice() throws Exception {
        try (CashLedger ledger = open("cash")) {
            CashLedger.Snapshot before = ledger.snapshot();
            CashLedger.TransferResult first = ledger.transfer("pay", "alice", "A", "B", money("100"));
            assertTrue(first.accepted());
            assertBalances(ledger, "900.00", "100.00");
            assertEquals(first, ledger.transfer("pay", "alice", "A", "B", money("100.00")));
            assertBalances(ledger, "900.00", "100.00");
            assertEquals(money("1000.00"), before.accounts().get("A").cash());
            assertThrows(UnsupportedOperationException.class, () -> before.accounts().clear());
            assertThrows(IllegalArgumentException.class,
                    () -> ledger.transfer("pay", "alice", "A", "B", money("200")));
        }
    }

    @Test
    void restartRestoresInsteadOfReissuingEndowments() throws Exception {
        try (CashLedger ledger = open("cash")) {
            ledger.transfer("pay", "alice", "A", "B", money("100"));
        }
        try (CashLedger ledger = open("cash")) {
            assertBalances(ledger, "900.00", "100.00");
            assertEquals(2, ledger.snapshot().position());
            ledger.transfer("pay", "alice", "A", "B", money("100"));
            assertEquals(2, ledger.snapshot().position());
        }
        assertThrows(IOException.class, () -> new CashLedger(file("cash"), "run",
                List.of(new CashLedger.OpeningAccount("A", "alice", money("9999"), "历史禀赋"))));
        try (CashLedger ledger = open("cash")) {
            assertBalances(ledger, "900.00", "100.00");
        }
    }

    @Test
    void rejectedCommandKeepsItsResultAfterFundsArrive() throws Exception {
        try (CashLedger ledger = open("cash")) {
            CashLedger.TransferResult rejected = ledger.transfer("early", "bob", "B", "A", money("100"));
            assertFalse(rejected.accepted());
            assertBalances(ledger, "1000.00", "0.00");
            ledger.transfer("fund", "alice", "A", "B", money("200"));
            assertEquals(rejected, ledger.transfer("early", "bob", "B", "A", money("100")));
            assertBalances(ledger, "800.00", "200.00");
        }
        try (CashLedger ledger = open("cash")) {
            assertFalse(ledger.transfer("early", "bob", "B", "A", money("100")).accepted());
            assertBalances(ledger, "800.00", "200.00");
        }
    }

    @Test
    void unauthorizedAndUnknownAccountsShareNonLeakingRejection() throws Exception {
        try (CashLedger ledger = open("cash")) {
            CashLedger.TransferResult unauthorized = ledger.transfer("bad1", "mallory", "A", "B", money("1"));
            CashLedger.TransferResult unknown = ledger.transfer("bad2", "mallory", "hidden", "B", money("1"));
            assertFalse(unauthorized.accepted());
            assertEquals(unauthorized.reason(), unknown.reason());
            assertFalse(ledger.transfer("bad3", "alice", "A", "missing", money("1")).accepted());
            assertBalances(ledger, "1000.00", "0.00");
        }
    }

    @Test
    void invalidAmountsAndClosedJournalCannotChangeBalances() throws Exception {
        CashLedger ledger = open("cash");
        try {
            for (String amount : List.of("0", "-1", "0.001")) {
                assertThrows(IllegalArgumentException.class,
                        () -> ledger.transfer("invalid-" + amount, "alice", "A", "B", money(amount)));
            }
            assertEquals(1, ledger.snapshot().position());
            ledger.close();
            CashLedger.Snapshot before = ledger.snapshot();
            assertThrows(IOException.class,
                    () -> ledger.transfer("closed", "alice", "A", "B", money("100")));
            assertEquals(before, ledger.snapshot());
        } finally {
            ledger.close();
        }
    }

    @Test
    void committedButUnappliedTransferIsRecoveredWithoutAnotherPayment() throws Exception {
        try (CashLedger donor = open("donor"); CashLedger target = open("target")) {
            donor.transfer("pay", "alice", "A", "B", money("100"));
        }
        // 将已确认批次写入目标日志，但不应用到内存，模拟持久提交后进程退出。
        try (WorldJournal donor = new WorldJournal(file("donor"), "run");
             WorldJournal target = new WorldJournal(file("target"), "run")) {
            WorldCommit payment = donor.commits().get(1);
            target.commit(payment.commandId(), payment.commandContent(), payment.result(), payment.events());
        }
        try (CashLedger restored = open("target")) {
            assertBalances(restored, "900.00", "100.00");
            restored.transfer("pay", "alice", "A", "B", money("100"));
            assertBalances(restored, "900.00", "100.00");
            assertEquals(2, restored.snapshot().position());
        }
    }

    @Test
    void duplicateOpeningAccountsCannotCreateCapital() {
        CashLedger.OpeningAccount account = openings().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> new CashLedger(file("bad"), "run", List.of(account, account)));
    }

    @Test
    void missingAccountIdentifiersAreBusinessRejectionsWithoutEffects() throws Exception {
        try (CashLedger ledger = open("cash")) {
            assertFalse(ledger.transfer("no-from", "alice", null, "B", money("1")).accepted());
            assertFalse(ledger.transfer("no-to", "alice", "A", null, money("1")).accepted());
            assertFalse(ledger.transfer("same", "alice", "A", "A", money("1")).accepted());
            assertBalances(ledger, "1000.00", "0.00");
        }
    }

    @Test
    void interruptedDiskWriteDoesNotPublishCandidate() throws Exception {
        try (CashLedger ledger = open("cash")) {
            CashLedger.Snapshot before = ledger.snapshot();
            try {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class,
                        () -> ledger.transfer("interrupt", "alice", "A", "B", money("100")));
            } finally {
                Thread.interrupted();
            }
            assertEquals(before, ledger.snapshot());
            assertThrows(IOException.class,
                    () -> ledger.transfer("after-failure", "alice", "A", "B", money("100")));
        }
        try (CashLedger restored = open("cash")) {
            assertBalances(restored, "1000.00", "0.00");
        }
    }

    @Test
    void tornTransferBatchRestoresBothSidesThenRetryPaysOnlyOnce() throws Exception {
        try (CashLedger ledger = open("cash")) {
            ledger.transfer("pay", "alice", "A", "B", money("100"));
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(file("cash"));
        java.nio.file.Files.write(file("cash"), java.util.Arrays.copyOf(bytes, bytes.length - 20));
        try (CashLedger ledger = open("cash")) {
            assertBalances(ledger, "1000.00", "0.00");
            assertEquals(1, ledger.snapshot().position());
            ledger.transfer("pay", "alice", "A", "B", money("100"));
            ledger.transfer("pay", "alice", "A", "B", money("100"));
            assertBalances(ledger, "900.00", "100.00");
            assertEquals(2, ledger.snapshot().position());
        }
    }

    private CashLedger open(String name) throws IOException {
        return new CashLedger(file(name), "run", openings());
    }

    private Path file(String name) {
        return directory.resolve(name + ".journal");
    }

    private List<CashLedger.OpeningAccount> openings() {
        return List.of(new CashLedger.OpeningAccount("A", "alice", money("1000"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("B", "bob", money("0"), "开局历史禀赋"));
    }

    private void assertBalances(CashLedger ledger, String a, String b) {
        CashLedger.Snapshot snapshot = ledger.snapshot();
        assertEquals(money(a), snapshot.accounts().get("A").cash());
        assertEquals(money(b), snapshot.accounts().get("B").cash());
        assertEquals(money("1000.00"), snapshot.accounts().get("A").cash().add(snapshot.accounts().get("B").cash()));
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
