package com.huawei.ascend.runtime.engine.spi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PayloadRefStore} that writes payloads to the local filesystem under a
 * configured base directory. The URI scheme is:
 *
 * <pre>
 *   payload_ref://&lt;contextId&gt;/&lt;taskId&gt;/&lt;seq&gt;-&lt;sanitized-fieldPath&gt;.txt
 * </pre>
 *
 * where the relative path is rooted at the configured {@code baseDir}. A consumer
 * resolves the physical file by joining {@code baseDir} with the path component
 * after {@code payload_ref://}.
 *
 * <p>The implementation never throws: any {@link IOException} is logged at WARN
 * and {@code null} is returned so the emitter falls back to truncated inline values.
 *
 * <p>Remote backends (object storage, a key-value service, etc.) are a future
 * {@link PayloadRefStore} implementation; this class is the local debugging / dev
 * variant only.
 */
public final class LocalFsPayloadRefStore implements PayloadRefStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFsPayloadRefStore.class);
    private static final String URI_SCHEME = "payload_ref://";

    private final Path baseDir;

    /**
     * @param baseDir root directory under which payload files are written; created
     *                on first use if it does not exist
     */
    public LocalFsPayloadRefStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String store(String contextId, String taskId, long seq, String fieldPath, String payload) {
        String safeCtx = sanitize(contextId != null ? contextId : "none");
        String safeTask = sanitize(taskId != null ? taskId : "none");
        String safeField = sanitize(fieldPath != null ? fieldPath : "field");
        String fileName = seq + "-" + safeField + ".txt";
        Path dir = baseDir.resolve(safeCtx).resolve(safeTask);
        Path file = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, payload, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("payload_ref store write failed for contextId={} taskId={} seq={} field={}: {}",
                    contextId, taskId, seq, fieldPath, e.getMessage());
            return null;
        }
        // Relative path portion after the scheme — always uses '/' as separator.
        String relativePath = safeCtx + "/" + safeTask + "/" + fileName;
        return URI_SCHEME + relativePath;
    }

    /**
     * Replaces any character that is not alphanumeric, hyphen, or underscore with an
     * underscore so path components are safe on all platforms.
     */
    private static String sanitize(String input) {
        return input.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
