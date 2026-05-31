package com.huawei.ascend.service.sessionmanage.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SessionMessage(
        String messageId,
        String name,
        SessionMessageRole role,
        String content,
        List<SessionContentPart> parts,
        Map<String, Object> metadata,
        Instant createdAt) {

    public SessionMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(role, "role");
        parts = parts == null ? List.of() : List.copyOf(parts);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
