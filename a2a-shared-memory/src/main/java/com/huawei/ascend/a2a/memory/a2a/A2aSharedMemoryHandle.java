package com.huawei.ascend.a2a.memory.a2a;

import com.huawei.ascend.a2a.memory.obs.MemoryObserver;
import com.huawei.ascend.a2a.memory.shared.SharedEntry;
import com.huawei.ascend.a2a.memory.shared.SharedMemoryKit;
import com.huawei.ascend.a2a.memory.shared.SharedMemoryStore;
import java.util.List;
import java.util.Optional;

/**
 * An A2A agent's view of its collaboration's shared blackboard — bound to the
 * collaboration (A2A contextId), tenant, and the calling agent. {@link #put} is
 * attributed to this agent automatically (ownership); {@link #get}/{@link #keys}
 * read what any participant wrote.
 */
public final class A2aSharedMemoryHandle {

    private final SharedMemoryKit kit;
    private final String agentId;

    A2aSharedMemoryHandle(SharedMemoryStore store, String tenantId, String collaborationId, String agentId,
            MemoryObserver observer) {
        this.kit = SharedMemoryKit.forCollaboration(store, tenantId, collaborationId, observer);
        this.agentId = agentId;
    }

    /** Write a conclusion, attributed to (and owned by) this agent. */
    public SharedEntry put(String key, String value) {
        return kit.put(key, value, agentId);
    }

    /** Latest value any participant wrote for a key. */
    public Optional<String> get(String key) {
        return kit.get(key);
    }

    /** Keys visible on this collaboration's blackboard. */
    public List<String> keys() {
        return kit.keys();
    }

    /** The agent this handle writes as. */
    public String agentId() {
        return agentId;
    }
}
