package com.huawei.ascend.service.engine.adapter.openjiuwen;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.service.engine.event.EngineCompletedEvent;
import com.huawei.ascend.service.engine.event.EngineExecutionEvent;
import com.huawei.ascend.service.engine.event.EngineFailedEvent;
import com.huawei.ascend.service.engine.event.EngineInterruptedEvent;
import com.huawei.ascend.service.engine.model.EngineExecutionScope;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenJiuwenResultMapperTest {

    private final OpenJiuwenResultMapper mapper =
            new OpenJiuwenResultMapper(() -> "event-id", () -> Instant.EPOCH);

    private EngineExecutionScope scope() {
        return new EngineExecutionScope("t", "u", "s", "task-1", "echo-agent");
    }

    @Test
    void map_answer_toCompletedWithOutput() {
        EngineExecutionEvent event = mapper.map(scope(), Map.of("result_type", "answer", "output", "hi"));

        assertThat(event).isInstanceOf(EngineCompletedEvent.class);
        assertThat(((EngineCompletedEvent) event).getFinalOutput().getContent()).isEqualTo("hi");
        assertThat(((EngineCompletedEvent) event).getFinalOutput().isFinalOutput()).isTrue();
    }

    @Test
    void map_error_toFailed() {
        EngineExecutionEvent event = mapper.map(scope(), Map.of("result_type", "error", "output", "boom"));

        assertThat(event).isInstanceOf(EngineFailedEvent.class);
        assertThat(((EngineFailedEvent) event).getErrorCode()).isEqualTo(OpenJiuwenResultMapper.ERROR_CODE);
        assertThat(((EngineFailedEvent) event).getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void map_interrupt_toInterrupted() {
        EngineExecutionEvent event = mapper.map(scope(), Map.of("result_type", "interrupt", "output", "need input"));

        assertThat(event).isInstanceOf(EngineInterruptedEvent.class);
        assertThat(((EngineInterruptedEvent) event).getPrompt()).isEqualTo("need input");
    }

    @Test
    void map_nullResult_toFailed() {
        EngineExecutionEvent event = mapper.map(scope(), null);

        assertThat(event).isInstanceOf(EngineFailedEvent.class);
    }
}
