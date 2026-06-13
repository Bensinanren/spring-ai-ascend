package com.huawei.ascend.runtime.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.runtime.engine.spi.TrajectoryEvent.Kind;
import org.junit.jupiter.api.Test;

class TrajectoryEventTest {

    @Test
    void mandatoryKindsAreTheCrossFrameworkCore() {
        assertThat(TrajectoryEvent.MANDATORY_KINDS).containsExactlyInAnyOrder(
                Kind.RUN_START, Kind.RUN_END, Kind.TOOL_CALL_START, Kind.TOOL_CALL_END, Kind.ERROR);
    }

    @Test
    void draftFactoriesCarryTheRightKindAndPayload() {
        assertThat(TrajectoryDraft.runStart().kind()).isEqualTo(Kind.RUN_START);
        assertThat(TrajectoryDraft.runEnd().kind()).isEqualTo(Kind.RUN_END);
        assertThat(TrajectoryDraft.toolCallStart("search", "q").kind()).isEqualTo(Kind.TOOL_CALL_START);
        assertThat(TrajectoryDraft.toolCallStart("search", "q").name()).isEqualTo("search");
        assertThat(TrajectoryDraft.toolCallStart("search", "q").args()).isEqualTo("q");

        TrajectoryDraft end = TrajectoryDraft.modelCallEnd(
                new TrajectoryEvent.Usage(10, 20, 5.0, "m"), "stop", "thinking");
        assertThat(end.kind()).isEqualTo(Kind.MODEL_CALL_END);
        assertThat(end.usage().inputTokens()).isEqualTo(10);
        assertThat(end.reasoning()).isEqualTo("thinking");

        TrajectoryDraft error = TrajectoryDraft.error("tool", "CODE", "boom", 2, true);
        assertThat(error.kind()).isEqualTo(Kind.ERROR);
        assertThat(error.error().code()).isEqualTo("CODE");
        assertThat(error.attempt()).isEqualTo(2);
        assertThat(error.retryable()).isTrue();
        // 5-arg overload always defaults to UNKNOWN so existing callers get a valid category.
        assertThat(error.error().category()).isEqualTo(ErrorCategory.UNKNOWN);
    }

    @Test
    void sixArgErrorOverloadPreservesExplicitCategory() {
        TrajectoryDraft draft = TrajectoryDraft.error("model", "RATE_LIMIT", "quota exceeded",
                ErrorCategory.RATE_LIMITED, 1, true);
        assertThat(draft.error().category()).isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(draft.error().code()).isEqualTo("RATE_LIMIT");
        assertThat(draft.error().message()).isEqualTo("quota exceeded");
    }
}
