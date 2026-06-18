package com.huawei.ascend.agentsdk.adapter.deepagent;

import com.huawei.ascend.agentsdk.adapter.OpenJiuwenMcpMapper;
import com.huawei.ascend.agentsdk.adapter.OpenJiuwenModelMapper;
import com.huawei.ascend.agentsdk.adapter.OpenJiuwenRailMapper;
import com.huawei.ascend.agentsdk.adapter.OpenJiuwenAgentSpecMapper;
import com.huawei.ascend.agentsdk.adapter.OpenJiuwenSkillMapper;
import com.huawei.ascend.agentsdk.adapter.OpenJiuwenToolMapper;
import com.huawei.ascend.agentsdk.spec.AgentSpec;
import com.huawei.ascend.agentsdk.spec.tool.ResolvedTool;
import com.huawei.ascend.agentsdk.spec.tool.ToolResolver;
import com.huawei.ascend.agentsdk.spec.tool.ToolSpec;
import com.huawei.ascend.agentsdk.spec.tool.UnsupportedToolRefException;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import java.util.List;

public final class OpenJiuwenDeepAgentBuilder {
    private final List<ToolResolver> toolResolvers;
    private final List<AgentRail> rails;
    private final OpenJiuwenToolMapper toolMapper = new OpenJiuwenToolMapper();
    private final OpenJiuwenSkillMapper skillMapper = new OpenJiuwenSkillMapper();
    private final OpenJiuwenAgentSpecMapper specMapper = new OpenJiuwenAgentSpecMapper();
    private final OpenJiuwenModelMapper modelMapper = new OpenJiuwenModelMapper();
    private final OpenJiuwenRailMapper railMapper = new OpenJiuwenRailMapper();
    private final OpenJiuwenMcpMapper mcpMapper = new OpenJiuwenMcpMapper();

    public OpenJiuwenDeepAgentBuilder(List<ToolResolver> toolResolvers) {
        this(toolResolvers, List.of());
    }

    public OpenJiuwenDeepAgentBuilder(List<ToolResolver> toolResolvers, List<AgentRail> rails) {
        this.toolResolvers = List.copyOf(toolResolvers);
        this.rails = List.copyOf(rails);
    }

    public DeepAgent buildAgent(AgentSpec spec) {
        OpenJiuwenDeepAgentOptions options = OpenJiuwenDeepAgentOptions.from(spec.frameworkOptions());
        List<Tool> tools = spec.toolSpecs().stream()
                .map(this::resolve)
                .map(toolMapper::toTool)
                .toList();
        List<String> skillDirectories = skillMapper.toSkillRootDirectories(spec.skillSpecs());
        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(spec.promptSpec().system())
                .maxIterations(options.maxIterations())
                .tools(List.copyOf(toObjects(tools)))
                .skillDirectories(skillDirectories)
                .skillMode(options.skillMode())
                .workspacePath(options.workspacePath())
                .language(options.language())
                .enableTaskLoop(options.enableTaskLoop())
                .enableTaskPlanning(options.enableTaskPlanning())
                .completionTimeout(options.completionTimeout())
                .model(modelMapper.toDeepAgentModelConfig(spec.modelSpec()))
                .backend(modelMapper.toDeepAgentBackendConfig(spec.modelSpec()))
                .rails(rails(spec))
                .mcps(mcpMapper.toMcpServerConfigs(spec.mcpSpecs()))
                .build();
        Workspace workspace = Workspace.builder()
                .rootPath(options.workspacePath())
                .language(options.language())
                .build();
        return HarnessFactory.createDeepAgent(specMapper.card(spec), config, workspace);
    }

    private ResolvedTool resolve(ToolSpec spec) {
        return toolResolvers.stream()
                .filter(resolver -> resolver.supports(spec.ref().scheme()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedToolRefException(
                        "Unsupported tool ref scheme: " + spec.ref().scheme()))
                .resolve(spec);
    }

    private static List<Object> toObjects(List<Tool> tools) {
        return tools.stream().map(tool -> (Object) tool).toList();
    }

    private List<Object> rails(AgentSpec spec) {
        List<Object> result = new java.util.ArrayList<>();
        spec.railSpecs().stream()
                .map(railMapper::toDeepAgentRail)
                .forEach(result::add);
        result.addAll(rails);
        return List.copyOf(result);
    }
}
