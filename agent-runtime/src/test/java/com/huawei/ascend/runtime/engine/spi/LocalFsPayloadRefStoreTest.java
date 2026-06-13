package com.huawei.ascend.runtime.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link LocalFsPayloadRefStore}. */
class LocalFsPayloadRefStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storeWritesPayloadAndReturnsPayloadRefUri() throws IOException {
        LocalFsPayloadRefStore store = new LocalFsPayloadRefStore(tempDir);
        String payload = "the full prompt content";

        String uri = store.store("ctx1", "task1", 0L, "result", payload);

        assertThat(uri).startsWith("payload_ref://");
        // Resolve the relative path and verify the file content.
        String relative = uri.substring("payload_ref://".length());
        Path file = tempDir.resolve(relative.replace('/', java.io.File.separatorChar));
        assertThat(file).exists();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void secondCallWithDifferentSeqWritesDistinctFile() {
        LocalFsPayloadRefStore store = new LocalFsPayloadRefStore(tempDir);

        String uri0 = store.store("ctx1", "task1", 0L, "result", "payload-zero");
        String uri1 = store.store("ctx1", "task1", 1L, "result", "payload-one");

        assertThat(uri0).isNotNull();
        assertThat(uri1).isNotNull();
        assertThat(uri0).isNotEqualTo(uri1);
    }

    @Test
    void storeCreatesIntermediateDirectories() {
        LocalFsPayloadRefStore store = new LocalFsPayloadRefStore(tempDir.resolve("deep/nested"));
        String uri = store.store("ctx", "task", 0L, "args", "content");

        assertThat(uri).startsWith("payload_ref://");
    }

    @Test
    void storeHandlesNullContextAndTaskWithoutThrowing() {
        LocalFsPayloadRefStore store = new LocalFsPayloadRefStore(tempDir);
        String uri = store.store(null, null, 0L, "result", "payload");

        assertThat(uri).startsWith("payload_ref://");
    }
}
