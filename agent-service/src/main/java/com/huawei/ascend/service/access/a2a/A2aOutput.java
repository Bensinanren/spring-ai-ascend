package com.huawei.ascend.service.access.a2a;

import java.util.Map;

public record A2aOutput(
        String kind,
        String taskId,
        Object body,
        boolean terminal,
        Map<String, Object> metadata) {

    public A2aOutput {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
