package com.huawei.ascend.service.access.a2a;

import java.util.List;
import java.util.Map;

public record A2aAgentCard(
        String name,
        String description,
        String url,
        String version,
        String documentationUrl,
        String provider,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<A2aAgentSkill> skills,
        List<A2aAgentInterface> interfaces,
        String preferredTransport,
        Map<String, Object> securitySchemes,
        List<Map<String, List<String>>> security,
        Map<String, Object> metadata) {

    public A2aAgentCard {
        defaultInputModes = defaultInputModes == null ? List.of() : List.copyOf(defaultInputModes);
        defaultOutputModes = defaultOutputModes == null ? List.of() : List.copyOf(defaultOutputModes);
        skills = skills == null ? List.of() : List.copyOf(skills);
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        securitySchemes = securitySchemes == null ? Map.of() : Map.copyOf(securitySchemes);
        security = security == null ? List.of() : List.copyOf(security);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
