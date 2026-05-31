package com.huawei.ascend.service.access.a2a;

import com.huawei.ascend.service.access.model.EgressBinding;

import java.util.Locale;
import java.util.Objects;

public final class DefaultA2aOutputSink implements A2aOutputSink {

    private final A2aOutputRegistry outputRegistry;
    private final A2aPushNotificationClient pushNotificationClient;

    public DefaultA2aOutputSink(
            A2aOutputRegistry outputRegistry,
            A2aPushNotificationClient pushNotificationClient) {
        this.outputRegistry = Objects.requireNonNull(outputRegistry, "outputRegistry");
        this.pushNotificationClient = Objects.requireNonNull(pushNotificationClient, "pushNotificationClient");
    }

    @Override
    public void send(EgressBinding binding, A2aOutput output) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(output, "output");
        A2aOutputHandle handle = new A2aOutputHandle(binding.tenantId(), binding.sessionId(), binding.taskId());
        outputRegistry.append(handle, output);
        if (resolveMode(binding) == A2aReplyMode.PUSH_NOTIFICATION) {
            pushNotificationClient.push(binding.targetRef(), output);
        }
    }

    private static A2aReplyMode resolveMode(EgressBinding binding) {
        String deliveryMode = binding.deliveryMode();
        if (deliveryMode == null || deliveryMode.isBlank()) {
            return A2aReplyMode.STREAM;
        }
        try {
            return A2aReplyMode.valueOf(deliveryMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return A2aReplyMode.STREAM;
        }
    }
}
