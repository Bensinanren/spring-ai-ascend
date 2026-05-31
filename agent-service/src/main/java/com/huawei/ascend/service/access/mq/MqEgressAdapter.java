package com.huawei.ascend.service.access.mq;

import com.huawei.ascend.service.access.egress.EgressAdapter;
import com.huawei.ascend.service.access.model.EgressBinding;
import com.huawei.ascend.service.access.model.NotificationFrame;
import com.huawei.ascend.service.access.model.ReplyChannel;

import java.util.Objects;

public final class MqEgressAdapter implements EgressAdapter {

    private final MqOutputSink outputSink;

    public MqEgressAdapter(MqOutputSink outputSink) {
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
    }

    @Override
    public ReplyChannel channel() {
        return ReplyChannel.MQ;
    }

    @Override
    public void deliver(EgressBinding binding, NotificationFrame frame) {
        outputSink.send(binding, frame);
    }
}
