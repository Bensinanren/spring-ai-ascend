package com.huawei.ascend.runtime.engine.spi;

import com.huawei.ascend.runtime.common.RuntimeIdentity;
import com.huawei.ascend.runtime.engine.spi.TrajectoryEvent.ErrorInfo;
import com.huawei.ascend.runtime.engine.spi.TrajectoryEvent.Kind;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns adapter-supplied {@link TrajectoryDraft}s into fully-stamped
 * {@link TrajectoryEvent}s — assigns a monotonic {@code seq}, the {@code contextId}/
 * {@code taskId} correlation, the {@code traceId}/{@code spanId}/{@code parentSpanId}
 * span tree, wall-clock {@code tsEpochMillis}/{@code durationMs}, and the schema
 * version; drops kinds the handler does not support; masks payloads — and hands each
 * event synchronously to the sink. One instance per invocation.
 *
 * <p>{@code emit} is synchronized: emission normally happens on the execute thread,
 * but a framework may fire callbacks from its own worker (openJiuwen's streaming mode
 * runs rails on a spawned thread), and the seq counter and span stack must observe a
 * single total order either way.
 *
 * <p>Span ids are derived from a per-invocation stack so adapters keep emitting only
 * {@code _START}/{@code _END}/point drafts: a {@code _START} pushes a child of the
 * current open span, the matching {@code _END} reuses that span's id and reports its
 * duration, and a point event hangs off the nearest open span. The stack is maintained
 * for <i>every</i> draft, even a capability-filtered one, so dropping an unsupported
 * span never desyncs the parent chain of the events that publish.
 *
 * <p><b>Payload-ref store:</b> when a {@link PayloadRefStore} is configured in
 * settings, opted-in STRING slot values longer than {@code payloadRefThreshold} are
 * persisted out-of-band (after secret-redaction) and replaced by a
 * {@code payload_ref://...} URI; see {@link #maybeRefize}.
 */
public final class StampingTrajectoryEmitter implements TrajectoryEmitter {

    private final TrajectorySink sink;
    private final String tenantId;
    private final String contextId;
    private final String taskId;
    private final String parentTaskId;
    private final String parentTraceId;
    private final TrajectorySettings settings;
    private final Set<Kind> supportedKinds;
    private final boolean keptInvocation;
    private long seq;
    private final Deque<SpanFrame> spanStack = new ArrayDeque<>();

    public StampingTrajectoryEmitter(TrajectorySink sink, RuntimeIdentity scope,
            TrajectorySettings settings, Set<Kind> supportedKinds) {
        this(sink, scope, settings, supportedKinds, null, null);
    }

    public StampingTrajectoryEmitter(TrajectorySink sink, RuntimeIdentity scope,
            TrajectorySettings settings, Set<Kind> supportedKinds,
            String parentTaskId, String parentTraceId) {
        this.sink = sink;
        this.tenantId = scope != null ? scope.tenantId() : null;
        this.contextId = scope != null ? scope.sessionId() : null;
        this.taskId = scope != null ? scope.taskId() : null;
        this.parentTaskId = parentTaskId;
        this.parentTraceId = parentTraceId;
        this.settings = settings;
        this.supportedKinds = supportedKinds;
        this.keptInvocation = settings.sampleRate() >= 1.0
                || ThreadLocalRandom.current().nextDouble() < settings.sampleRate();
    }

    @Override
    public synchronized void emit(TrajectoryDraft draft) {
        if (draft == null) {
            return;
        }
        Kind kind = draft.kind();
        boolean publish = kind != null && supportedKinds.contains(kind) && samplingAllows(kind);
        long now = System.currentTimeMillis();
        // Maintain the span stack for every draft, even a filtered one, so a dropped
        // unsupported span never desyncs the parent chain of what does publish.
        SpanInfo span = computeSpan(kind, publish, now);
        if (!publish) {
            return;
        }
        long eventSeq = seq++;
        String kindName = String.valueOf(kind);
        Object args = maybeRefize(kindName, "args", draft.args(),
                redact(kindName, "args", draft.args()), eventSeq);
        Object result = maybeRefize(kindName, "result", draft.result(),
                redact(kindName, "result", draft.result()), eventSeq);
        // reasoning is typed as String on TrajectoryEvent; redact then optionally ref-ize.
        Object reasoningRedacted = redact(kindName, "reasoning", draft.reasoning());
        Object reasoningFinal = maybeRefize(kindName, "reasoning", draft.reasoning(),
                reasoningRedacted, eventSeq);
        String reasoningStr = reasoningFinal != null ? String.valueOf(reasoningFinal) : null;
        ErrorInfo error = maskError(kindName, draft.error());
        sink.accept(new TrajectoryEvent(
                eventSeq,
                kind,
                now,
                span.durationMs(),
                taskId,
                span.spanId(),
                span.parentSpanId(),
                tenantId,
                contextId,
                taskId,
                draft.object(),
                draft.name(),
                args,
                result,
                draft.usage(),
                draft.attempt(),
                draft.retryable(),
                error,
                reasoningStr,
                draft.finishReason(),
                parentTaskId,
                parentTraceId,
                TrajectoryEvent.SCHEMA_VERSION));
    }

    /**
     * Derives the span ids for one draft and advances the span stack. A {@code _START}
     * pushes a child of the nearest emitted ancestor; the matching {@code _END} reuses
     * that span's id (and its original parent) and reports the elapsed duration; a point
     * event hangs off the nearest emitted ancestor with a fresh id. {@code published}
     * records whether this START will be emitted, so a filtered span is skipped when a
     * later event resolves its parent. Never throws — trajectory must not break the run.
     */
    private SpanInfo computeSpan(Kind kind, boolean published, long now) {
        if (kind == null) {
            return new SpanInfo(newSpanId(), currentPublishedSpanId(), null);
        }
        return switch (kind) {
            case RUN_START, MODEL_CALL_START, TOOL_CALL_START -> {
                String parent = currentPublishedSpanId();
                String spanId = newSpanId();
                spanStack.push(new SpanFrame(spanId, parent, now, kind, published));
                yield new SpanInfo(spanId, parent, null);
            }
            case RUN_END, MODEL_CALL_END, TOOL_CALL_END -> {
                SpanFrame frame = matchAndPop(startOf(kind));
                if (frame != null) {
                    yield new SpanInfo(frame.spanId(), frame.parentSpanId(), now - frame.startMillis());
                }
                yield new SpanInfo(newSpanId(), currentPublishedSpanId(), null);
            }
            default -> new SpanInfo(newSpanId(), currentPublishedSpanId(), null);
        };
    }

    /** Nearest open span that was itself emitted — skips capability-filtered ancestors. */
    private String currentPublishedSpanId() {
        for (SpanFrame frame : spanStack) {
            if (frame.published()) {
                return frame.spanId();
            }
        }
        return null;
    }

    /** Pops the matching open START (top first, else nearest below) — tolerant of unbalanced ends. */
    private SpanFrame matchAndPop(Kind startKind) {
        if (startKind == null) {
            return null;
        }
        SpanFrame top = spanStack.peek();
        if (top != null && top.startKind() == startKind) {
            return spanStack.pop();
        }
        for (Iterator<SpanFrame> it = spanStack.iterator(); it.hasNext(); ) {
            SpanFrame frame = it.next();
            if (frame.startKind() == startKind) {
                it.remove();
                return frame;
            }
        }
        return null;
    }

    private static Kind startOf(Kind endKind) {
        return switch (endKind) {
            case RUN_END -> Kind.RUN_START;
            case MODEL_CALL_END -> Kind.MODEL_CALL_START;
            case TOOL_CALL_END -> Kind.TOOL_CALL_START;
            default -> null;
        };
    }

    private static String newSpanId() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }

    /** One open span: its id, the parent it was opened under, its start time, and whether it emits. */
    private record SpanFrame(String spanId, String parentSpanId, long startMillis, Kind startKind, boolean published) {}

    private record SpanInfo(String spanId, String parentSpanId, Long durationMs) {}

    /** RUN_START, RUN_END, and ERROR are always emitted regardless of the sample roll. */
    private static boolean isAlwaysKept(Kind kind) {
        return kind == Kind.RUN_START || kind == Kind.RUN_END || kind == Kind.ERROR;
    }

    /** Returns true when the kind should be published under the current sampling decision. */
    private boolean samplingAllows(Kind kind) {
        return isAlwaysKept(kind) || keptInvocation;
    }

    /**
     * Routes one payload slot through the configured {@link Redactor} when present, or
     * falls back to {@link TrajectoryMasking#mask} — preserving the default behaviour when
     * no custom redactor is wired.
     */
    private Object redact(String eventKind, String fieldPath, Object value) {
        if (settings.redactor() != null) {
            return settings.redactor().redact(eventKind, fieldPath, value);
        }
        return TrajectoryMasking.mask(value, settings.maskKeyPattern(), settings.truncateChars());
    }

    /**
     * Applies payload-ref storage for over-threshold STRING slots when the store is
     * configured and the field is opted in. Only top-level {@link CharSequence} raw
     * values are eligible (prompt/completion use-case); Maps and Lists go through normal
     * redaction only.
     *
     * <p>Invariant: secrets are redacted BEFORE content reaches the store. The full
     * redacted string is produced without truncation — for the default (no custom
     * {@link Redactor}) path that is the raw string itself (bare strings have no map
     * keys, so key-name masking is a no-op). For the custom-redactor path the redactor's
     * own output is stored — note that if the redactor itself truncates, the stored
     * content may be truncated (accepted limitation; documented here).
     *
     * @param eventKind     the kind string, forwarded to a custom redactor if present
     * @param fieldPath     slot name, e.g. {@code "result"}
     * @param rawValue      original draft value before any masking
     * @param normalRedacted value already produced by {@link #redact} (masked+truncated)
     * @param eventSeq      this event's own seq number, used to name the stored file
     * @return a {@code payload_ref://} URI replacing the inline value, or
     *         {@code normalRedacted} when ref-izing is not applicable or storage fails
     */
    private Object maybeRefize(String eventKind, String fieldPath, Object rawValue,
            Object normalRedacted, long eventSeq) {
        PayloadRefStore store = settings.payloadRefStore();
        if (store == null || !settings.payloadRefFields().contains(fieldPath)) {
            return normalRedacted;
        }
        if (!(rawValue instanceof CharSequence rawSeq)) {
            return normalRedacted;
        }
        String raw = rawSeq.toString();
        int threshold = settings.payloadRefThreshold();
        if (threshold <= 0 || raw.length() <= threshold) {
            return normalRedacted;
        }
        // Produce full secret-redacted content without truncation (secrets-before-store invariant).
        String fullRedacted;
        if (settings.redactor() != null) {
            // Custom redactor path: call it and stringify. Its own truncation may apply.
            Object redactedObj = settings.redactor().redact(eventKind, fieldPath, raw);
            fullRedacted = redactedObj != null ? String.valueOf(redactedObj) : raw;
        } else {
            // Default key-name path: mask with truncateChars=0 (no truncation). A bare
            // string has no map keys, so this effectively returns the raw string unchanged.
            Object redactedObj = TrajectoryMasking.mask(raw, settings.maskKeyPattern(), 0);
            fullRedacted = redactedObj != null ? String.valueOf(redactedObj) : raw;
        }
        String ref = store.store(contextId, taskId, eventSeq, fieldPath, fullRedacted);
        return ref != null ? ref : normalRedacted;
    }

    /** Free-text error messages can embed secrets; run the message through the same masker. */
    private ErrorInfo maskError(String eventKind, ErrorInfo error) {
        if (error == null || error.message() == null) {
            return error;
        }
        Object masked = redact(eventKind, "error.message", error.message());
        return new ErrorInfo(error.code(), masked != null ? String.valueOf(masked) : null, error.category());
    }
}
