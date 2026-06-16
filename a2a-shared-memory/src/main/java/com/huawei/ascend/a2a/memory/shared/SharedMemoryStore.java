package com.huawei.ascend.a2a.memory.shared;

import java.util.List;
import java.util.Optional;

/**
 * Backend SPI for the A2A run-scoped shared blackboard — the server-sovereign
 * boundary. The in-process {@link InMemorySharedMemoryStore} implements it for
 * offline eval; a gRPC client to the closed A2A shared-memory engine implements the same
 * SPI in production (ADR-0162, form C). Implementations MUST enforce the
 * ownership write rule and tenant isolation; the kit facade stays thin.
 */
public interface SharedMemoryStore {

    /**
     * Append a value to a key under one collaboration, enforcing ownership.
     *
     * @throws OwnershipViolationException if the key exists and {@code writerAgentId}
     *                                     is not its owner (the version-1 writer).
     */
    SharedEntry append(String tenantId, String collaborationId, String key, String value, String writerAgentId);

    /** Latest entry for a key, or empty if never written. */
    Optional<SharedEntry> latest(String tenantId, String collaborationId, String key);

    /** Full append history for a key, oldest first (provenance trail). */
    List<SharedEntry> history(String tenantId, String collaborationId, String key);

    /** All keys currently present for the collaboration. */
    List<String> keys(String tenantId, String collaborationId);

    /** Drop all blackboard state for the collaboration (run end / archival). */
    void release(String tenantId, String collaborationId);
}
