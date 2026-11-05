package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.RoleAccess.AccountBinding;
import edu.cufe.auction.world.RoleAccess.Action;
import edu.cufe.auction.world.RoleAccess.Appointment;
import edu.cufe.auction.world.RoleAccess.Decision;
import edu.cufe.auction.world.RoleAccess.PersonAccount;
import edu.cufe.auction.world.RoleAccess.ProductMandate;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleAccess.State;
import edu.cufe.auction.world.RoleAccess.Target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4a.1：任职与四岗权限纯核测试。
 *
 * <p>覆盖施工单的六组验收：错误开局拒绝、四岗能力矩阵、任职区间与撤职边界、
 * 跨产品/跨机构/雇主自有账户/未列账户隔离、重叠与继任规则、深不可变与 JSON 确定。</p>
 *
 * <p>本类只验证确定性判定，不验证 LLM 角色已运行、不验证世界日志或工具层执行；
 * 所有时刻都是显式传入的模拟毫秒，不读取本机时钟。</p>
 */
@DisplayName("RoleAccess：任职与四岗权限纯核")
class RoleAccessTest {

    private static final String FUND = "ORG-FUND";
    private static final String OTHER = "ORG-OTHER";

    private static final String RES = "P-RES";
    private static final String PM = "P-PM";
    private static final String RISK = "P-RISK";
    private static final String TRADER = "P-TRADER";
    private static final String NEXT = "P-NEXT";
    private static final String OUTSIDER = "P-OUTSIDER";

    private static final String OWN_ORG_FUND = "OWN-ORG-FUND";
    private static final String OWN_ORG_OTHER = "OWN-ORG-OTHER";
    private static final String PROD_A = "OWN-PROD-A";
    private static final String PROD_B = "OWN-PROD-B";
    private static final String OWN_PERSON_RES = "OWN-PERSON-RES";
    private static final String OWN_PERSON_PM = "OWN-PERSON-PM";
    private static final String OWN_PERSON_OUTSIDER = "OWN-PERSON-OUTSIDER";

    private static final String ACC_A1 = "ACC-PROD-A-1";
    private static final String ACC_A2 = "ACC-PROD-A-2";
    private static final String ACC_A3 = "ACC-PROD-A-3";
    private static final String ACC_B = "ACC-PROD-B-1";
    private static final String ACC_ORG = "ACC-ORG-FUND";
    private static final String ACC_PERSON_RES = "ACC-PERSON-RES";
    private static final String ACC_PERSON_PM = "ACC-PERSON-PM";
    private static final String ACC_PERSON_OUT = "ACC-PERSON-OUTSIDER";
    private static final String ACC_PERSON_RES_2 = "ACC-PERSON-RES-2";

    private static final String CASH_UNIQUE_A = "1234567.89";

    private static final OpeningRegistry REGISTRY = registry();
    private static final List<OpeningAccount> CASH = cashOpenings();
    private static final List<ProductMandate> MANDATES = List.of(
            new ProductMandate(FUND, PROD_A, List.of(ACC_A1, ACC_A2)),
            new ProductMandate(OTHER, PROD_B, List.of(ACC_B)));

    // ---------------------------------------------------------------- 夹具

    private static OpeningRegistry registry() {
        List<Party> parties = List.of(
                new Party(FUND, PartyKind.ORGANIZATION, "实验基金公司"),
                new Party(OTHER, PartyKind.ORGANIZATION, "另一家基金公司"),
                new Party(RES, PartyKind.PERSON, "研究员"),
                new Party(PM, PartyKind.PERSON, "基金经理"),
                new Party(RISK, PartyKind.PERSON, "风控"),
                new Party(TRADER, PartyKind.PERSON, "执行交易员"),
                new Party(NEXT, PartyKind.PERSON, "继任研究员"),
                new Party(OUTSIDER, PartyKind.PERSON, "别机构人物"));
        List<PropertyOwner> owners = List.of(
                new PropertyOwner(OWN_ORG_FUND, OpeningRegistry.OwnerKind.ORGANIZATION, FUND),
                new PropertyOwner(OWN_ORG_OTHER, OpeningRegistry.OwnerKind.ORGANIZATION, OTHER),
                new PropertyOwner(PROD_A, OpeningRegistry.OwnerKind.PRODUCT, null),
                new PropertyOwner(PROD_B, OpeningRegistry.OwnerKind.PRODUCT, null),
                new PropertyOwner(OWN_PERSON_RES, OpeningRegistry.OwnerKind.PERSON, RES),
                new PropertyOwner(OWN_PERSON_PM, OpeningRegistry.OwnerKind.PERSON, PM),
                new PropertyOwner(OWN_PERSON_OUTSIDER, OpeningRegistry.OwnerKind.PERSON, OUTSIDER));
        return new OpeningRegistry(parties, owners, List.of(), List.of());
    }

    private static List<OpeningAccount> cashOpenings() {
        return List.of(
                new OpeningAccount(ACC_A1, PROD_A, new BigDecimal(CASH_UNIQUE_A), "历史禀赋"),
                new OpeningAccount(ACC_A2, PROD_A, new BigDecimal("250000.00"), "历史禀赋"),
                new OpeningAccount(ACC_A3, PROD_A, new BigDecimal("90000.00"), "历史禀赋"),
                new OpeningAccount(ACC_B, PROD_B, new BigDecimal("400000.00"), "历史禀赋"),
                new OpeningAccount(ACC_ORG, OWN_ORG_FUND, new BigDecimal("800000.00"), "公司自有"),
                new OpeningAccount(ACC_PERSON_RES, OWN_PERSON_RES, new BigDecimal("5000.00"), "个人"),
                new OpeningAccount(ACC_PERSON_PM, OWN_PERSON_PM, new BigDecimal("6000.00"), "个人"),
                new OpeningAccount(ACC_PERSON_OUT, OWN_PERSON_OUTSIDER, new BigDecimal("7000.00"), "个人"));
    }

    private static State seated() {
        State state = RoleAccess.open(REGISTRY, CASH, MANDATES);
        state = RoleAccess.appoint(REGISTRY, state, "APT-RES", RES, FUND, Role.RESEARCHER, 1000L, 5000L, 1000L);
        state = RoleAccess.appoint(REGISTRY, state, "APT-PM", PM, FUND, Role.PORTFOLIO_MANAGER, 1000L, 5000L, 1000L);
        state = RoleAccess.appoint(REGISTRY, state, "APT-RISK", RISK, FUND, Role.RISK_OFFICER, 1000L, 5000L, 1000L);
        state = RoleAccess.appoint(REGISTRY, state, "APT-TRADER", TRADER, FUND, Role.EXECUTION_TRADER, 1000L, 5000L, 1000L);
        return state;
    }

    private static Appointment appointment(State state, String id) {
        for (Appointment appointment : state.appointments()) {
            if (appointment.id().equals(id)) {
                return appointment;
            }
        }
        return null;
    }

    private static Target targetFor(Action action) {
        switch (action) {
            case READ_PRODUCT_ACCOUNT:
            case EXECUTE_ORDER:
            case CANCEL_ORDER:
                return Target.account(FUND, PROD_A, ACC_A1);
            case SET_PORTFOLIO_TARGET:
            case ISSUE_RISK_HALT:
                return Target.product(FUND, PROD_A);
            default:
                return Target.organization(FUND);
        }
    }

    private static Set<Action> allowed(Role role) {
        Set<Action> actions = EnumSet.of(Action.READ_PRODUCT_ACCOUNT, Action.READ_ORGANIZATION_FILE);
        switch (role) {
            case RESEARCHER:
                actions.add(Action.WRITE_RESEARCH);
                break;
            case PORTFOLIO_MANAGER:
                actions.add(Action.SET_PORTFOLIO_TARGET);
                break;
            case RISK_OFFICER:
                actions.add(Action.ISSUE_RISK_HALT);
                break;
            case EXECUTION_TRADER:
                actions.add(Action.EXECUTE_ORDER);
                actions.add(Action.CANCEL_ORDER);
                break;
            default:
                throw new IllegalStateException("未知角色：" + role);
        }
        return actions;
    }

    private static Decision decide(State state, String person, Action action, Target target, long now) {
        return RoleAccess.authorize(state, person, action, target, now);
    }

    // ---------------------------------------------------------------- 1. 开局

    @Test
    @DisplayName("错误开局全部拒绝：账户归属、账户存在性、组织与产品种类、一产品一管理组织")
    void openingRejectsWrongIdentitiesAccountsAndMandates() {
        assertEquals(8, RoleAccess.open(REGISTRY, CASH, MANDATES).accounts().size(),
                "账户引用表只登记绑定，不复制现金");

        // 账户不在开局清单
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of("ACC-NOT-OPEN")))));
        // 账户属于另一个产品
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of(ACC_B)))));
        // 把雇主自有账户塞进产品授权
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of(ACC_ORG)))));
        // 把个人账户塞进产品授权
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of(ACC_PERSON_RES)))));
        // 管理组织必须是已登记组织，不能是人物标识
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(RES, PROD_A, List.of(ACC_A1)))));
        // 未登记组织
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate("ORG-NOPE", PROD_A, List.of(ACC_A1)))));
        // 产品主体必须是 PRODUCT，不能拿公司自有财产主体冒充产品
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, OWN_ORG_FUND, List.of(ACC_ORG)))));
        // 未登记产品主体
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, "OWN-PROD-NOPE", List.of(ACC_A1)))));
        // 一产品只允许一个管理组织
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH, List.of(
                new ProductMandate(FUND, PROD_A, List.of(ACC_A1)),
                new ProductMandate(OTHER, PROD_A, List.of(ACC_A2)))));
        // 空账户清单不能靠 owner 通配
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of()))));
        // 同一 mandate 内重复账户
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of(ACC_A1, ACC_A1)))));
        // 开局账户重复
        List<OpeningAccount> duplicated = new ArrayList<>(CASH);
        duplicated.add(new OpeningAccount(ACC_A1, PROD_A, new BigDecimal("1.00"), "重复"));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, duplicated, MANDATES));
        // 开局账户挂了未登记主体
        List<OpeningAccount> unknownOwner = new ArrayList<>(CASH);
        unknownOwner.add(new OpeningAccount("ACC-UNKNOWN-OWNER", "OWN-NOPE", new BigDecimal("1.00"), "来源"));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.open(REGISTRY, unknownOwner, MANDATES));

        // 产品财产主体不能伪造人物/组织映射；公司财产主体与组织 partyId 是两件事
        assertThrows(IllegalArgumentException.class,
                () -> new PropertyOwner(PROD_A, OpeningRegistry.OwnerKind.PRODUCT, FUND));
        assertNull(REGISTRY.owner(PROD_A).partyId());
        assertEquals(FUND, REGISTRY.owner(OWN_ORG_FUND).partyId());
        assertNotEquals(REGISTRY.owner(PROD_A).ownerId(), REGISTRY.owner(OWN_ORG_FUND).partyId());
    }

    // ---------------------------------------------------------------- 2. 能力矩阵

    @Test
    @DisplayName("四个独立人物的能力矩阵：读权共享，专属动作互不越界")
    void fourRolesHaveExactCapabilityMatrix() {
        State state = seated();
        assertEquals(4, state.appointments().stream().map(Appointment::personPartyId).distinct().count(),
                "四岗必须是四个独立人物");

        assertMatrix(state, RES, Role.RESEARCHER);
        assertMatrix(state, PM, Role.PORTFOLIO_MANAGER);
        assertMatrix(state, RISK, Role.RISK_OFFICER);
        assertMatrix(state, TRADER, Role.EXECUTION_TRADER);
    }

    private static void assertMatrix(State state, String person, Role role) {
        Set<Action> allow = allowed(role);
        for (Action action : Action.values()) {
            Decision expected = allow.contains(action) ? Decision.ALLOW : Decision.NOT_AUTHORIZED;
            assertEquals(expected, decide(state, person, action, targetFor(action), 1000L),
                    role + " 对 " + action + " 的判定");
        }
    }

    @Test
    @DisplayName("三个越权动作明确拒绝：经理下单、研究员改目标、交易员发风控指令")
    void managerResearcherTraderCannotCrossDuties() {
        State state = seated();
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, PM, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A1), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, PM, Action.CANCEL_ORDER, Target.account(FUND, PROD_A, ACC_A1), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.SET_PORTFOLIO_TARGET, Target.product(FUND, PROD_A), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.ISSUE_RISK_HALT, Target.product(FUND, PROD_A), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A1), 1000L));
        // 交易员不能把自己的动作升级成改目标或写研究
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.SET_PORTFOLIO_TARGET, Target.product(FUND, PROD_A), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.WRITE_RESEARCH, Target.organization(FUND), 1000L));
    }

    // ---------------------------------------------------------------- 3. 区间与撤职

    @Test
    @DisplayName("任职区间左闭右开：startsAt 当刻可用，endsAt 当刻已失效")
    void appointmentWindowIsHalfOpen() {
        State state = seated();
        assertEquals(Decision.ALLOW, decide(state, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 1000L));
        assertEquals(Decision.ALLOW, decide(state, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 4999L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 5000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 999L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 6000L));
    }

    @Test
    @DisplayName("撤职当刻失去访问，原任职记录与期限保留，拒绝重复撤销与历史 ID 复用")
    void revocationIsImmediateAndKeepsHistory() {
        State state = seated();
        Appointment before = appointment(state, "APT-TRADER");
        assertNotNull(before);

        State revoked = RoleAccess.revoke(state, "APT-TRADER", 2000L);

        assertEquals(Decision.ALLOW,
                decide(revoked, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A1), 1999L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(revoked, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A1), 2000L));
        // 同一个人仍能做的只剩无任职支持的事
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(revoked, TRADER, Action.READ_ORGANIZATION_FILE, Target.organization(FUND), 2000L));

        Appointment after = appointment(revoked, "APT-TRADER");
        assertNotNull(after, "撤职不删除任职");
        assertEquals(before.personPartyId(), after.personPartyId());
        assertEquals(before.organizationPartyId(), after.organizationPartyId());
        assertEquals(before.role(), after.role());
        assertEquals(before.startsAt(), after.startsAt());
        assertEquals(before.endsAt(), after.endsAt());
        assertEquals(2000L, after.revokedAt());

        // 重复撤销、历史 ID 复用、提前伪造离任都明确拒绝
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(revoked, "APT-TRADER", 3000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, revoked, "APT-TRADER", NEXT, FUND, Role.EXECUTION_TRADER, 3000L, 4000L, 2500L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(revoked, "APT-NOT-THERE", 3000L));
    }

    @Test
    @DisplayName("撤职当刻可由新人继任，前任新人都只拿到授权目录而非前任经历")
    void successorStartsAtRevocationMoment() {
        State state = seated();
        State revoked = RoleAccess.revoke(state, "APT-RES", 2000L);

        State both = RoleAccess.appoint(REGISTRY, revoked, "APT-RES-2", NEXT, FUND,
                Role.RESEARCHER, 2000L, 5000L, 2000L);

        assertEquals(Decision.ALLOW,
                decide(both, NEXT, Action.WRITE_RESEARCH, Target.organization(FUND), 2000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(both, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 2000L),
                "前任在撤职当刻不再有新访问");
        assertEquals(5, both.appointments().size(), "四条原任职加一条继任，前任任职保留");
        assertEquals(2, both.appointments().stream()
                .filter(a -> a.organizationPartyId().equals(FUND) && a.role() == Role.RESEARCHER)
                .count(), "前任与继任各一条");
        Appointment oldOne = appointment(both, "APT-RES");
        Appointment newOne = appointment(both, "APT-RES-2");
        assertNotNull(oldOne);
        assertNotNull(newOne);
        assertNotEquals(oldOne.id(), newOne.id());
        assertNotEquals(oldOne.personPartyId(), newOne.personPartyId());
        assertEquals(2000L, newOne.startsAt());
        assertNull(newOne.revokedAt());
        // 继任者没有被克隆：前任仍在册且仍标记为自己那条，记录各自独立
        assertEquals(2000L, oldOne.revokedAt());
        assertNotEquals(oldOne.personPartyId(), newOne.personPartyId());
    }

    // ---------------------------------------------------------------- 4. 隔离

    @Test
    @DisplayName("跨产品、跨机构、雇主自有、未列同产品账户与未知标识一律拒绝")
    void accountIsolationAcrossProductsOrgsAndOwners() {
        State state = seated();

        // 未列出的同产品账户
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A3), 1000L));
        // 雇主自有账户
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_ORG), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, PM, Action.READ_PRODUCT_ACCOUNT, Target.account(FUND, PROD_A, ACC_ORG), 1000L));
        // 同机构未授权的另一产品、以及别机构产品
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, PM, Action.SET_PORTFOLIO_TARGET, Target.product(FUND, PROD_B), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_B, ACC_B), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.READ_PRODUCT_ACCOUNT, Target.account(OTHER, PROD_B, ACC_B), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.READ_ORGANIZATION_FILE, Target.organization(OTHER), 1000L));
        // 产品与账户交叉拼装
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_B), 1000L));
        // 动作与范围不匹配
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.EXECUTE_ORDER, Target.product(FUND, PROD_A), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.READ_PRODUCT_ACCOUNT, Target.organization(FUND), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, RES, Action.WRITE_RESEARCH, Target.account(FUND, PROD_A, ACC_A1), 1000L));
        // 多账户产品：明确列出的第二个账户可用
        assertEquals(Decision.ALLOW,
                decide(state, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A2), 1000L));
        // 未知人物与未知账户得到同一个拒绝值，不区分存在性
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, "P-NOPE", Action.READ_PRODUCT_ACCOUNT, Target.account(FUND, PROD_A, ACC_A1), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.READ_PRODUCT_ACCOUNT, Target.account(FUND, PROD_A, "ACC-NOPE"), 1000L));
    }

    @Test
    @DisplayName("PERSON 账户只允许本人查看，产品账户与公司账户不能走个人通道")
    void personalAccountOnlyForItsPerson() {
        State state = seated();
        assertEquals(Decision.ALLOW, RoleAccess.canReadPersonalAccount(state, RES, ACC_PERSON_RES));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.canReadPersonalAccount(state, PM, ACC_PERSON_RES));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.canReadPersonalAccount(state, RES, ACC_PERSON_PM));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.canReadPersonalAccount(state, TRADER, ACC_A1));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.canReadPersonalAccount(state, RES, ACC_PERSON_OUT));
        assertEquals(Decision.ALLOW, RoleAccess.canReadPersonalAccount(state, OUTSIDER, ACC_PERSON_OUT));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.canReadPersonalAccount(state, "P-NOPE", ACC_PERSON_RES));
        // 角色判定不能把产品 owner 当人物，也不能借个人通道
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(state, TRADER, Action.READ_PRODUCT_ACCOUNT, Target.account(FUND, PROD_A, ACC_PERSON_RES), 1000L));
    }

    // ---------------------------------------------------------------- 5. 重叠、继任、时间

    @Test
    @DisplayName("同岗占位、同机构兼岗与区间重叠拒绝；未来非重叠可预排")
    void overlappingAppointmentsAreRejected() {
        State state = seated();
        // 同机构同岗位重叠（另一人物）
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-RISK-2", NEXT, FUND, Role.RISK_OFFICER, 1500L, 3000L, 1000L));
        // 同人物同机构兼不同岗
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-RES-PM", RES, FUND, Role.PORTFOLIO_MANAGER, 3000L, 4000L, 1000L));
        // 同人物同机构同岗重复
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-RES-DUP", RES, FUND, Role.RESEARCHER, 1000L, 2000L, 1000L));
        // 前一条刚到期即继任是允许的（右开）
        State later = RoleAccess.appoint(REGISTRY, state, "APT-RISK-3", NEXT, FUND,
                Role.RISK_OFFICER, 5000L, 6000L, 1000L);
        assertEquals(Decision.ALLOW,
                decide(later, NEXT, Action.ISSUE_RISK_HALT, Target.product(FUND, PROD_A), 5000L));
        // 同一个人可以在别的机构任职（首版只禁止同机构兼岗）
        State across = RoleAccess.appoint(REGISTRY, later, "APT-RES-OUT", RES, OTHER,
                Role.RESEARCHER, 3000L, 4000L, 1000L);
        assertEquals(Decision.ALLOW,
                decide(across, RES, Action.WRITE_RESEARCH, Target.organization(OTHER), 3000L));
    }

    @Test
    @DisplayName("撤职后的有效结束时刻参与重叠判定，继任者可从撤职当刻开始")
    void revocationShortensOverlapWindow() {
        State state = seated();
        State revoked = RoleAccess.revoke(state, "APT-RISK", 2000L);
        // 若忽略 revokedAt，[2000,3000) 与 [1000,5000) 会重叠而被拒绝
        State next = RoleAccess.appoint(REGISTRY, revoked, "APT-RISK-2", NEXT, FUND,
                Role.RISK_OFFICER, 2000L, 3000L, 2000L);
        assertEquals(Decision.ALLOW,
                decide(next, NEXT, Action.ISSUE_RISK_HALT, Target.product(FUND, PROD_A), 2000L));
        // 缩短后的在任期仍受同岗重叠约束
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, next, "APT-RISK-3", OUTSIDER, FUND, Role.RISK_OFFICER, 2500L, 3500L, 2000L));
    }

    @Test
    @DisplayName("预排起点不得早于调用 now；非法窗口、错误身份与负时间全部拒绝")
    void appointRejectsPastStartAndMalformedInput() {
        State state = seated();
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, FUND, Role.RESEARCHER, 999L, 4000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, FUND, Role.RESEARCHER, 3000L, 3000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, FUND, Role.RESEARCHER, 4000L, 3000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, FUND, Role.RESEARCHER, -1L, 3000L, 0L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, FUND, Role.RESEARCHER, 2000L, 3000L, -1L));
        // 人物必须是 PERSON，组织必须已是 ORGANIZATION
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", FUND, FUND, Role.RESEARCHER, 2000L, 3000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, RES, Role.RESEARCHER, 2000L, 3000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", "P-NOPE", FUND, Role.RESEARCHER, 2000L, 3000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "  ", NEXT, FUND, Role.RESEARCHER, 2000L, 3000L, 1000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.appoint(
                REGISTRY, state, "APT-X", NEXT, FUND, null, 2000L, 3000L, 1000L));
        // 非负 now 是所有判定的前提
        assertThrows(IllegalArgumentException.class,
                () -> decide(state, RES, Action.WRITE_RESEARCH, Target.organization(FUND), -1L));
    }

    @Test
    @DisplayName("撤销不能早于开始、不能撤销已到期任职、不能重复、未知 ID 与负时间拒绝")
    void revokeRejectsEarlyExpiredAndRepeated() {
        State state = seated();
        State future = RoleAccess.appoint(REGISTRY, state, "APT-FUTURE", NEXT, OTHER,
                Role.RESEARCHER, 3000L, 4000L, 1000L);
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(future, "APT-FUTURE", 2000L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(future, "APT-FUTURE", -1L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(future, "APT-UNKNOWN", 3000L));

        State expired = RoleAccess.appoint(REGISTRY, state, "APT-SHORT", NEXT, OTHER,
                Role.RESEARCHER, 1000L, 2000L, 1000L);
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(expired, "APT-SHORT", 2000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(expired, NEXT, Action.WRITE_RESEARCH, Target.organization(OTHER), 2000L),
                "过期任职不产生权限");
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(expired, NEXT, Action.WRITE_RESEARCH, Target.organization(OTHER), 5000L));

        State revoked = RoleAccess.revoke(expired, "APT-SHORT", 1500L);
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(revoked, NEXT, Action.WRITE_RESEARCH, Target.organization(OTHER), 1500L));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.revoke(revoked, "APT-SHORT", 1600L));
    }

    // ---------------------------------------------------------------- 6. 不可变与 JSON

    @Test
    @DisplayName("状态不保存现金：只有账户引用，JSON 里没有任何余额")
    void stateHoldsNoCash() throws Exception {
        State state = seated();
        String json = RoleAccess.toJson(state);
        assertFalse(json.contains(CASH_UNIQUE_A), "不应把现金余额写入授权状态");
        assertFalse(json.contains("250000.00"));
        assertFalse(json.contains("800000.00"));
        assertFalse(json.toLowerCase().contains("cash"));
        assertFalse(json.toLowerCase().contains("balance"));

        for (Class<?> type : List.of(State.class, AccountBinding.class, PersonAccount.class,
                ProductMandate.class, Appointment.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertNotEquals(BigDecimal.class, component.getType(), type.getSimpleName() + " 不应记金额");
                assertFalse(component.getName().toLowerCase().contains("cash"));
                assertFalse(component.getName().toLowerCase().contains("balance"));
            }
        }
        for (AccountBinding binding : state.accounts()) {
            assertNotNull(binding.ownerId(), "绑定必须指向已登记财产主体");
        }
    }

    @Test
    @DisplayName("集合深不可变，JSON 稳定往返，篡改后的状态被构造期校验拒绝")
    void deepImmutabilityAndDeterministicJson() {
        State state = seated();
        assertThrows(UnsupportedOperationException.class, () -> state.accounts().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.appointments().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> state.mandates().get(0).accountIds().add("ACC-X"));
        assertThrows(UnsupportedOperationException.class, () -> state.personAccounts().clear());

        String json = RoleAccess.toJson(state);
        assertEquals(json, RoleAccess.toJson(state), "同一状态必须序列化稳定");
        State restored = RoleAccess.fromJson(json);
        assertEquals(state, restored, "往返后状态相等");
        assertEquals(json, RoleAccess.toJson(restored));

        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.fromJson(json.replace("\"revokedAt\":null", "\"revokedAt\":-1")));
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.fromJson(json.replace("\"accountIds\":[\"ACC-PROD-A-1\",\"ACC-PROD-A-2\"]",
                        "\"accountIds\":[\"ACC-PROD-A-1\",\"ACC-NOPE\"]")));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.fromJson("  "));

        // 直接构造也必须拒绝：重叠任职、把产品账户塞进个人通道
        List<Appointment> overlaps = new ArrayList<>(state.appointments());
        overlaps.add(new Appointment("APT-Z", NEXT, FUND, Role.RESEARCHER, 2000L, 3000L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new State(state.accounts(), state.personAccounts(), state.mandates(), overlaps));
        assertThrows(IllegalArgumentException.class,
                () -> new State(state.accounts(), List.of(new PersonAccount(ACC_A1, RES)),
                        state.mandates(), state.appointments()));
    }

    @Test
    @DisplayName("对外判定 API 没有布尔提权参数或第二个 authorize 重载")
    void noPrivilegeEscalationParameter() {
        int authorizeCount = 0;
        for (Method method : RoleAccess.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                assertNotEquals(boolean.class, parameter.getType(),
                        method.getName() + " 不应接受布尔旁路参数");
                assertNotEquals(Boolean.class, parameter.getType(),
                        method.getName() + " 不应接受布尔旁路参数");
            }
            if (method.getName().equals("authorize")) {
                authorizeCount++;
                assertEquals(5, method.getParameterCount(), "authorize 只接受人物、动作、范围、时刻与状态");
            }
        }
        assertEquals(1, authorizeCount, "不允许存在第二个提权重载");
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.authorize(null, RES, Action.WRITE_RESEARCH, Target.organization(FUND), 1000L));
    }

    @Test
    @DisplayName("JSON 与构造期校验共享同一套不变量：未知字段与非法角色被拒绝")
    void jsonRejectsUnknownFieldsAndIllegalRole() throws Exception {
        State state = seated();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(state);
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.fromJson(json.replace("\"appointments\"", "\"godMode\":true,\"appointments\"")));
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.fromJson(json.replace("\"role\":\"RESEARCHER\"", "\"role\":\"CHAIRMAN\"")));
        assertTrue(json.contains("\"role\":\"RESEARCHER\""));
        assertTrue(json.contains("\"productOwnerId\":\"OWN-PROD-A\""));
    }

    // ---------------------------------------------------------------- 7. 恢复校验（独审 R1/R2）

    @Test
    @DisplayName("恢复校验接受正常产物、JSON 往返、空授权与乱序输入，且不复制期初余额")
    void validateAcceptsNormalStatesJsonAndUnorderedInput() {
        State state = seated();
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, state));

        State restored = RoleAccess.fromJson(RoleAccess.toJson(state));
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, restored));
        assertFalse(RoleAccess.toJson(restored).contains(CASH_UNIQUE_A), "校验不引入金额");

        State empty = RoleAccess.open(REGISTRY, List.of(), List.of());
        assertTrue(empty.accounts().isEmpty());
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, List.of(), empty));

        State noMandates = RoleAccess.open(REGISTRY, CASH, List.of());
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, noMandates));

        List<OpeningAccount> reversed = new ArrayList<>(CASH);
        Collections.reverse(reversed);
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, reversed, state));
        assertEquals(CASH.size(), state.accounts().size());

        assertThrows(IllegalArgumentException.class, () -> RoleAccess.validateAgainst(null, CASH, state));
        assertThrows(IllegalArgumentException.class, () -> RoleAccess.validateAgainst(REGISTRY, CASH, null));
    }

    @Test
    @DisplayName("账户引用必须与开局账户全集一致：缺失、额外与换属主都拒绝")
    void validateRejectsAccountSetMismatch() {
        State state = seated();

        List<AccountBinding> missing = new ArrayList<>(state.accounts());
        missing.removeIf(binding -> binding.accountId().equals(ACC_A3));
        State missingState = new State(missing, state.personAccounts(),
                state.mandates(), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, missingState));

        List<AccountBinding> extra = new ArrayList<>(state.accounts());
        extra.add(new AccountBinding("ACC-GHOST", PROD_A));
        State extraState = new State(extra, state.personAccounts(),
                state.mandates(), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, extraState));

        List<AccountBinding> swapped = new ArrayList<>(state.accounts());
        swapped.replaceAll(binding -> binding.accountId().equals(ACC_A3)
                ? new AccountBinding(ACC_A3, PROD_B) : binding);
        State swappedState = new State(swapped, state.personAccounts(),
                state.mandates(), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, swappedState));

        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, state));
    }

    @Test
    @DisplayName("个人通道必须精确等于全部 PERSON 属主账户：偷绑产品、他人、组织账户与漏绑都拒绝")
    void validateRejectsForgedPersonalBindings() {
        State state = seated();

        State productAsPersonal = new State(state.accounts(),
                List.of(new PersonAccount(ACC_A3, RES)), state.mandates(), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, productAsPersonal));

        State someoneElses = new State(state.accounts(),
                List.of(new PersonAccount(ACC_PERSON_PM, RES)), state.mandates(), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, someoneElses));

        State organizationAccount = new State(state.accounts(),
                List.of(new PersonAccount(ACC_ORG, RES)), state.mandates(), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, organizationAccount));

        // 同一人物的两个个人账户：全在才通过，少一个或改绑别人都拒绝
        List<OpeningAccount> twoAccounts = new ArrayList<>(CASH);
        twoAccounts.add(new OpeningAccount(ACC_PERSON_RES_2, OWN_PERSON_RES,
                new BigDecimal("11.00"), "个人第二账户"));
        State complete = RoleAccess.open(REGISTRY, twoAccounts, MANDATES);
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, twoAccounts, complete));
        assertEquals(2, complete.personAccounts().stream()
                .filter(personal -> personal.personPartyId().equals(RES)).count());

        State incomplete = new State(complete.accounts(),
                complete.personAccounts().stream()
                        .filter(personal -> !personal.accountId().equals(ACC_PERSON_RES_2))
                        .toList(),
                complete.mandates(), complete.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, twoAccounts, incomplete));

        State misassigned = new State(complete.accounts(),
                complete.personAccounts().stream()
                        .map(personal -> personal.accountId().equals(ACC_PERSON_RES_2)
                                ? new PersonAccount(ACC_PERSON_RES_2, PM) : personal)
                        .toList(),
                complete.mandates(), complete.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, twoAccounts, misassigned));
    }

    @Test
    @DisplayName("mandate 管理者必须是组织、产品主体必须是 PRODUCT、账户必须属该产品")
    void validateRejectsOrganizationAssetAsProduct() {
        State state = seated();

        State employerAsProduct = new State(state.accounts(), state.personAccounts(),
                List.of(new ProductMandate(FUND, OWN_ORG_FUND, List.of(ACC_ORG))),
                state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, employerAsProduct));

        State personAsManager = new State(state.accounts(), state.personAccounts(),
                List.of(new ProductMandate(RES, PROD_A, List.of(ACC_A1))), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, personAsManager));

        State ghostManager = new State(state.accounts(), state.personAccounts(),
                List.of(new ProductMandate("ORG-GHOST", PROD_A, List.of(ACC_A1))),
                state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, ghostManager));

        // State 内部一致性可通过、但开户侧账户归属不符：把未列产品账户改属主后由 mandate 引用
        List<AccountBinding> rebound = new ArrayList<>(state.accounts());
        rebound.replaceAll(binding -> binding.accountId().equals(ACC_A3)
                ? new AccountBinding(ACC_A3, PROD_B) : binding);
        State reboundState = new State(rebound, state.personAccounts(),
                List.of(new ProductMandate(FUND, PROD_B, List.of(ACC_A3))), state.appointments());
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, reboundState));
    }

    @Test
    @DisplayName("任职的人物与组织必须是对应登记类型：错类型与未知标识都拒绝")
    void validateRejectsUnknownOrMistypedAppointments() {
        State state = seated();

        State organizationAsPerson = new State(state.accounts(), state.personAccounts(),
                state.mandates(), List.of(new Appointment("APT-BAD-1", FUND, FUND,
                        Role.RESEARCHER, 1000L, 5000L, null)));
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, organizationAsPerson));

        State personAsOrganization = new State(state.accounts(), state.personAccounts(),
                state.mandates(), List.of(new Appointment("APT-BAD-2", RES, RES,
                        Role.RESEARCHER, 1000L, 5000L, null)));
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, personAsOrganization));

        State ghostPerson = new State(state.accounts(), state.personAccounts(),
                state.mandates(), List.of(new Appointment("APT-BAD-3", "P-GHOST", FUND,
                        Role.RESEARCHER, 1000L, 5000L, null)));
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, ghostPerson));

        State ghostOrganization = new State(state.accounts(), state.personAccounts(),
                state.mandates(), List.of(new Appointment("APT-BAD-4", RES, "ORG-GHOST",
                        Role.RESEARCHER, 1000L, 5000L, null)));
        assertThrows(IllegalArgumentException.class,
                () -> RoleAccess.validateAgainst(REGISTRY, CASH, ghostOrganization));

        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, state));
    }

    @Test
    @DisplayName("授权保持显式：不要求每个产品都有 mandate，也不因同机构而扩大账户范围")
    void validateKeepsMandatesExplicitAndNarrow() {
        State state = seated();
        State narrow = new State(state.accounts(), state.personAccounts(),
                List.of(new ProductMandate(FUND, PROD_A, List.of(ACC_A1))), state.appointments());
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, narrow));
        assertEquals(Decision.ALLOW,
                decide(narrow, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A1), 1000L));
        assertEquals(Decision.NOT_AUTHORIZED,
                decide(narrow, TRADER, Action.EXECUTE_ORDER, Target.account(FUND, PROD_A, ACC_A2), 1000L),
                "未列出的同产品账户不因同机构而放行");

        State productWithoutMandate = RoleAccess.open(REGISTRY, CASH,
                List.of(new ProductMandate(FUND, PROD_A, List.of(ACC_A1, ACC_A2))));
        assertDoesNotThrow(() -> RoleAccess.validateAgainst(REGISTRY, CASH, productWithoutMandate));
        assertEquals(Decision.NOT_AUTHORIZED, decide(productWithoutMandate, TRADER,
                Action.EXECUTE_ORDER, Target.account(OTHER, PROD_B, ACC_B), 1000L));
    }
}
