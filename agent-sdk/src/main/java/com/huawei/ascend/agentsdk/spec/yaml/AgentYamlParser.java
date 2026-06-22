package com.huawei.ascend.agentsdk.spec.yaml;

import com.huawei.ascend.agentsdk.spec.AgentSpec;
import com.huawei.ascend.agentsdk.spec.model.ModelSpec;
import com.huawei.ascend.agentsdk.spec.model.ModelRequestSpec;
import com.huawei.ascend.agentsdk.spec.mcp.McpSpec;
import com.huawei.ascend.agentsdk.spec.prompt.PromptSpec;
import com.huawei.ascend.agentsdk.spec.rail.RailSpec;
import com.huawei.ascend.agentsdk.spec.skill.SkillSourceLoader;
import com.huawei.ascend.agentsdk.spec.skill.SkillSourceSpec;
import com.huawei.ascend.agentsdk.spec.skill.SkillSpec;
import com.huawei.ascend.agentsdk.spec.tool.ToolRef;
import com.huawei.ascend.agentsdk.spec.tool.ToolSpec;
import com.huawei.ascend.agentsdk.support.DurationValues;
import com.huawei.ascend.agentsdk.support.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AgentYamlParser {
    private static final Set<String> FUNCTION_RAIL_EVENTS = Set.of(
            "beforeModelCall", "afterModelCall", "beforeToolCall", "afterToolCall");

    public AgentSpec parse(Map<String, Object> root, Path yamlPath) {
        Path yamlDir = yamlPath.toAbsolutePath().normalize().getParent();
        String schema = string(root.get("schema"));
        if (!"ascend-agent/v1".equals(schema)) {
            throw new ValidationException("Unsupported agent schema: " + schema);
        }
        String name = requiredString(root, "name");
        String displayName = defaultString(string(root.get("displayName")), name);
        String description = requiredString(root, "description");
        Map<String, Object> framework = requiredMap(map(root), "framework");
        String frameworkType = requiredString(framework, "type");
        String agentType = requiredString(framework, "agent");
        Map<String, Object> options = mapOrEmpty(framework.get("options"));
        ModelSpec modelSpec = model(mapOrEmpty(root.get("model")));
        PromptSpec promptSpec = prompt(mapOrEmpty(root.get("prompt")), yamlDir);
        List<SkillSourceSpec> skillSources = skillSources(mapOrEmpty(root.get("skills")), yamlDir);
        List<SkillSpec> skillSpecs = new SkillSourceLoader().load(skillSources);
        List<ToolSpec> toolSpecs = toolSpecs(listOrEmpty(root.get("tools")), yamlDir);
        List<RailSpec> railSpecs = railSpecs(listOrEmpty(root.get("rails")));
        List<McpSpec> mcpSpecs = mcpSpecs(listOrEmpty(root.get("mcps")));
        return new AgentSpec(
                schema,
                name,
                displayName,
                description,
                frameworkType,
                agentType,
                options,
                modelSpec,
                promptSpec,
                skillSpecs,
                toolSpecs,
                railSpecs,
                mcpSpecs);
    }

    private ModelSpec model(Map<String, Object> model) {
        return new ModelSpec(
                defaultString(string(model.get("provider")), "openai-compatible"),
                requiredString(model, "name", "model.name"),
                requiredString(model, "baseUrl", "model.baseUrl"),
                requiredString(model, "apiKey", "model.apiKey"),
                booleanValue(model.get("sslVerify"), true, "model.sslVerify"),
                stringMap(mapOrEmpty(model.get("headers"))),
                modelRequest(mapOrEmpty(model.get("request"))),
                DurationValues.duration(model.get("timeout"), null, "model.timeout"),
                optionalInt(model.get("maxRetries"), "model.maxRetries"));
    }

    private ModelRequestSpec modelRequest(Map<String, Object> request) {
        Map<String, Object> extra = new LinkedHashMap<>(request);
        extra.keySet().removeAll(Set.of("temperature", "topP", "maxTokens", "stop", "seed"));
        return new ModelRequestSpec(
                optionalDouble(request.get("temperature"), "model.request.temperature"),
                optionalDouble(request.get("topP"), "model.request.topP"),
                optionalInt(request.get("maxTokens"), "model.request.maxTokens"),
                string(request.get("stop")),
                optionalInt(request.get("seed"), "model.request.seed"),
                extra);
    }

    private PromptSpec prompt(Map<String, Object> prompt, Path yamlDir) {
        String agentMd = prompt.containsKey("agentMd") ? string(prompt.get("agentMd")) : null;
        String system = prompt.containsKey("system") ? string(prompt.get("system")) : null;
        String agentMdContent = agentMd == null || agentMd.isBlank() ? null : readAgentMd(agentMd, yamlDir);
        String systemContent = system == null || system.isBlank() ? null : system;
        return new PromptSpec(joinSections(agentMdContent, systemContent));
    }

    private static String readAgentMd(String agentMd, Path yamlDir) {
        Path resolved = resolvePath(yamlDir, agentMd);
        try {
            return java.nio.file.Files.readString(resolved);
        } catch (IOException error) {
            throw new ValidationException("Failed to read agentMd file: " + resolved, error);
        }
    }

    private static String joinSections(String first, String second) {
        if (first == null && second == null) {
            return "";
        }
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + "\n\n" + second;
    }

    private List<SkillSourceSpec> skillSources(Map<String, Object> skills, Path yamlDir) {
        List<Object> sources = listOrEmpty(skills.get("sources"));
        List<SkillSourceSpec> result = new ArrayList<>();
        for (Object source : sources) {
            if (source instanceof String path) {
                result.add(new SkillSourceSpec("filesystem", resolvePath(yamlDir, path)));
            } else {
                Map<String, Object> sourceMap = map(source);
                String type = defaultString(string(sourceMap.get("type")), "filesystem");
                result.add(new SkillSourceSpec(
                        type,
                        resolvePath(yamlDir, requiredString(sourceMap, "path"))));
            }
        }
        return List.copyOf(result);
    }

    private List<ToolSpec> toolSpecs(List<Object> tools, Path yamlDir) {
        List<ToolSpec> result = new ArrayList<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Object tool : tools) {
            Map<String, Object> toolMap = map(tool);
            String name = requiredString(toolMap, "name", "tools[].name");
            if (!names.add(name)) {
                // The name becomes the global tool registry key — a duplicate silently shadows.
                throw new ValidationException("Duplicate tool name: " + name);
            }
            Object rawRef = toolMap.get("ref");
            if (rawRef == null) {
                throw new ValidationException("Missing required YAML field: tools[].ref (tool: " + name + ")");
            }
            ToolRef ref = toolRef(rawRef, yamlDir);
            result.add(new ToolSpec(
                    name,
                    requiredString(toolMap, "description", "tools[].description (tool: " + name + ")"),
                    mapOrEmpty(toolMap.get("inputSchema")),
                    ref));
        }
        return List.copyOf(result);
    }

    private List<RailSpec> railSpecs(List<Object> rails) {
        List<RailSpec> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object rail : rails) {
            Map<String, Object> railMap = map(rail);
            String name = requiredString(railMap, "name", "rails[].name");
            if (!names.add(name)) {
                throw new ValidationException("Duplicate rail name: " + name);
            }
            String type = defaultString(string(railMap.get("type")), "class");
            String className = requiredString(railMap, "class", "rails[].class (rail: " + name + ")");
            String method = string(railMap.get("method"));
            String event = string(railMap.get("event"));
            if ("function".equalsIgnoreCase(type)) {
                if (method == null || method.isBlank()) {
                    throw new ValidationException("Missing required YAML field: rails[].method (rail: " + name + ")");
                }
                if (event == null || event.isBlank() || !FUNCTION_RAIL_EVENTS.contains(event)) {
                    throw new ValidationException("Unsupported function rail event: " + event);
                }
            } else if ("class".equalsIgnoreCase(type)) {
                if (method != null && !method.isBlank()) {
                    throw new ValidationException("class rail must not declare method: " + name);
                }
            } else {
                throw new ValidationException("Unsupported rail type: " + type);
            }
            result.add(new RailSpec(
                    name,
                    type.toLowerCase(java.util.Locale.ROOT),
                    className,
                    method,
                    event,
                    optionalInt(railMap.get("priority"), "rails[].priority"),
                    mapOrEmpty(railMap.get("options"))));
        }
        return List.copyOf(result);
    }

    private List<McpSpec> mcpSpecs(List<Object> mcps) {
        List<McpSpec> result = new ArrayList<>();
        for (Object mcp : mcps) {
            Map<String, Object> mcpMap = map(mcp);
            String serverName = requiredString(mcpMap, "serverName", "mcps[].serverName");
            result.add(new McpSpec(
                    defaultString(string(mcpMap.get("serverId")), UUID.randomUUID().toString()),
                    serverName,
                    requiredString(mcpMap, "serverPath", "mcps[].serverPath (server: " + serverName + ")"),
                    defaultString(string(mcpMap.get("clientType")), "sse"),
                    mapOrEmpty(mcpMap.get("params")),
                    stringMap(mapOrEmpty(mcpMap.get("authHeaders"))),
                    stringMap(mapOrEmpty(mcpMap.get("authQueryParams")))));
        }
        return List.copyOf(result);
    }

    private ToolRef toolRef(Object ref, Path yamlDir) {
        if (ref instanceof String value) {
            int split = value.indexOf(':');
            if (split <= 0) {
                throw new ValidationException("Tool ref must be scheme:value: " + value);
            }
            String scheme = value.substring(0, split);
            String rawValue = value.substring(split + 1);
            return new ToolRef(scheme, shorthandAttributes(scheme, rawValue, value));
        }
        Map<String, Object> refMap = map(ref);
        String scheme = requiredString(refMap, "type");
        Map<String, Object> attributes = new LinkedHashMap<>(refMap);
        attributes.remove("type");
        return new ToolRef(scheme, attributes);
    }

    /**
     * The string shorthand must produce the attribute keys the built-in resolvers
     * actually read (class/method, url, server/tool) — anything else parses fine
     * and then fails far away at agent build with an unrelated message.
     */
    private static Map<String, Object> shorthandAttributes(String scheme, String rawValue, String full) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        switch (scheme) {
            case "file" -> {
                int hash = rawValue.indexOf('#');
                if (hash <= 0 || hash == rawValue.length() - 1) {
                    throw new ValidationException(
                            "file: tool ref shorthand must be file:com.example.Class#method, got: " + full);
                }
                attributes.put("class", rawValue.substring(0, hash));
                attributes.put("method", rawValue.substring(hash + 1));
            }
            case "http" -> {
                if (rawValue.isBlank()) {
                    throw new ValidationException("http: tool ref shorthand must be http:<url>, got: " + full);
                }
                attributes.put("url", rawValue);
            }
            default -> attributes.put("value", rawValue);
        }
        return attributes;
    }

    private static Path resolvePath(Path base, String value) {
        Path path = Path.of(value);
        Path resolved = path.isAbsolute() ? path : base.resolve(path);
        Path normalized = resolved.toAbsolutePath().normalize();
        if ((value.endsWith(".yaml") || value.endsWith(".yml")) && !Files.exists(normalized)) {
            throw new ValidationException("Referenced file does not exist: " + normalized);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        throw new ValidationException("Expected YAML object, got: " + value);
    }

    static Map<String, Object> mapOrEmpty(Object value) {
        return value == null ? Map.of() : map(value);
    }

    static List<Object> listOrEmpty(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        throw new ValidationException("Expected YAML list, got: " + value);
    }

    static String requiredString(Map<String, Object> map, String key) {
        return requiredString(map, key, key);
    }

    static String requiredString(Map<String, Object> map, String key, String label) {
        String value = string(map.get(key));
        if (value == null || value.isBlank()) {
            throw new ValidationException("Missing required YAML field: " + label);
        }
        return value;
    }

    static String requiredString(Object root, String key) {
        return requiredString(map(root), key);
    }

    static Map<String, Object> requiredMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value == null) {
            throw new ValidationException("Missing required YAML section: " + key);
        }
        return map(value);
    }

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static boolean booleanValue(Object value, boolean fallback, String label) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        // Boolean.parseBoolean would map 'yes'/'enabled'/typos to false silently.
        throw new ValidationException("Field '" + label + "' must be true or false, got: " + value);
    }

    static Integer optionalInt(Object value, String label) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            throw new ValidationException("Field '" + label + "' must be an integer, got: " + value, error);
        }
    }

    static Double optionalDouble(Object value, String label) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            throw new ValidationException("Field '" + label + "' must be a number, got: " + value, error);
        }
    }

    static Map<String, String> stringMap(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                result.put(key, String.valueOf(value));
            }
        });
        return Map.copyOf(result);
    }
}
