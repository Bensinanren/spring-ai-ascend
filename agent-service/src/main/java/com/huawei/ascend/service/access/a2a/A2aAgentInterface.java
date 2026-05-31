package com.huawei.ascend.service.access.a2a;

import java.util.Map;

public record A2aAgentInterface(
        String transport,
        String url,
        Map<String, Object> metadata) {

    public A2aAgentInterface {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
