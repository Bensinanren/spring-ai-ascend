package com.huawei.ascend.examples.a2a.returnmodes;

import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.engine.a2a.A2aAgentCardMapper;
import com.huawei.ascend.runtime.engine.a2a.AgentCards;
import com.huawei.ascend.runtime.engine.spi.AgentCapabilitiesDescriptor;
import com.huawei.ascend.runtime.engine.spi.AgentExecutionResult;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.StreamAdapter;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReturnModesAgentConfiguration {

    public static final String AGENT_ID = "return-modes-agent";

    @Bean
    AgentRuntimeHandler returnModesAgentRuntimeHandler() {
        return new DeterministicReturnModesHandler();
    }

    @Bean
    org.a2aproject.sdk.spec.AgentCard returnModesAgentCard() {
        // This handler genuinely emits multiple incremental output frames before completion
        // (stream-part-1, stream-part-2, stream-done), so streaming=true is honest.
        // In-memory push store is not durable, so pushNotifications=false.
        return A2aAgentCardMapper.toAgentCard(
                AgentCards.defaultDescriptor(AGENT_ID, "Deterministic agent for A2A return mode verification.")
                        .withCapabilities(new AgentCapabilitiesDescriptor(true, false, false)));
    }

    private static final class DeterministicReturnModesHandler implements AgentRuntimeHandler {
        private static final StreamAdapter ADAPTER = rawResults -> rawResults.map(AgentExecutionResult.class::cast);

        @Override
        public String agentId() {
            return AGENT_ID;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            // execute() returns multiple incremental output frames before the terminal result,
            // so this handler genuinely streams rather than returning a single completed frame.
            return true;
        }

        @Override
        public Stream<?> execute(AgentExecutionContext context) {
            String input = context.lastUserText();
            String normalized = input.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("fail")) {
                // Mirror the common case: the agent simply throws. The runtime must translate this
                // into a FAILED task carrying a structured, machine-readable error for the client.
                throw new IllegalArgumentException("deliberate failure for return-mode verification");
            }
            if (normalized.contains("stream")) {
                return Stream.of(
                        AgentExecutionResult.output("stream-part-1 "),
                        AgentExecutionResult.output("stream-part-2 "),
                        AgentExecutionResult.completed("stream-done"));
            }
            if (normalized.contains("input")) {
                return Stream.of(AgentExecutionResult.interrupted("please provide more input"));
            }
            return Stream.of(AgentExecutionResult.completed("sync-pong"));
        }

        @Override
        public StreamAdapter resultAdapter() {
            return ADAPTER;
        }
    }
}
