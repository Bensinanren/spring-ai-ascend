package com.huawei.ascend.service.access.mq;

import com.huawei.ascend.service.access.model.AccessOperation;

import java.util.Objects;

public record MqEnvelope(MqHeaders headers, MqBody body) {
    public MqEnvelope {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    public record MqHeaders(
            String tenantId,
            String userId,
            String agentId,
            String sessionId,
            AccessOperation operation,
            String idempotencyKey,
            String correlationId,
            String replyTopic) {

        public MqHeaders {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
            operation = operation == null ? AccessOperation.SUBMIT : operation;
        }
    }

    public record MqBody(String query, Object payload) {
    }
}
