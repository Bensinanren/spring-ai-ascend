package com.huawei.ascend.agentsdk.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.agentsdk.spec.rail.RailSpec;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenJiuwenRailMapperTest {

    @Test
    void functionRailCanBindMapToMapMethodAndWriteReturnedExtra() {
        RailSpec spec = new RailSpec(
                "rewrite-extra",
                "function",
                TestRailHooks.class.getName(),
                "rewriteExtra",
                "beforeModelCall",
                7,
                Map.of());

        AgentRail rail = new OpenJiuwenRailMapper().toAgentRail(spec);
        AgentCallbackContext context = AgentCallbackContext.builder()
                .extra(new LinkedHashMap<>(Map.of("input", "old")))
                .build();
        rail.getCallbacks().get(AgentCallbackEvent.BEFORE_MODEL_CALL).accept(context);

        assertThat(context.getExtra())
                .containsEntry("input", "old")
                .containsEntry("rewritten", true);
        assertThat(rail.getPriority()).isEqualTo(7);
    }

    public static final class TestRailHooks {
        private TestRailHooks() {
        }

        public static Map<String, Object> rewriteExtra(Map<String, Object> extra) {
            Map<String, Object> result = new LinkedHashMap<>(extra);
            result.put("rewritten", true);
            return result;
        }
    }
}
