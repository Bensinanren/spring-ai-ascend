package com.huawei.ascend.service.engine.support;

import com.huawei.ascend.service.engine.event.EngineCancelledEvent;
import com.huawei.ascend.service.engine.event.EngineCompletedEvent;
import com.huawei.ascend.service.engine.event.EngineFailedEvent;
import com.huawei.ascend.service.engine.event.EngineInterruptedEvent;
import com.huawei.ascend.service.engine.model.EngineExecutionScope;
import com.huawei.ascend.service.engine.spi.TaskControlClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link TaskControlClient} for tests: records every lifecycle
 * transition so assertions can verify routing without the real
 * task-centric-control module.
 */
public class RecordingTaskControlClient implements TaskControlClient {

    public final List<String> transitions = new ArrayList<>();
    public final List<EngineCompletedEvent> succeeded = new ArrayList<>();
    public final List<EngineFailedEvent> failed = new ArrayList<>();
    public final List<EngineInterruptedEvent> waiting = new ArrayList<>();
    public final List<EngineCancelledEvent> cancelled = new ArrayList<>();
    private final AtomicInteger childSeq = new AtomicInteger();

    @Override
    public void markRunning(EngineExecutionScope scope) {
        transitions.add("RUNNING:" + scope.taskId());
    }

    @Override
    public void markWaiting(EngineExecutionScope scope, EngineInterruptedEvent event) {
        transitions.add("WAITING:" + scope.taskId());
        waiting.add(event);
    }

    @Override
    public void markSucceeded(EngineExecutionScope scope, EngineCompletedEvent event) {
        transitions.add("SUCCEEDED:" + scope.taskId());
        succeeded.add(event);
    }

    @Override
    public void markFailed(EngineExecutionScope scope, EngineFailedEvent event) {
        transitions.add("FAILED:" + scope.taskId());
        failed.add(event);
    }

    @Override
    public void markCancelled(EngineExecutionScope scope, EngineCancelledEvent event) {
        transitions.add("CANCELLED:" + scope.taskId());
        cancelled.add(event);
    }

    @Override
    public EngineExecutionScope createChildTask(EngineExecutionScope parentScope, String targetAgentId, String input) {
        String childTaskId = parentScope.taskId() + "-child-" + childSeq.incrementAndGet();
        return new EngineExecutionScope(parentScope.tenantId(), parentScope.userId(),
                parentScope.sessionId(), childTaskId, targetAgentId);
    }
}
