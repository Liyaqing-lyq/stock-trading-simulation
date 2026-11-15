package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 命令边界：身份不可注入，载荷冻结，长整数往返不丢精度。 */
class ResourceOperationsRequestTest {
    @Test
    void identityInjectionAndMalformedFieldsAreRejected() {
        var fields = new LinkedHashMap<>(Map.of("jobId", "j", "seamId", "a", "quantityKg", "10"));
        fields.put("actor", "another-company");
        assertThrows(IllegalArgumentException.class, () -> new ResourceOperations.Request(
                ResourceOperations.Action.BEGIN_EXTRACTION, fields));
        fields.remove("actor");
        for (String quantity : new String[]{"1.5", "1e3", "9223372036854775808", "-1", " 10"}) {
            fields.put("quantityKg", quantity);
            assertThrows(IllegalArgumentException.class, () -> new ResourceOperations.Request(
                    ResourceOperations.Action.BEGIN_EXTRACTION, fields));
        }
        assertThrows(IllegalArgumentException.class, () -> new ResourceOperations.Request(
                ResourceOperations.Action.FINISH_EXTRACTION, Map.of()));
    }

    @Test
    void exactIntegerAndImmutableCanonicalPayloadSurviveJsonRoundTrip() throws Exception {
        var fields = new LinkedHashMap<>(Map.of("jobId", "j", "seamId", "a",
                "quantityKg", "9007199254740993"));
        var request = new ResourceOperations.Request(ResourceOperations.Action.BEGIN_EXTRACTION, fields);
        fields.put("quantityKg", "1");
        assertEquals(9007199254740993L, request.integer("quantityKg"));
        assertThrows(UnsupportedOperationException.class, () -> request.arguments().put("actor", "x"));
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(request);
        assertEquals(request, mapper.readValue(json, ResourceOperations.Request.class));
        assertEquals(json, mapper.writeValueAsString(mapper.readValue(json, ResourceOperations.Request.class)));
    }
}
