package com.huawei.ascend.service.engine.spi;

import com.huawei.ascend.service.engine.event.EngineExecutionEvent;
import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import java.util.stream.Stream;

/**
 * The seam between the engine and a concrete agent framework. An implementation
 * knows how to run one agent and surface its progress as a stream of engine
 * execution events. See engine model design §9.1.
 */
public interface AgentHandler {

    /** The agent id this handler serves. */
    String agentId();

    /** Whether this handler is ready to execute. */
    boolean isHealthy();

    /** Run the agent for the given context, emitting execution events. */
    Stream<EngineExecutionEvent> execute(AgentExecutionContext context);
}
