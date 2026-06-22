package com.huawei.ascend.agentsdk.spec.tool;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record HttpExecutionHandle(
        URI url,
        String method,
        Map<String, String> headers,
        Duration timeout,
        boolean followRedirects,
        int maxResponseBytes,
        boolean exposeErrorBody) implements ExecutionHandle {

    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    public HttpExecutionHandle(URI url, String method, Map<String, String> headers, Duration timeout) {
        this(url, method, headers, timeout, false, DEFAULT_MAX_RESPONSE_BYTES, false);
    }

    public HttpExecutionHandle {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (maxResponseBytes <= 0) {
            throw new com.huawei.ascend.agentsdk.support.ValidationException(
                    "Tool ref maxResponseBytes must be positive, got: " + maxResponseBytes);
        }
    }
}
