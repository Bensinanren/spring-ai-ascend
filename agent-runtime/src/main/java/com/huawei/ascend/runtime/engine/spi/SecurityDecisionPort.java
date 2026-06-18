package com.huawei.ascend.runtime.engine.spi;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface SecurityDecisionPort {
    CompletionStage<SecurityDecision> evaluate(SecurityEvaluationRequest request);
}
