package com.huawei.ascend.agentsdk.spec.model;

import java.time.Duration;
import java.util.Map;

public record ModelSpec(
        String provider,
        String name,
        String baseUrl,
        String apiKey,
        boolean sslVerify,
        Map<String, String> headers,
        ModelRequestSpec requestSpec,
        Duration timeout,
        Integer maxRetries) {

    public ModelSpec {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        requestSpec = requestSpec == null ? ModelRequestSpec.empty() : requestSpec;
    }
}
