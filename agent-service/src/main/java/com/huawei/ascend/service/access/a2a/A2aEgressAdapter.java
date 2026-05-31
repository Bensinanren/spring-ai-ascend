package com.huawei.ascend.service.access.a2a;

import com.huawei.ascend.service.access.egress.EgressAdapter;
import com.huawei.ascend.service.access.model.EgressBinding;
import com.huawei.ascend.service.access.model.NotificationFrame;
import com.huawei.ascend.service.access.model.ReplyChannel;

import java.util.Map;
import java.util.Objects;

public final class A2aEgressAdapter implements EgressAdapter {

    private final A2aOutputSink outputSink;

    public A2aEgressAdapter(A2aOutputSink outputSink) {
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
    }

    @Override
    public ReplyChannel channel() {
        return ReplyChannel.A2A;
    }

    @Override
    public void deliver(EgressBinding binding, NotificationFrame frame) {
        outputSink.send(binding, toA2aOutput(frame));
    }

    public A2aOutput toA2aOutput(NotificationFrame frame) {
        String kind = switch (frame.type()) {
            case ACK -> "TaskStatus";
            case TOOL_RESULT -> "Artifact";
            case LLM_RESULT -> "Message";
            case ERROR -> "error";
        };
        return new A2aOutput(
                kind,
                frame.taskId(),
                frame.payload(),
                frame.terminal(),
                Map.of("notificationType", frame.type().name()));
    }
}
