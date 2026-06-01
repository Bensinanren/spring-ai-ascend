package com.huawei.ascend.service.engine.adapter.openjiuwen;

import com.huawei.ascend.service.engine.event.EngineExecutionEvent;
import com.huawei.ascend.service.engine.event.EngineFailedEvent;
import com.huawei.ascend.service.engine.event.EngineStartedEvent;
import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import com.huawei.ascend.service.engine.model.EngineExecutionScope;
import com.huawei.ascend.service.engine.spi.AgentHandler;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.ReActAgent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Generic openJiuwen {@link AgentHandler} (design §10.1). It is the only engine
 * code that touches the framework's {@code Runner}: it builds the agent via the
 * {@link OpenJiuwenAgentFactory} seam, runs it synchronously, and maps the
 * result to a terminal event per §10.4. The concrete agent (prompt/tools/model)
 * is supplied by the developer's factory (layer ③).
 */
public class OpenJiuwenAgentHandler implements AgentHandler {

    private final String agentId;
    private final OpenJiuwenAgentFactory agentFactory;
    private final OpenJiuwenMessageConverter messageConverter;
    private final OpenJiuwenResultMapper resultMapper;

    public OpenJiuwenAgentHandler(String agentId, OpenJiuwenAgentFactory agentFactory, OpenJiuwenMessageConverter messageConverter) {
        this(agentId, agentFactory, messageConverter,
                new OpenJiuwenResultMapper(() -> UUID.randomUUID().toString(), Instant::now));
    }

    OpenJiuwenAgentHandler(String agentId, OpenJiuwenAgentFactory agentFactory, OpenJiuwenMessageConverter messageConverter,
                           OpenJiuwenResultMapper resultMapper) {
        this.agentId = agentId;
        this.agentFactory = agentFactory;
        this.messageConverter = messageConverter;
        this.resultMapper = resultMapper;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public boolean isHealthy() {
        return agentFactory != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<EngineExecutionEvent> execute(AgentExecutionContext context) {
        EngineExecutionScope scope = context.getScope();
        EngineStartedEvent started = new EngineStartedEvent(newId(), scope, Instant.now());
        EngineExecutionEvent terminal;
        try {
            ReActAgent agent = agentFactory.create(context);
            Object input = messageConverter.toOpenJiuwenInput(context);
            Map<String, Object> result = (Map<String, Object>) Runner.runAgent(agent, input, null, null);
            terminal = resultMapper.map(scope, result);
        } catch (Exception e) {
            terminal = new EngineFailedEvent(newId(), scope, Instant.now(),
                    OpenJiuwenResultMapper.ERROR_CODE, String.valueOf(e.getMessage()));
        } finally {
            safeRelease(scope);
        }
        return Stream.of(started, terminal);
    }

    private void safeRelease(EngineExecutionScope scope) {
        try {
            Runner.release(scope.taskId());
        } catch (Exception ignored) {
            // best-effort cleanup; release failures must not mask the result
        }
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }
}
