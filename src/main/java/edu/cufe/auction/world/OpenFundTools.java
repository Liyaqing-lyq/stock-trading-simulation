package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** 开放式产品的本人决定工具，申请人由可信会话绑定。 */
public final class OpenFundTools {
    /** 仅用于有份额登记的产品，动作均有正耗时。 */
    public static final List<String> TOOLS = List.of("fund.subscribe", "fund.redeem", "fund.cancel");
    private OpenFundTools() {}

    static void addAvailable(List<String> tools, JsonNode view) {
        if (!view.path("holder").asBoolean()) return;
        tools.add("fund.subscribe");
        if (positive(view.path("availableUnits"))) tools.add("fund.redeem");
        for (var order : view.path("orders")) {
            if (positive(order.path("remaining"))) { tools.add("fund.cancel"); break; }
        }
    }

    private static boolean positive(JsonNode value) {
        return !value.isMissingNode() && !value.isNull() && new BigDecimal(value.asText()).signum() > 0;
    }

    static OpenFundUnits.Command command(JsonNode observed, String person, String tool, JsonNode args) {
        var view = observed.at("/engineering/openFund");
        if (!view.path("holder").asBoolean() || !person.equals(view.path("person").asText()))
            throw new IllegalArgumentException("本人没有该产品申请权限");
        boolean cancel = tool.equals("fund.cancel");
        var fields = cancel ? Set.of("requestId") : Set.of("requestId","amount");
        if (!args.isObject() || args.size()!=fields.size() || !fields.stream().allMatch(args::has))
            throw new IllegalArgumentException("基金申请字段不合法");
        var id = args.path("requestId");
        if (!id.isTextual() || id.asText().isBlank()) throw new IllegalArgumentException("申请编号不能为空");
        if (cancel) {
            for (var order : view.path("orders"))
                if (id.textValue().equals(order.path("id").asText()) && positive(order.path("remaining")))
                    return new OpenFundUnits.Command(person,id.textValue(),"CANCEL",null);
            throw new IllegalArgumentException("观察时没有本人可撤回的申请");
        }
        var amount = args.path("amount");
        if (!amount.isNumber() && !amount.isTextual()) throw new IllegalArgumentException("金额或份额须为十进制数");
        var kind = tool.equals("fund.subscribe") ? OpenFundUnits.Kind.SUBSCRIBE : OpenFundUnits.Kind.REDEEM;
        var value = OpenFundUnits.parseAmount(kind, amount.asText());
        return new OpenFundUnits.Command(person,id.textValue(),kind.name(),value);
    }
}
