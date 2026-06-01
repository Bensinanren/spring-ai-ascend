package com.huawei.ascend.service.engine.dispatch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.huawei.ascend.service.engine.event.EngineCommandEvent;
import com.huawei.ascend.service.engine.event.EngineCompletedEvent;
import com.huawei.ascend.service.engine.event.EngineExecutionEvent;
import com.huawei.ascend.service.engine.event.EngineFailedEvent;
import com.huawei.ascend.service.engine.event.EngineOutputEvent;
import com.huawei.ascend.service.engine.event.EngineStartedEvent;
import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import com.huawei.ascend.service.engine.model.EngineExecutionScope;
import com.huawei.ascend.service.engine.model.EngineInput;
import com.huawei.ascend.service.engine.model.EngineOutput;
import com.huawei.ascend.service.engine.spi.AccessLayerClient;
import com.huawei.ascend.service.engine.spi.AgentHandler;
import com.huawei.ascend.service.engine.spi.TaskControlClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EngineDispatcherTest {

    private EngineExecutionScope scope() {
        return new EngineExecutionScope("t1", "u1", "s1", "task-1", "echo-agent");
    }

    private EngineCommandEvent cmd() {
        EngineInput in = new EngineInput("text", List.of(), Map.of());
        return new EngineCommandEvent("EXECUTE", scope(), in, Instant.EPOCH);
    }

    @Test
    void dispatch_completedEvent_routesToMarkRunningSucceededAndCompleteOutput() {
        TaskControlClient task = mock(TaskControlClient.class);
        AccessLayerClient access = mock(AccessLayerClient.class);
        AgentHandlerRegistry registry = new DefaultAgentHandlerRegistry();
        registry.register("echo-agent", new FakeAgentHandler(List.of(
                new EngineStartedEvent("e1", scope(), Instant.EPOCH),
                new EngineCompletedEvent("e2", scope(), Instant.EPOCH, new EngineOutput("hi", true)))));
        EngineDispatcher dispatcher = new EngineDispatcher(registry, task, access);

        dispatcher.dispatch(cmd());

        verify(task).markRunning(scope());
        verify(task).markSucceeded(org.mockito.ArgumentMatchers.eq(scope()), org.mockito.ArgumentMatchers.any(EngineCompletedEvent.class));
        verify(access).completeOutput(org.mockito.ArgumentMatchers.eq(scope()), org.mockito.ArgumentMatchers.any(EngineCompletedEvent.class));
    }

    @Test
    void dispatch_outputThenFailed_routesAppendOutputAndMarkFailed() {
        TaskControlClient task = mock(TaskControlClient.class);
        AccessLayerClient access = mock(AccessLayerClient.class);
        AgentHandlerRegistry registry = new DefaultAgentHandlerRegistry();
        registry.register("echo-agent", new FakeAgentHandler(List.of(
                new EngineStartedEvent("e1", scope(), Instant.EPOCH),
                new EngineOutputEvent("e2", scope(), Instant.EPOCH, new EngineOutput("partial", false)),
                new EngineFailedEvent("e3", scope(), Instant.EPOCH, "ERR", "boom"))));
        EngineDispatcher dispatcher = new EngineDispatcher(registry, task, access);

        dispatcher.dispatch(cmd());

        verify(task).markRunning(scope());
        verify(access).appendOutput(org.mockito.ArgumentMatchers.eq(scope()), org.mockito.ArgumentMatchers.any(EngineOutputEvent.class));
        verify(task).markFailed(org.mockito.ArgumentMatchers.eq(scope()), org.mockito.ArgumentMatchers.any(EngineFailedEvent.class));
    }

    static class FakeAgentHandler implements AgentHandler {
        private final List<EngineExecutionEvent> events;

        FakeAgentHandler(List<EngineExecutionEvent> events) {
            this.events = events;
        }

        @Override
        public String agentId() {
            return "echo-agent";
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public Stream<EngineExecutionEvent> execute(AgentExecutionContext context) {
            return events.stream();
        }
    }
}
