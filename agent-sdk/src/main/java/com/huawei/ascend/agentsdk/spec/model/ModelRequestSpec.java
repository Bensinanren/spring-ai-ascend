package com.huawei.ascend.agentsdk.spec.model;

import java.util.Map;

public record ModelRequestSpec(
        Double temperature,
        Double topP,
        Integer maxTokens,
        String stop,
        Integer seed,
        Map<String, Object> extra) {

    public ModelRequestSpec {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static ModelRequestSpec empty() {
        return new ModelRequestSpec(null, null, null, null, null, Map.of());
    }

    public boolean isEmpty() {
        return temperature == null
                && topP == null
                && maxTokens == null
                && stop == null
                && seed == null
                && extra.isEmpty();
    }
}
