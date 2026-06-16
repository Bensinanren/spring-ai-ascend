package com.huawei.ascend.a2a.memory.a2a;

import com.huawei.ascend.a2a.memory.obs.MemoryObserver;
import com.huawei.ascend.a2a.memory.shared.SharedMemoryStore;
import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.common.RuntimeIdentity;

/**
 * Binds the A2A shared-memory kit to a running A2A agent. The shared blackboard is
 * keyed by the <b>A2A {@code contextId}</b> (the collaboration root that all agents
 * in one A2A collaboration share — surfaced on the runtime as
 * {@link RuntimeIdentity#sessionId()}), scoped to the tenant. The calling agent's
 * {@link RuntimeIdentity#agentId()} is the owner of anything it writes, so agents
 * read each other's conclusions but only the author can change its own.
 *
 * <p>This is the one place that touches {@code agent-runtime} (provided): the kit
 * core stays agent-neutral, this support class adapts the neutral execution context.
 *
 * <pre>{@code
 * // inside an A2A agent's execute(ctx):
 * var board = A2aSharedMemory.forContext(ctx, store);
 * board.put("riskAssessment", json);          // attributed to this agent
 * board.get("loanDecision");                   // read another agent's conclusion
 * }</pre>
 */
public final class A2aSharedMemory {

    private A2aSharedMemory() {
    }

    /** Bind to the agent's current A2A collaboration (contextId) + tenant, owning writes as this agent. */
    public static A2aSharedMemoryHandle forContext(AgentExecutionContext context, SharedMemoryStore store) {
        return forContext(context, store, MemoryObserver.NOOP);
    }

    public static A2aSharedMemoryHandle forContext(AgentExecutionContext context, SharedMemoryStore store,
            MemoryObserver observer) {
        RuntimeIdentity scope = context.getScope();
        String collaborationId = scope.sessionId(); // A2A contextId = the shared collaboration root
        return new A2aSharedMemoryHandle(store, scope.tenantId(), collaborationId, scope.agentId(), observer);
    }
}
