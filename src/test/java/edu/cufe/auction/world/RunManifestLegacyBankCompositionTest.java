package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 银行三档与组合档的模块集合契约（永久回归）。
 *
 * <p>银行 world / roles / funds 是三条互相独立的旧契约分支，不能混成一条派生链：</p>
 * <ul>
 *   <li>{@code banking-world-experiment-v1}：只有 cash/scheduler/registry/banking/custody；</li>
 *   <li>{@code banking-roles-experiment-v1}：在银行之上只加 roles/information；</li>
 *   <li>{@code banking-funds-experiment-v1}：在银行角色之上只加 securities/matching/funding。</li>
 * </ul>
 *
 * <p>三者都不带 manufacturing/governance/trading，也不带组合档的群组/工程/媒体模块；纯银行运行
 * 不要求制造开局配置。{@code composed-v1}（27 模块）与 {@code composed-v2}（28 模块，多
 * {@code command-intent}）由 withGroups 链整体拥有完整公司模块，集合必须逐字保持原样。</p>
 *
 * <p>校验是精确相等，不接受任意子集或超集：把银行档少写一个模块（例如缺 custody）或按旧的错误
 * 模块表多补 manufacturing/governance，都必须在构造期被拒绝。</p>
 */
class RunManifestLegacyBankCompositionTest {

    private static Map<String, Integer> modules(String... names) {
        Map<String, Integer> map = new TreeMap<>();
        for (String name : names) {
            map.put(name, 1);
        }
        return map;
    }

    private static RunManifest bank(String rulesVersion, Map<String, Integer> modules) {
        return bank(rulesVersion, modules, RunManifest.MODEL_NONE);
    }

    private static RunManifest bank(String rulesVersion, Map<String, Integer> modules,
                                    String modelConfigVersion) {
        return new RunManifest("manifest-contract", 7L, rulesVersion, modules, Map.of(),
                modelConfigVersion);
    }

    @Test
    void bankingWorldKeepsTheFiveModuleContract() {
        Map<String, Integer> expected = modules("cash", "scheduler", "registry", "banking",
                "custody");
        RunManifest manifest = bank(RunManifest.RULES_BANKING_WORLD_EXPERIMENT_V1, expected);
        assertEquals(expected, manifest.moduleVersions(), "银行 world 档只能是五模块");
        assertTrue(manifest.hasBank());
        assertTrue(manifest.hasCustody());
        assertFalse(manifest.hasRoles(), "银行 world 档没有任职");
        assertFalse(manifest.hasInformation());
        assertFalse(manifest.hasBankRoles(), "银行 world 档不是银行角色档");
        assertFalse(manifest.hasManufacturing(), "纯银行不携带制造");
        assertFalse(manifest.hasGovernance(), "纯银行不携带治理");
        assertFalse(manifest.hasMatching(), "纯银行不携带撮合");
        assertFalse(manifest.hasSecurities(), "纯银行不携带证券台账");
        assertFalse(manifest.hasFunding(), "银行 world 档没有资金桶");
    }

    @Test
    void bankingRolesAddsOnlyRolesAndInformation() {
        Map<String, Integer> expected = modules("cash", "scheduler", "registry", "banking",
                "custody", "roles", "information");
        RunManifest manifest = bank(RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1, expected);
        assertEquals(expected, manifest.moduleVersions(), "银行角色档只能是七模块");
        assertTrue(manifest.hasBankRoles());
        assertTrue(manifest.hasRoles());
        assertTrue(manifest.hasInformation());
        assertFalse(manifest.hasManufacturing(), "银行人物动作不携带制造经营");
        assertFalse(manifest.hasGovernance(), "银行人物动作不携带董事会治理");
        assertFalse(manifest.hasMatching(), "银行人物动作不携带撮合");
        assertFalse(manifest.hasSecurities());
        assertFalse(manifest.hasFunding());
        assertFalse(manifest.hasGroups());
        assertFalse(manifest.hasEngineering());
    }

    @Test
    void bankingFundsAddsOnlySecuritiesMatchingFunding() {
        Map<String, Integer> expected = modules("cash", "scheduler", "registry", "banking",
                "custody", "roles", "information", "securities", "matching", "funding");
        RunManifest manifest = bank(RunManifest.RULES_BANKING_FUNDS_EXPERIMENT_V1, expected);
        assertEquals(expected, manifest.moduleVersions(), "资金桶档只能是十模块");
        assertTrue(manifest.hasFunding());
        assertTrue(manifest.hasSecurities());
        assertTrue(manifest.hasMatching());
        assertTrue(manifest.hasRoles());
        assertTrue(manifest.hasInformation());
        assertFalse(manifest.hasManufacturing(), "资金桶不携带制造经营");
        assertFalse(manifest.hasGovernance(), "资金桶不携带董事会治理");
        assertFalse(manifest.hasTrading(), "资金桶不携带四岗交易");
    }

    @Test
    void composedProfileKeepsCompleteModuleTable() {
        Map<String, Integer> composed = modules("cash", "scheduler", "registry", "securities",
                "matching", "manufacturing", "governance", "roles", "information", "trading",
                "decisions", "dividend", "live-decisions", "cognition-recall", "belief", "media",
                "interview", "dialogue", "evidence", "social", "engineering", "groups",
                "publication", "banking", "custody", "funding", "company-governance");
        RunManifest v1 = bank(RunManifest.RULES_COMPOSED_BANK_WORLD_V1, composed,
                RunManifest.LIVE_DECISIONS_V1);
        assertEquals(composed, v1.moduleVersions(), "composed-v1 的 27 模块表必须逐字不变");
        assertTrue(v1.hasGovernance());
        assertTrue(v1.hasManufacturing());
        assertTrue(v1.hasGroups());
        assertTrue(v1.hasEngineering());
        assertTrue(v1.hasFunding());
        assertTrue(v1.hasCompanyGovernance());

        Map<String, Integer> v2Modules = new TreeMap<>(composed);
        v2Modules.put("command-intent", 1);
        RunManifest v2 = bank(RunManifest.RULES_COMPOSED_BANK_WORLD_V2, v2Modules,
                RunManifest.LIVE_DECISIONS_V1);
        assertEquals(v2Modules, v2.moduleVersions(), "composed-v2 只多 command-intent");
        assertTrue(v2.hasCommandIntent());
        assertTrue(v2.hasDefaultPeriodicGovernance());
        assertFalse(v1.hasCommandIntent(), "旧组合档不得凭空获得命令意图模块");
    }

    @Test
    void legacyBankProfilesRejectSupersetsAndSubsets() {
        Map<String, Integer> rolesSubset = modules("cash", "scheduler", "registry", "banking",
                "custody", "roles");
        assertThrows(IllegalArgumentException.class, () -> bank(
                        RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1, rolesSubset),
                "银行角色档少 information 必须被拒，不能接受子集");

        Map<String, Integer> rolesSuperset = modules("cash", "scheduler", "registry", "banking",
                "custody", "roles", "information", "manufacturing", "governance");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> bank(RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1, rolesSuperset),
                "银行角色档多补制造/治理必须被拒，不能接受超集");
        assertTrue(failure.getMessage().contains("需要模块"),
                "拒绝信息必须给出精确模块表，实际：" + failure.getMessage());

        Map<String, Integer> fundsSuperset = modules("cash", "scheduler", "registry", "banking",
                "custody", "roles", "information", "securities", "matching", "funding",
                "manufacturing");
        assertThrows(IllegalArgumentException.class, () -> bank(
                        RunManifest.RULES_BANKING_FUNDS_EXPERIMENT_V1, fundsSuperset),
                "资金桶档多补制造必须被拒，不能接受超集");
    }
}
