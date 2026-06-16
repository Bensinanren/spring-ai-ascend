package com.huawei.ascend.a2a.memory.shared;

import com.huawei.ascend.a2a.memory.obs.MemoryObserver;
import java.util.List;
import java.util.Optional;

/**
 * Kit facade for the A2A run-scoped shared blackboard — the open consumption
 * surface (ADR-0162). Bound to one collaboration; agents read each other's
 * conclusions and write their own. Thin by design: ownership, append-log and
 * isolation live in the {@link SharedMemoryStore} (the engine), not here.
 *
 * <p>Ownership rejections surface (they are a permission error, not a backend
 * failure) — but they are also reported to the {@link MemoryObserver} so ops can
 * see contention. A backend failure surfaces too: the collaboration coordinator
 * has its own reclaim, so shared memory does not silently swallow infra errors.
 *
 * <pre>{@code
 * SharedMemoryKit board = SharedMemoryKit.forCollaboration(store, "demo-tenant", taskId);
 * board.put("riskAssessment", json, "risk-agent");   // risk-agent owns this key
 * board.get("riskAssessment");                        // any agent reads
 * }</pre>
 */
public final class SharedMemoryKit {

    private final SharedMemoryStore store;
    private final String tenantId;
    private final String collaborationId;
    private final MemoryObserver observer;

    private SharedMemoryKit(SharedMemoryStore store, String tenantId, String collaborationId,
            MemoryObserver observer) {
        this.store = store;
        this.tenantId = tenantId;
        this.collaborationId = collaborationId;
        this.observer = observer == null ? MemoryObserver.NOOP : observer;
    }

    /**
     * @param collaborationId the collaboration-root id (the A2A contextId / the
     *                        collaboration token's taskId) — keys this blackboard.
     */
    public static SharedMemoryKit forCollaboration(SharedMemoryStore store, String tenantId, String collaborationId) {
        return new SharedMemoryKit(store, tenantId, collaborationId, MemoryObserver.NOOP);
    }

    public static SharedMemoryKit forCollaboration(SharedMemoryStore store, String tenantId, String collaborationId,
            MemoryObserver observer) {
        return new SharedMemoryKit(store, tenantId, collaborationId, observer);
    }

    /**
     * Write (append) a value to a key as {@code writerAgentId}. The first writer
     * of a key owns it; a non-owner write throws {@link OwnershipViolationException}.
     */
    public SharedEntry put(String key, String value, String writerAgentId) {
        long t0 = System.nanoTime();
        try {
            SharedEntry entry = store.append(tenantId, collaborationId, key, value, writerAgentId);
            observer.onOperation("shared.put", tenantId, true, elapsedMs(t0));
            return entry;
        } catch (OwnershipViolationException e) {
            observer.onDegraded("shared.put", tenantId, "ownership-rejected");
            throw e;
        }
    }

    /** Latest value for a key (any participant may read). */
    public Optional<String> get(String key) {
        long t0 = System.nanoTime();
        Optional<String> value = store.latest(tenantId, collaborationId, key).map(SharedEntry::value);
        observer.onOperation("shared.get", tenantId, true, elapsedMs(t0));
        return value;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Latest full entry (value + provenance) for a key. */
    public Optional<SharedEntry> entry(String key) {
        return store.latest(tenantId, collaborationId, key);
    }

    /** Append history for a key, oldest first (provenance trail). */
    public List<SharedEntry> history(String key) {
        return store.history(tenantId, collaborationId, key);
    }

    /** All keys on this blackboard. */
    public List<String> keys() {
        return store.keys(tenantId, collaborationId);
    }

    /** Drop this collaboration's blackboard (run end / after experience distillation). */
    public void release() {
        store.release(tenantId, collaborationId);
    }

    public String collaborationId() {
        return collaborationId;
    }

    public String tenantId() {
        return tenantId;
    }
}
