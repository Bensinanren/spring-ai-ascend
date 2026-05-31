package com.huawei.ascend.service.access.mq;

import com.huawei.ascend.service.access.gateway.AccessGateway;
import com.huawei.ascend.service.access.model.AccessAcceptedResponse;
import com.huawei.ascend.service.access.model.AccessIntent;

import java.util.Objects;

public final class MqIngressAdapter implements MqIngressQueue {

    private final AccessGateway accessGateway;

    public MqIngressAdapter(AccessGateway accessGateway) {
        this.accessGateway = Objects.requireNonNull(accessGateway, "accessGateway");
    }

    @Override
    public void enqueue(MqEnvelope envelope) {
        AccessIntent intent = accessGateway.acceptMq(envelope);
        AccessAcceptedResponse accepted = accessGateway.dispatch(intent).toCompletableFuture().join();
        if (accepted.accepted()) {
            accessGateway.bindEgress(intent, accepted);
        }
    }
}
