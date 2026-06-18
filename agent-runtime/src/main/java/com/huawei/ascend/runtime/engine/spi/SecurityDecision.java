package com.huawei.ascend.runtime.engine.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SecurityDecision(
        String schemaVersion,
        String decisionId,
        String securityEvaluationRequestId,
        DecisionType decisionType,
        String policyId,
        String policyHash,
        String policyProfile,
        String delegationEnvelopeRef,
        String profileRule,
        List<DecisionObligation> obligations,
        String reasonCode,
        String humanMessage,
        String approvalRef,
        String auditRef,
        Instant expiresAt,
        Map<String, String> attributes) {

    public static final String SCHEMA_VERSION = "security-decision/v1";

    public SecurityDecision {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(securityEvaluationRequestId, "securityEvaluationRequestId");
        Objects.requireNonNull(decisionType, "decisionType");
        obligations = List.copyOf(obligations == null ? List.of() : obligations);
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    public static SecurityDecision deny(SecurityEvaluationRequest request, String reasonCode, String humanMessage) {
        Objects.requireNonNull(request, "request");
        return new SecurityDecision(SCHEMA_VERSION,
                request.securityEvaluationRequestId() + ":decision",
                request.securityEvaluationRequestId(),
                DecisionType.DENY,
                "runtime-default-deny",
                null,
                request.policyProfile(),
                request.delegationEnvelopeRef(),
                null,
                List.of(DecisionObligation.RECORD_SECURITY_EVENT),
                reasonCode,
                humanMessage,
                null,
                null,
                null,
                Map.of("sourceSurface", Objects.toString(request.sourceSurface(), "")));
    }
}
