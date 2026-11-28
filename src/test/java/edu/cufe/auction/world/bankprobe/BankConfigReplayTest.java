package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankPersistence;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class BankConfigReplayTest {
    @Test void alternatingWorldsReadTheirOwnFrozenConfiguration() throws Exception {
        var one = new BankConfig.Builder().label("甲").build();
        var two = new BankConfig.Builder().label("乙").build();
        String first = BankPersistence.writeConfig(one);
        String second = BankPersistence.writeConfig(two);
        assertNotEquals(one.fingerprint(), two.fingerprint());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var checks = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                String raw = i % 2 == 0 ? first : second;
                String expected = i % 2 == 0 ? one.fingerprint() : two.fingerprint();
                checks.add(pool.submit(() -> {
                    var read = BankPersistence.readConfig(raw);
                    assertEquals(expected, read.fingerprint());
                    assertEquals(raw, BankPersistence.writeConfig(read));
                }));
            }
            for (var completed : checks) completed.get();
        } finally {
            pool.shutdownNow();
        }
        assertThrows(IllegalArgumentException.class, () -> BankPersistence.readConfig("{}"));
        assertEquals(one.fingerprint(), BankPersistence.readConfig(first).fingerprint());
    }
}
