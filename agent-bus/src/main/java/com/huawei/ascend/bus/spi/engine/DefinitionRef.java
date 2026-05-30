package com.huawei.ascend.bus.spi.engine;

import java.io.Serializable;

/**
 * The wire-form of an {@link ExecutorDefinition} reference: a capability name a
 * remote engine resolves to its own {@code ExecutorDefinition}.
 *
 * <p>In-process (co-deployed service+engine, or SDK-embedded) the Service
 * resolves the name to a fully-built {@code ExecutorDefinition} — node and
 * reasoner functions included — and hands it to the engine directly. Across a
 * transport boundary an {@code ExecutorDefinition} cannot travel: its functions
 * are JVM lambdas. Only this reference crosses, and the engine resolves it
 * against its own capability registry. That is what lets the boundary stay
 * transport-agnostic without pushing executable behaviour onto the wire — see
 * {@code engine-port.v1.yaml} {@code #operations.execute.request.definitionRef}.
 */
public record DefinitionRef(String capabilityName) implements Serializable {

    public DefinitionRef {
        if (capabilityName == null || capabilityName.isBlank()) {
            throw new IllegalArgumentException("capabilityName must not be blank");
        }
    }
}
