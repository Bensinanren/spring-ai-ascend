package com.huawei.ascend.service.engine.dispatch;

import com.huawei.ascend.service.engine.spi.AgentHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link AgentHandlerRegistry} backed by a concurrent map.
 */
public class DefaultAgentHandlerRegistry implements AgentHandlerRegistry {

    private final Map<String, AgentHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(String agentId, AgentHandler handler) {
        handlers.put(agentId, handler);
    }

    @Override
    public AgentHandler findByAgentId(String agentId) {
        AgentHandler handler = handlers.get(agentId);
        if (handler == null) {
            throw new IllegalStateException("No AgentHandler registered for agentId=" + agentId);
        }
        return handler;
    }
}
