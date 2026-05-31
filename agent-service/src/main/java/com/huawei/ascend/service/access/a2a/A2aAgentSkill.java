package com.huawei.ascend.service.access.a2a;

import java.util.List;
import java.util.Map;

public record A2aAgentSkill(
        String id,
        String name,
        String description,
        List<String> inputModes,
        List<String> outputModes,
        Map<String, Object> metadata) {

    public A2aAgentSkill {
        inputModes = inputModes == null ? List.of() : List.copyOf(inputModes);
        outputModes = outputModes == null ? List.of() : List.copyOf(outputModes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
