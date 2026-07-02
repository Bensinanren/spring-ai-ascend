/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.examples.deepresearch.read.a2a;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure-construction test for {@link ReadAgentHandler}. Writes a minimal valid
 * assembly YAML to a temp dir, constructs the handler, and asserts a null
 * yaml-path fails fast. Does NOT start Spring.
 */
class ReadAgentHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void handler_shouldConstructWithoutException() throws Exception {
        Path yamlPath = tempDir.resolve("agent.yaml");
        String minimalYaml = """
                schema: ascend-agent/v1
                name: test-read-agent
                description: Test agent.
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  provider: OpenAI
                  name: test-model
                  baseUrl: http://localhost:4000/v1
                  apiKey: test-key
                  sslVerify: false
                prompt:
                  system: You are a test agent.
                """;
        Files.writeString(yamlPath, minimalYaml);

        ReadAgentHandler handler = new ReadAgentHandler(yamlPath);
        assertThat(handler).isNotNull();
    }

    @Test
    void nullYamlPath_shouldThrow() {
        assertThatCode(() -> new ReadAgentHandler(null))
                .isInstanceOf(NullPointerException.class);
    }
}
