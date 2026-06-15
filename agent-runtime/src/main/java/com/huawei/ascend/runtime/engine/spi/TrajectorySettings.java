package com.huawei.ascend.runtime.engine.spi;

import java.util.regex.Pattern;

/**
 * Resolved per-invocation trajectory settings handed to a {@link TrajectorySource}.
 * The runtime computes these from global configuration plus any per-request override
 * before opening the trajectory, so the adapter base never reads configuration itself.
 * When enabled, every supported kind is emitted with masked and truncated payloads,
 * unless {@code sampleRate} drops the whole invocation (head sampling). {@code redactor}
 * applies value-level content redaction on top of key-name masking; {@code costCalculator}
 * fills model-call {@code provider}/{@code costMicros}. Both default to no-ops.
 */
public record TrajectorySettings(boolean enabled, Pattern maskKeyPattern, int truncateChars, double sampleRate,
        Redactor redactor, CostCalculator costCalculator) {

    public TrajectorySettings {
        if (redactor == null) {
            redactor = Redactor.NONE;
        }
        if (costCalculator == null) {
            costCalculator = CostCalculator.NONE;
        }
    }

    /** Full-sampling, no value-redaction/cost convenience — keeps pre-sampling call sites unchanged. */
    public TrajectorySettings(boolean enabled, Pattern maskKeyPattern, int truncateChars) {
        this(enabled, maskKeyPattern, truncateChars, 1.0, Redactor.NONE, CostCalculator.NONE);
    }

    /** No value-redaction/cost convenience — keeps pre-redaction call sites unchanged. */
    public TrajectorySettings(boolean enabled, Pattern maskKeyPattern, int truncateChars, double sampleRate) {
        this(enabled, maskKeyPattern, truncateChars, sampleRate, Redactor.NONE, CostCalculator.NONE);
    }

    public static TrajectorySettings off() {
        return new TrajectorySettings(false, null, 0, 1.0);
    }
}
