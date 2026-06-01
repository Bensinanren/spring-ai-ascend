package com.huawei.ascend.service.engine.adapter.openjiuwen;

import com.huawei.ascend.service.engine.event.EngineCompletedEvent;
import com.huawei.ascend.service.engine.event.EngineExecutionEvent;
import com.huawei.ascend.service.engine.event.EngineFailedEvent;
import com.huawei.ascend.service.engine.event.EngineInterruptedEvent;
import com.huawei.ascend.service.engine.model.EngineExecutionScope;
import com.huawei.ascend.service.engine.model.EngineOutput;
import com.huawei.ascend.service.engine.model.InterruptType;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps openJiuwen's {@code Runner.runAgent} result map to a terminal engine
 * execution event, per the execution contract in design §10.4:
 * {@code result_type ∈ {answer, error, interrupt}} →
 * Completed / Failed / Interrupted.
 *
 * <p>Pure and framework-free so the mapping is unit-testable offline; the real
 * framework execution path is exercised by the sample's smoke IT.
 */
public class OpenJiuwenResultMapper {

    static final String ERROR_CODE = "OPENJIUWEN_ERROR";

    private final Supplier<String> eventIdSupplier;
    private final Supplier<Instant> clock;

    public OpenJiuwenResultMapper(Supplier<String> eventIdSupplier, Supplier<Instant> clock) {
        this.eventIdSupplier = eventIdSupplier;
        this.clock = clock;
    }

    public EngineExecutionEvent map(EngineExecutionScope scope, Map<String, Object> result) {
        String type = result == null ? null : asString(result.get("result_type"));
        String output = result == null ? "" : asString(result.get("output"));
        if ("answer".equals(type)) {
            return new EngineCompletedEvent(newId(), scope, now(), new EngineOutput(output, true));
        }
        if ("interrupt".equals(type)) {
            return new EngineInterruptedEvent(newId(), scope, now(), InterruptType.HUMAN_INPUT, output);
        }
        return new EngineFailedEvent(newId(), scope, now(), ERROR_CODE, output);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String newId() {
        return eventIdSupplier.get();
    }

    private Instant now() {
        return clock.get();
    }
}
