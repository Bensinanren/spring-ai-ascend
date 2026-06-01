package com.huawei.ascend.service.engine.model;

import java.util.Objects;

/**
 * A single message in the Agent execution input.
 *
 * <p>First version retains only text messages. The {@code content} field
 * directly adapts to common text input for openJiuwen/agent-core-java.
 * Complex message blocks and multimodal messages are not designed.
 *
 * @param role    message role: system, user, assistant, or tool.
 * @param content message text content.
 */
public record EngineMessage(
        String role,
        String content) {

    public EngineMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
