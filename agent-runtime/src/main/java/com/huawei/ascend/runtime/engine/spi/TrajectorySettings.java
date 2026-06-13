package com.huawei.ascend.runtime.engine.spi;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolved per-invocation trajectory settings handed to a {@link TrajectorySource}.
 * The runtime computes these from global configuration plus any per-request override
 * before opening the trajectory, so the adapter base never reads configuration itself.
 * When enabled, every supported kind is emitted with masked and truncated payloads.
 *
 * <p>{@code sampleRate} is a per-invocation Bernoulli probability in [0.0, 1.0].
 * {@code RUN_START}, {@code RUN_END}, and {@code ERROR} are always emitted regardless
 * of the sample decision; all other kinds are dropped on un-kept invocations.
 *
 * <p>{@code redactor} is optional. When {@code null} the emitter falls back to the
 * built-in key-name masking via {@link TrajectoryMasking#mask}, preserving byte-identical
 * default behaviour. A deployment supplies a {@link Redactor} bean to opt into
 * value-based recognition (card numbers, national IDs, GPS coordinate pairs, etc.).
 *
 * <p><b>Payload-ref store (opt-in):</b> when {@code payloadRefStore} is non-null,
 * {@code payloadRefFields} is non-empty, and {@code payloadRefThreshold} is positive,
 * over-threshold STRING slot values for the named fields are persisted out-of-band and
 * replaced in the trajectory by a {@code payload_ref://...} URI. Default ({@code off()}
 * and {@code basic()}) is no store — behaviour is byte-identical to before.
 */
public record TrajectorySettings(boolean enabled, Pattern maskKeyPattern, int truncateChars,
        double sampleRate, Redactor redactor,
        PayloadRefStore payloadRefStore, int payloadRefThreshold, Set<String> payloadRefFields) {

    /**
     * Canonical constructor — validates that {@code payloadRefFields} is never null so
     * callers can always call {@code payloadRefFields().contains(...)} safely.
     */
    public TrajectorySettings {
        if (payloadRefFields == null) {
            payloadRefFields = Set.of();
        }
    }

    /**
     * Backward-compatible convenience constructor for call sites that do not need
     * payload-ref: wraps the original five-field signature with no-op ref defaults.
     */
    public TrajectorySettings(boolean enabled, Pattern maskKeyPattern, int truncateChars,
            double sampleRate, Redactor redactor) {
        this(enabled, maskKeyPattern, truncateChars, sampleRate, redactor, null, 0, Set.of());
    }

    public static TrajectorySettings off() {
        return new TrajectorySettings(false, null, 0, 1.0, null, null, 0, Set.of());
    }

    /**
     * Convenience factory for fully-sampled settings ({@code sampleRate = 1.0}) with no
     * custom redactor. Use this in tests and any call site that does not need per-invocation
     * sampling or value-based recognition.
     */
    public static TrajectorySettings basic(boolean enabled, Pattern maskKeyPattern, int truncateChars) {
        return new TrajectorySettings(enabled, maskKeyPattern, truncateChars, 1.0, null, null, 0, Set.of());
    }
}
