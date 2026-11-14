package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.RunManifest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 银行独立档案的模块集合回归：银行世界 / 银行任职 / B7a 资金桶 / B7b 托管成交各自精确冻结，
 * 不因组合档或派生顺序被扩成制造、治理或四岗交易。
 *
 * <p>这些集合来自已接受的旧契约；本类只核对构造期精确集合，不重跑银行 3 全套业务。</p>
 */
class BankModuleSetRegressionTest {

    @Test
    void bankingWorldHasOnlyBankAndCustody() {
        RunManifest manifest = new RunManifest("bank-world-check", 11L,
                RunManifest.RULES_BANKING_WORLD_EXPERIMENT_V1,
                modules("banking", "custody"), Map.of(), RunManifest.MODEL_NONE);
        assertEquals(modules("cash", "scheduler", "registry", "banking", "custody"),
                manifest.moduleVersions());
        assertFalse(manifest.hasRoles());
        assertFalse(manifest.hasManufacturing());
        assertFalse(manifest.hasGovernance());
    }

    @Test
    void bankingRolesAddsRolesAndInformationOnly() {
        RunManifest manifest = new RunManifest("bank-roles-check", 11L,
                RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1,
                modules("banking", "custody", "roles", "information"), Map.of(),
                RunManifest.MODEL_NONE);
        assertEquals(modules("cash", "scheduler", "registry", "banking", "custody", "roles",
                "information"), manifest.moduleVersions());
        assertFalse(manifest.hasFunding());
        assertFalse(manifest.hasManufacturing());
        assertFalse(manifest.hasGovernance());
    }

    @Test
    void bankingFundsAddsSecuritiesMatchingFundingOnly() {
        RunManifest manifest = new RunManifest("bank-funds-check", 11L,
                RunManifest.RULES_BANKING_FUNDS_EXPERIMENT_V1,
                modules("banking", "custody", "roles", "information", "securities", "matching",
                        "funding"), Map.of(), RunManifest.MODEL_NONE);
        assertEquals(modules("cash", "scheduler", "registry", "banking", "custody", "roles",
                "information", "securities", "matching", "funding"), manifest.moduleVersions());
        assertTrue(manifest.hasFunding());
        assertFalse(manifest.hasCustodyFill());
        assertFalse(manifest.hasManufacturing());
        assertFalse(manifest.hasGovernance());
    }

    @Test
    void bankingCustodyIsExactlyFundsPlusCustodyFill() {
        RunManifest manifest = new RunManifest("bank-custody-check", 11L,
                RunManifest.RULES_BANKING_CUSTODY_EXPERIMENT_V1,
                modules("banking", "custody", "roles", "information", "securities", "matching",
                        "funding", "custody-fill"), Map.of(), RunManifest.MODEL_NONE);
        assertEquals(modules("cash", "scheduler", "registry", "banking", "custody", "roles",
                "information", "securities", "matching", "funding", "custody-fill"),
                manifest.moduleVersions());
        assertTrue(manifest.hasCustodyFill());
        assertFalse(manifest.hasManufacturing());
        assertFalse(manifest.hasGovernance());
    }

    private static Map<String, Integer> modules(String... names) {
        Map<String, Integer> map = new TreeMap<>();
        map.put("cash", 1);
        map.put("scheduler", 1);
        map.put("registry", 1);
        for (String name : names) {
            map.put(name, 1);
        }
        return map;
    }
}
