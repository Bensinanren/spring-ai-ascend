package com.huawei.ascend.agentsdk.spec.rail;

import java.util.Map;

public record RailSpec(
        String name,
        String type,
        String className,
        String method,
        String event,
        Integer priority,
        Map<String, Object> options) {

    public RailSpec {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
