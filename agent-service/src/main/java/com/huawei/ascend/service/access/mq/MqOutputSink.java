package com.huawei.ascend.service.access.mq;

import com.huawei.ascend.service.access.model.EgressBinding;
import com.huawei.ascend.service.access.model.NotificationFrame;

public interface MqOutputSink {
    void send(EgressBinding binding, NotificationFrame frame);
}
