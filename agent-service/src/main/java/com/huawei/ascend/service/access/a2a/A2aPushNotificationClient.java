package com.huawei.ascend.service.access.a2a;

public interface A2aPushNotificationClient {
    void push(String target, A2aOutput output);
}
