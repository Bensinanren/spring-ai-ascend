package com.huawei.ascend.runtime.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityDecisionPortTest {

    @Test
    void defaultPortDeniesWhenPolicyIsNotConfigured() {
        SecurityEvaluationRequest request = request(Map.of("toolCallId", "tool-1"));

        SecurityDecision decision = new DenyingSecurityDecisionPort().evaluate(request)
                .toCompletableFuture()
                .join();

        assertThat(decision.decisionType()).isEqualTo(DecisionType.DENY);
        assertThat(decision.securityEvaluationRequestId()).isEqualTo("sec-1");
        assertThat(decision.reasonCode()).isEqualTo("SECURITY_POLICY_NOT_CONFIGURED");
        assertThat(decision.obligations()).containsExactly(DecisionObligation.RECORD_SECURITY_EVENT);
    }

    @Test
    void requestMetadataIsDefensivelyCopied() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("remoteInvocationChainId", "chain-1");

        SecurityEvaluationRequest request = request(metadata);
        metadata.put("remoteInvocationChainId", "chain-2");

        assertThat(request.requestMetadata()).containsEntry("remoteInvocationChainId", "chain-1");
        assertThatThrownBy(() -> request.requestMetadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiredFieldsAreValidated() {
        assertThatThrownBy(() -> new SecurityEvaluationRequest(
                SecurityEvaluationRequest.SCHEMA_VERSION,
                "sec-1",
                "tenant-a",
                "user-a",
                "session-a",
                "task-a",
                "agent-a",
                "test",
                "trace-a",
                "span-a",
                null,
                "target-a",
                RiskTier.R2_NETWORK_READ,
                null,
                null,
                null,
                "research",
                "review_unknown",
                "delegation-a",
                "host-validated",
                "chain-a",
                0,
                5,
                Map.of(),
                Instant.now())).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionType");
    }

    private static SecurityEvaluationRequest request(Map<String, Object> metadata) {
        return new SecurityEvaluationRequest(
                SecurityEvaluationRequest.SCHEMA_VERSION,
                "sec-1",
                "tenant-a",
                "user-a",
                "session-a",
                "task-a",
                "agent-a",
                "a2a_remote_outbound",
                "trace-a",
                "span-a",
                ActionType.A2A_REMOTE_AGENT_CALL,
                "quote-agent.ask",
                RiskTier.R2_NETWORK_READ,
                Map.of("summary", "redacted"),
                "input-hash",
                "idem-1",
                "research",
                "review_unknown",
                "delegation-a",
                "host-validated",
                "chain-a",
                0,
                5,
                metadata,
                Instant.EPOCH);
    }
}
