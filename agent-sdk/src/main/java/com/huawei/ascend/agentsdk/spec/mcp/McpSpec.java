package com.huawei.ascend.agentsdk.spec.mcp;

import java.util.Map;

public record McpSpec(
        String serverId,
        String serverName,
        String serverPath,
        String clientType,
        Map<String, Object> params,
        Map<String, String> authHeaders,
        Map<String, String> authQueryParams) {

    public McpSpec {
        params = params == null ? Map.of() : Map.copyOf(params);
        authHeaders = authHeaders == null ? Map.of() : Map.copyOf(authHeaders);
        authQueryParams = authQueryParams == null ? Map.of() : Map.copyOf(authQueryParams);
    }
}
