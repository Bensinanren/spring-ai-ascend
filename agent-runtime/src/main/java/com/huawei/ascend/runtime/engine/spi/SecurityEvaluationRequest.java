package com.huawei.ascend.runtime.engine.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record SecurityEvaluationRequest(
        String schemaVersion,
        String securityEvaluationRequestId,
        String tenantId,
        String userId,
        String sessionId,
        String taskId,
        String agentId,
        String sourceSurface,
        String traceId,
        String spanId,
        ActionType actionType,
        String target,
        RiskTier riskTier,
        Object redactedPreview,
        String inputHash,
        String idempotencyKey,
        String posture,
        String policyProfile,
        String delegationEnvelopeRef,
        String metadataTrustSource,
        String remoteInvocationChainId,
        Integer remoteLegIndex,
        Integer maxRemoteLegs,
        Map<String, Object> requestMetadata,
        Instant requestedAt) {

    public static final String SCHEMA_VERSION = "security-evaluation-request/v1";

    public SecurityEvaluationRequest {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(securityEvaluationRequestId, "securityEvaluationRequestId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(riskTier, "riskTier");
        Objects.requireNonNull(requestedAt, "requestedAt");
        requestMetadata = Map.copyOf(requestMetadata == null ? Map.of() : requestMetadata);
    }

    public boolean remoteInvocationLeg() {
        return remoteInvocationChainId != null || remoteLegIndex != null || maxRemoteLegs != null;
    }
}
