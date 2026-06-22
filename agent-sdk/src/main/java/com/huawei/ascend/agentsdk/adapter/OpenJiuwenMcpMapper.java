package com.huawei.ascend.agentsdk.adapter;

import com.huawei.ascend.agentsdk.spec.mcp.McpSpec;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import java.util.List;

public final class OpenJiuwenMcpMapper {

    public List<McpServerConfig> toMcpServerConfigs(List<McpSpec> specs) {
        return specs.stream()
                .map(this::toMcpServerConfig)
                .toList();
    }

    private McpServerConfig toMcpServerConfig(McpSpec spec) {
        return McpServerConfig.builder()
                .serverId(spec.serverId())
                .serverName(spec.serverName())
                .serverPath(spec.serverPath())
                .clientType(spec.clientType())
                .params(spec.params())
                .authHeaders(spec.authHeaders())
                .authQueryParams(spec.authQueryParams())
                .build();
    }
}
