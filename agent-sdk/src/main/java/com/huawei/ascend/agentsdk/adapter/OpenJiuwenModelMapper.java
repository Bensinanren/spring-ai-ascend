package com.huawei.ascend.agentsdk.adapter;

import com.huawei.ascend.agentsdk.spec.model.ModelRequestSpec;
import com.huawei.ascend.agentsdk.spec.model.ModelSpec;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OpenJiuwenModelMapper {

    public ModelClientConfig toModelClientConfig(ModelSpec spec) {
        ModelClientConfig.Builder builder = ModelClientConfig.builder()
                .clientProvider(spec.provider())
                .apiKey(spec.apiKey())
                .apiBase(spec.baseUrl())
                .verifySsl(spec.sslVerify())
                .headers(spec.headers());
        if (spec.timeout() != null) {
            builder.timeout(seconds(spec.timeout()));
        }
        if (spec.maxRetries() != null) {
            builder.maxRetries(spec.maxRetries());
        }
        return builder.build();
    }

    public ModelRequestConfig toModelRequestConfig(ModelRequestSpec spec) {
        ModelRequestConfig.ModelRequestConfigBuilder builder = ModelRequestConfig.builder()
                .extraFields(spec.extra());
        if (spec.temperature() != null) {
            builder.temperature(spec.temperature());
        }
        if (spec.topP() != null) {
            builder.topP(spec.topP());
        }
        if (spec.maxTokens() != null) {
            builder.maxTokens(spec.maxTokens());
        }
        if (spec.stop() != null) {
            builder.stop(spec.stop());
        }
        if (spec.seed() != null) {
            builder.seed(spec.seed());
        }
        return builder.build();
    }

    public Map<String, Object> toDeepAgentModelConfig(ModelSpec spec) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", spec.name());
        ModelRequestSpec request = spec.requestSpec();
        putIfPresent(model, "temperature", request.temperature());
        putIfPresent(model, "top_p", request.topP());
        putIfPresent(model, "max_tokens", request.maxTokens());
        putIfPresent(model, "stop", request.stop());
        putIfPresent(model, "seed", request.seed());
        model.putAll(request.extra());
        return model;
    }

    public Map<String, Object> toDeepAgentBackendConfig(ModelSpec spec) {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("provider", spec.provider());
        backend.put("apiKey", spec.apiKey());
        backend.put("baseUrl", spec.baseUrl());
        backend.put("verifySsl", spec.sslVerify());
        backend.put("headers", spec.headers());
        if (spec.timeout() != null) {
            backend.put("timeout", seconds(spec.timeout()));
        }
        if (spec.maxRetries() != null) {
            backend.put("max_retries", spec.maxRetries());
        }
        return backend;
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static double seconds(java.time.Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
