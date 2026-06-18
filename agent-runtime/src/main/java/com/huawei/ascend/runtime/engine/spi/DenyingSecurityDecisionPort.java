package com.huawei.ascend.runtime.engine.spi;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DenyingSecurityDecisionPort implements SecurityDecisionPort {

    @Override
    public CompletionStage<SecurityDecision> evaluate(SecurityEvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(SecurityDecision.deny(request,
                "SECURITY_POLICY_NOT_CONFIGURED",
                "Security policy is not configured for this runtime."));
    }
}
