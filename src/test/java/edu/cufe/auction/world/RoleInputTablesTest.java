package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleInputTablesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sameOrderedColumnsAndNamedParametersRoundTripWithoutLosingNullsOrOrder() throws Exception {
        String json = """
                {"profile":{"catalog":[{"docId":"公开/甲","version":1,"title":"甲"},{"docId":"公开/乙","version":2,"title":"乙"}],
                "holdings":[],"market":[{"symbol":"甲","bestBid":null},{"symbol":"乙","bestBid":10}]},
                "tools":[{"name":"order","parameters":[{"name":"symbol","type":"string"},{"name":"limitPrice","type":"decimal"}]}]}
                """;
        var input = (ObjectNode) mapper.readTree(json);
        String old = mapper.writeValueAsString(input);
        RoleInputTables.compact(input);
        assertEquals("docId", input.at("/profile/catalog/columns/0").textValue());
        assertTrue(input.at("/profile/market/rows/0/1").isNull());
        assertTrue(input.at("/profile/holdings").isArray());
        assertEquals("decimal", input.at("/tools/0/parameters/limitPrice").textValue());
        RoleInputTables.expand(input);
        assertEquals(old, mapper.writeValueAsString(input));
    }

    @Test void heterogeneousRowsAndNonstandardToolParametersKeepTheirOriginalRepresentation() throws Exception {
        String json = """
                {"profile":{"catalog":[{"id":"甲","v":1},{"v":1,"id":"乙"}],"holdings":[{"symbol":"甲"}]},
                "tools":[{"name":"mixed","parameters":[{"name":"a","type":"string","optional":true}]},
                {"name":"duplicate","parameters":[{"name":"a","type":"long"},{"name":"a","type":"string"}]},
                {"name":"wait","parameters":[]}]}
                """;
        var input = (ObjectNode) mapper.readTree(json);
        String old = mapper.writeValueAsString(input);
        RoleInputTables.compact(input);
        assertEquals(old, mapper.writeValueAsString(input));
        RoleInputTables.expand(input);
        assertEquals(old, mapper.writeValueAsString(input));
    }
}
