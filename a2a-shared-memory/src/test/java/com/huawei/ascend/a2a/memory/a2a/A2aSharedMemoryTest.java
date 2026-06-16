package com.huawei.ascend.a2a.memory.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huawei.ascend.a2a.memory.shared.InMemorySharedMemoryStore;
import com.huawei.ascend.a2a.memory.shared.OwnershipViolationException;
import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.common.RuntimeIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The actual A2A agent-to-agent sharing: two agents in the SAME A2A collaboration
 * (same {@code contextId}, surfaced as {@link RuntimeIdentity#sessionId()}) read
 * each other's conclusions; a third agent in a different collaboration sees nothing.
 * Uses the real agent-runtime execution context so the binding is exercised, not a
 * stand-in.
 */
class A2aSharedMemoryTest {

    private static final String CONTEXT = "collab-7"; // the A2A contextId shared by collaborators

    /** An A2A agent's execution context within collaboration {@code contextId}. */
    private static AgentExecutionContext ctx(String agentId, String contextId) {
        RuntimeIdentity scope = new RuntimeIdentity("bank", "u-1", contextId, "task-1", agentId);
        return new AgentExecutionContext(scope, "USER_MESSAGE", List.of(), Map.of(), contextId, null);
    }

    @Test
    void agentsInTheSameCollaborationShareConclusions() {
        InMemorySharedMemoryStore store = new InMemorySharedMemoryStore(() -> 1L);

        // risk-agent writes its conclusion to the shared blackboard...
        A2aSharedMemory.forContext(ctx("risk-agent", CONTEXT), store).put("riskAssessment", "C3 medium");
        // ...the advisor-agent, later in the same A2A collaboration, reads it.
        Optional<String> seen = A2aSharedMemory.forContext(ctx("advisor-agent", CONTEXT), store).get("riskAssessment");

        assertEquals(Optional.of("C3 medium"), seen, "advisor reads risk-agent's conclusion via the A2A contextId");
    }

    @Test
    void othersReadButOnlyTheOwnerCanChangeAKey() {
        InMemorySharedMemoryStore store = new InMemorySharedMemoryStore(() -> 1L);
        A2aSharedMemory.forContext(ctx("risk-agent", CONTEXT), store).put("riskAssessment", "C3");

        A2aSharedMemoryHandle advisor = A2aSharedMemory.forContext(ctx("advisor-agent", CONTEXT), store);
        assertTrue(advisor.get("riskAssessment").isPresent(), "advisor may read");
        assertThrows(OwnershipViolationException.class, () -> advisor.put("riskAssessment", "tampered"),
                "a non-owner cannot change another agent's conclusion");
    }

    @Test
    void differentCollaborationIsIsolated() {
        InMemorySharedMemoryStore store = new InMemorySharedMemoryStore(() -> 1L);
        A2aSharedMemory.forContext(ctx("risk-agent", CONTEXT), store).put("riskAssessment", "C3");

        Optional<String> otherCollab =
                A2aSharedMemory.forContext(ctx("advisor-agent", "collab-OTHER"), store).get("riskAssessment");
        assertTrue(otherCollab.isEmpty(), "a different A2A collaboration shares nothing");
    }

    @Test
    void eachAgentOwnsItsOwnKeysHandoverStyle() {
        InMemorySharedMemoryStore store = new InMemorySharedMemoryStore(() -> 1L);
        // A writes, then the task hands to B which writes its OWN key; both visible, A's stays A's.
        A2aSharedMemory.forContext(ctx("risk-agent", CONTEXT), store).put("riskAssessment", "C3");
        A2aSharedMemory.forContext(ctx("loan-agent", CONTEXT), store).put("loanDecision", "approved");

        A2aSharedMemoryHandle anyReader = A2aSharedMemory.forContext(ctx("advisor-agent", CONTEXT), store);
        assertEquals(Optional.of("C3"), anyReader.get("riskAssessment"));
        assertEquals(Optional.of("approved"), anyReader.get("loanDecision"));
        assertEquals(2, anyReader.keys().size());
    }
}
