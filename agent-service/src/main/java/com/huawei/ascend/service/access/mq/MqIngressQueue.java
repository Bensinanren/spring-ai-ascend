package com.huawei.ascend.service.access.mq;

public interface MqIngressQueue {
    void enqueue(MqEnvelope envelope);
}
