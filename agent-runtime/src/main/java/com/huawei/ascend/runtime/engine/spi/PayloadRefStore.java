package com.huawei.ascend.runtime.engine.spi;

/**
 * Out-of-band store for over-threshold prompt/completion payloads. When a
 * {@code PayloadRefStore} is configured and a slot is opted in, the emitter
 * persists the full (secret-redacted) content here and replaces the inline slot
 * value with a {@code payload_ref://...} URI. The trajectory therefore carries
 * a stable reference rather than the full blob.
 *
 * <p>Implementations MUST be thread-safe: multiple invocations may call
 * {@code store} concurrently. They MUST also be failure-tolerant: return
 * {@code null} on any storage error so the caller can fall back to normal
 * truncated inline behaviour — never throw.
 *
 * <p>URI scheme: {@code payload_ref://<relative-path>} — the {@code relative-path}
 * component is implementation-defined but stable (i.e. deterministic given the
 * same inputs). Consumers that need to resolve the actual bytes must know which
 * concrete implementation produced the URI (e.g. {@link LocalFsPayloadRefStore}
 * resolves relative paths under a configured base directory).
 */
public interface PayloadRefStore {

    /**
     * Stores {@code payload} out-of-band and returns the {@code payload_ref://...}
     * URI by which it can be retrieved, or {@code null} if storage failed (caller
     * falls back to truncated inline value in that case).
     *
     * @param contextId  the session / context identifier from {@link com.huawei.ascend.runtime.common.RuntimeIdentity}
     * @param taskId     the task identifier
     * @param seq        the trajectory event sequence number (monotonic within a task)
     * @param fieldPath  the slot name, e.g. {@code "args"}, {@code "result"}, {@code "reasoning"}
     * @param payload    the full secret-redacted content to store; never {@code null}
     * @return a {@code payload_ref://} URI, or {@code null} on failure
     */
    String store(String contextId, String taskId, long seq, String fieldPath, String payload);
}
