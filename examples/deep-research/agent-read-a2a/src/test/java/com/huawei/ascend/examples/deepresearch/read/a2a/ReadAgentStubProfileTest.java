/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.examples.deepresearch.read.a2a;

import com.huawei.ascend.runtime.engine.openjiuwen.OpenJiuwenAgentRuntimeHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4.5 stub-profile boot test. Activates the {@code stub} profile (which
 * points {@code read-agent.yaml-classpath} at the fixture-backed
 * {@code agent.stub.yaml}) and asserts the Spring context loads and the
 * {@link ReadAgentHandler} bean is present. The {@code LLM_*} properties
 * satisfy the YAML env-placeholder resolver with a fake endpoint — the test
 * never invokes the ReAct loop, so no real model call is made.
 */
@SpringBootTest(
        classes = ReadAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "LLM_PROVIDER=openai",
                "LLM_API_KEY=test-key",
                "LLM_API_BASE=http://localhost:4000/v1",
                "LLM_MODEL=test-model"
        })
@ActiveProfiles("stub")
class ReadAgentStubProfileTest {

    @Autowired(required = false)
    private OpenJiuwenAgentRuntimeHandler handler;

    @Test
    void contextLoads_withStubProfile() {
        // If the context loads without exception, stub wiring is correct.
        assertThat(true).isTrue();
    }

    @Test
    void handler_shouldBePresent() {
        assertThat(handler).isNotNull();
    }
}
