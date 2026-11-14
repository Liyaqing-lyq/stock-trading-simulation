package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.RoleAccess.Action;
import edu.cufe.auction.world.RoleAccess.Decision;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleAccess.State;
import edu.cufe.auction.world.RoleAccess.Target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 公司经理岗位：最小动作集、跨公司隔离、撤销与重叠。 */
class RoleAccessManagerTest {

    private static final String ORG = "org-company";
    private static final String OTHER = "org-other";

    @Test
    @DisplayName("经理四动作允许；交易/研究/媒体/验收类动作拒绝")
    void managerPermissions() {
        State state = appointed();
        for (Action action : List.of(Action.READ_COMPANY_LEDGER,
                Action.REQUEST_COMPANY_OPERATIONS, Action.APPROVE_SUPPLIER_PAYMENT,
                Action.REQUEST_IMPAIRMENT_OR_WRITEOFF)) {
            assertEquals(Decision.ALLOW, decide(state, "p1", action, 200));
        }
        for (Action action : List.of(Action.EXECUTE_ORDER, Action.WRITE_RESEARCH,
                Action.WRITE_STORY, Action.READ_PRODUCT_ACCOUNT)) {
            assertEquals(Decision.NOT_AUTHORIZED, decide(state, "p1", action, 200));
        }
    }

    @Test
    @DisplayName("过期/撤销后拒绝；跨公司目标拒绝；旧任职历史保留")
    void revokedAndExpired() {
        State state = appointed();
        assertEquals(Decision.ALLOW, decide(state, "p1", Action.READ_COMPANY_LEDGER, 200));
        state = RoleAccess.revoke(state, "m1", 500);
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, "p1", Action.READ_COMPANY_LEDGER, 501));
        assertEquals("m1", state.appointments().get(0).id());
        assertEquals(500L, state.appointments().get(0).revokedAt());
        assertEquals(Decision.NOT_AUTHORIZED,
                RoleAccess.authorize(state, "p1", Action.READ_COMPANY_LEDGER,
                        Target.organization(OTHER), 200));
    }

    @Test
    @DisplayName("同机构同岗位不同人不得重叠；合法撤销后新人可接任；同 ID 不得复用")
    void overlapAndSuccession() {
        State state = appointed();
        final State base = state;
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(registry(), base,
                "m2", "p2", ORG, Role.COMPANY_MANAGER, 200, 900, 150));
        state = RoleAccess.revoke(state, "m1", 500);
        State successor = RoleAccess.appoint(registry(), state, "m2", "p2", ORG,
                Role.COMPANY_MANAGER, 600, 1000, 500);
        assertEquals(Decision.ALLOW, decide(successor, "p2", Action.READ_COMPANY_LEDGER, 700));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(successor, "p1", Action.READ_COMPANY_LEDGER, 700));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(registry(),
                successor, "m1", "p3", ORG, Role.COMPANY_MANAGER, 1100, 1200, 700));
        State cancelled = RoleAccess.revoke(successor, "m2", 800);
        assertEquals(800L, cancelled.appointments().get(1).revokedAt());
        assertEquals(500L, cancelled.appointments().get(0).revokedAt());
    }

    private static State appointed() {
        State state = RoleAccess.open(registry(), List.of(
                new OpeningAccount("CO", ORG, new BigDecimal("1000.00"), "开局")), List.of());
        return RoleAccess.appoint(registry(), state, "m1", "p1", ORG,
                Role.COMPANY_MANAGER, 100, 1000, 0);
    }

    private static Decision decide(State state, String person, Action action, long now) {
        return RoleAccess.authorize(state, person, action, Target.organization(ORG), now);
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new Party("p1", PartyKind.PERSON, "甲"),
                new Party("p2", PartyKind.PERSON, "乙"),
                new Party("p3", PartyKind.PERSON, "丙"),
                new Party(ORG, PartyKind.ORGANIZATION, "实验公司"),
                new Party(OTHER, PartyKind.ORGANIZATION, "另一组织")),
                List.of(new PropertyOwner(ORG, OwnerKind.ORGANIZATION, ORG)),
                List.of(), List.of());
    }
}
