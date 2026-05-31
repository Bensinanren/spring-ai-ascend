package com.huawei.ascend.service.access.a2a;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record A2aEnvelope(
        A2aContext context,
        A2aMessage message,
        A2aReplySpec reply) {

    public A2aEnvelope {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(message, "message");
        reply = reply == null ? new A2aReplySpec(A2aReplyMode.STREAM, null) : reply;
    }

    public record A2aContext(
            String tenantId,
            String userId,
            String agentId,
            String sessionId,
            String contextId,
            String idempotencyKey,
            String correlationId) {

        public A2aContext {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }

    public record A2aMessage(
            String text,
            List<Object> parts,
            Map<String, Object> metadata) {

        public A2aMessage {
            parts = parts == null ? List.of() : List.copyOf(parts);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record A2aReplySpec(
            A2aReplyMode mode,
            String target) {
        public A2aReplySpec {
            mode = mode == null ? A2aReplyMode.STREAM : mode;
        }
    }
}
