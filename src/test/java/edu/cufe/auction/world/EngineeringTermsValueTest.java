package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 合同阶段条款经过持久化后仍按内容比较，不能把合法恢复判成篡改。 */
class EngineeringTermsValueTest {
    @Test void persistedTermsRetainValueIdentityAndDetectChangedTranches() throws Exception {
        var original = new EngineeringModel.Terms(1000, 500, 10, 20,
                EngineeringModel.RetentionMode.TIME_ONLY, new long[]{3, 7});
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(original), EngineeringModel.Terms.class);
        assertEquals(original, restored, "相同条款恢复后应按阶段数值相等");
        assertEquals(original.hashCode(), restored.hashCode());
        assertEquals(1, new HashSet<>(java.util.List.of(original, restored)).size());
        assertNotEquals(original, new EngineeringModel.Terms(1000, 500, 10, 20,
                EngineeringModel.RetentionMode.TIME_ONLY, new long[]{4, 6}), "总量相同不能掩盖阶段变化");
        assertTrue(Set.of(original).contains(restored));
        long[] exposed = restored.trancheUnits();
        exposed[0] = 99;
        assertEquals(original, restored, "防御性副本不得修改条款");
    }
}
