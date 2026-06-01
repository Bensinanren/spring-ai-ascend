package com.huawei.ascend.service.engine.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input for Agent execution.
 *
 * <p>Carries Agent execution input. Prioritizes {@code messages} for
 * conversational input. {@code variables} is used for workflow variables
 * or structured task parameters.
 *
 * @param inputType input type: USER_MESSAGE, RESUME_SIGNAL, or AGENT_CALL_RESULT.
 * @param messages  list of messages (conversational input).
 * @param variables workflow variables or structured task parameters.
 */
public record EngineInput(
        String inputType,
        List<EngineMessage> messages,
        Map<String, Object> variables) {

    public EngineInput {
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(variables, "variables");
        messages = List.copyOf(messages);
        variables = Map.copyOf(variables);
    }
}
