package com.huawei.ascend.service.engine.support;

import com.huawei.ascend.service.engine.event.EngineCompletedEvent;
import com.huawei.ascend.service.engine.event.EngineExecutionEvent;
import com.huawei.ascend.service.engine.event.EngineInterruptedEvent;
import com.huawei.ascend.service.engine.event.EngineOutputEvent;
import com.huawei.ascend.service.engine.event.EngineStartedEvent;
import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import com.huawei.ascend.service.engine.model.EngineExecutionScope;
import com.huawei.ascend.service.engine.model.EngineOutput;
import com.huawei.ascend.service.engine.model.InterruptType;
import com.huawei.ascend.service.engine.spi.AgentHandler;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Test {@link AgentHandler} that interrupts on the first run (EXECUTE) and
 * completes on the second (RESUME), keyed by an internal flag. Lets an
 * interrupt → resume round-trip be exercised entirely in-engine, without a real
 * agent framework.
 */
public class FakeInterruptingAgentHandler implements AgentHandler {

    private final String agentId;
    private boolean resumed;

    public FakeInterruptingAgentHandler(String agentId) {
        this.agentId = agentId;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public Stream<EngineExecutionEvent> execute(AgentExecutionContext context) {
        EngineExecutionScope scope = context.getScope();
        EngineStartedEvent started = new EngineStartedEvent(id(), scope, Instant.now());
        if (!resumed) {
            resumed = true;
            EngineInterruptedEvent interrupted = new EngineInterruptedEvent(
                    id(), scope, Instant.now(), InterruptType.HUMAN_INPUT, "Need your confirmation");
            return Stream.of(started, interrupted);
        }
        EngineOutputEvent output = new EngineOutputEvent(id(), scope, Instant.now(), new EngineOutput("done", false));
        EngineCompletedEvent completed = new EngineCompletedEvent(
                id(), scope, Instant.now(), new EngineOutput("final answer", true));
        return Stream.of(started, output, completed);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
