package com.huawei.ascend.service.access.port;

import com.huawei.ascend.service.access.model.NotificationFrame;

public interface NotificationPort {
    void notify(NotificationFrame frame);
}
