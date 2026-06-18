package com.huawei.ascend.agentsdk.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentFactoryTest {

    @Test
    void convertsYamlToReactAgent() throws Exception {
        TestYaml yaml = exampleYaml("react");

        ReActAgent agent = AgentFactory.toReactAgent(yaml.path());

        assertThat(agent).isInstanceOf(ReActAgent.class);
        assertThat(agent.getCard().getId()).isEqualTo(yaml.agentId());
        assertRegisteredTools(yaml);
    }

    @Test
    void convertsYamlToDeepAgent() throws Exception {
        TestYaml yaml = exampleYaml("deepagent");

        DeepAgent agent = AgentFactory.toDeepAgent(yaml.path());

        assertThat(agent.getCard().getId()).isEqualTo(yaml.agentId());
        assertThat(agent.getConfig().getTools()).hasSize(2);
        assertThat(agent.getConfig().getSkillDirectories())
                .containsExactly(yaml.skillsRoot().toAbsolutePath().normalize().toString());
    }

    @Test
    void mapsDeepAgentP1FieldsToConfig() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path tempDir = testDirectory("deepagent-p1");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: deepP1%s
                description: deep p1
                framework:
                  type: openjiuwen
                  agent: deepagent
                  options:
                    maxIterations: 3
                    skillMode: none
                    workspacePath: ./workspace
                    language: en
                    enableTaskLoop: true
                    enableTaskPlanning: true
                    completionTimeout: 77
                model:
                  provider: openai-compatible
                  name: deepseek-chat
                  baseUrl: https://api.deepseek.com
                  apiKey: sk-test
                  timeout: 45s
                  maxRetries: 2
                  request:
                    temperature: 0.3
                    topP: 0.9
                    maxTokens: 2048
                    stop: END
                    seed: 9
                rails:
                  - name: audit
                    type: class
                    class: %s
                    priority: 12
                mcps:
                  - serverId: orders
                    serverName: order-mcp
                    serverPath: http://localhost:9000/sse
                    clientType: sse
                """.formatted(suffix, TestDeepRail.class.getName()));

        DeepAgent agent = AgentFactory.toDeepAgent(yaml);

        assertThat(agent.getConfig().getSkillMode()).isEqualTo("none");
        assertThat(agent.getConfig().getWorkspacePath()).endsWith("workspace");
        assertThat(agent.getConfig().getLanguage()).isEqualTo("en");
        assertThat(agent.getConfig().isTaskLoopEnabled()).isTrue();
        assertThat(agent.getConfig().isTaskPlanningEnabled()).isTrue();
        assertThat(agent.getConfig().getCompletionTimeout()).isEqualTo(77.0);
        Map<?, ?> model = (Map<?, ?>) agent.getConfig().getModel();
        assertThat(model.get("model")).isEqualTo("deepseek-chat");
        assertThat(model.get("temperature")).isEqualTo(0.3);
        assertThat(model.get("top_p")).isEqualTo(0.9);
        assertThat(model.get("max_tokens")).isEqualTo(2048);
        assertThat(model.get("stop")).isEqualTo("END");
        assertThat(model.get("seed")).isEqualTo(9);
        Map<?, ?> backend = (Map<?, ?>) agent.getConfig().getBackend();
        assertThat(backend.get("timeout")).isEqualTo(45.0);
        assertThat(backend.get("max_retries")).isEqualTo(2);
        assertThat(agent.getConfig().getRails())
                .anySatisfy(rail -> {
                    assertThat(rail).isInstanceOf(TestDeepRail.class);
                });
        assertThat(agent.getConfig().getMcps()).hasSize(1);
        assertThat(agent.getConfig().getMcps().get(0).getServerId()).isEqualTo("orders");
    }

    @Test
    void mapsReactModelAndRails() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path tempDir = testDirectory("react-p1");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: reactP1%s
                description: react p1
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  provider: openai-compatible
                  name: deepseek-chat
                  baseUrl: https://api.deepseek.com
                  apiKey: sk-test
                  timeout: 33s
                  maxRetries: 4
                  request:
                    temperature: 0.1
                    topP: 0.7
                    maxTokens: 512
                    stop: STOP
                    seed: 3
                rails:
                  - name: audit
                    type: class
                    class: %s
                    priority: 21
                """.formatted(suffix, TestReactRail.class.getName()));

        ReActAgent agent = AgentFactory.toReactAgent(yaml);
        ReActAgentConfig config = (ReActAgentConfig) agent.getConfig();

        assertThat(config.getModelClientConfig().getTimeout()).isEqualTo(33.0);
        assertThat(config.getModelClientConfig().getMaxRetries()).isEqualTo(4);
        assertThat(config.getModelConfigObj().getTemperature()).isEqualTo(0.1);
        assertThat(config.getModelConfigObj().getTopP()).isEqualTo(0.7);
        assertThat(config.getModelConfigObj().getMaxTokens()).isEqualTo(512);
        assertThat(config.getModelConfigObj().getStop()).isEqualTo("STOP");
        assertThat(config.getModelConfigObj().getSeed()).isEqualTo(3);
        assertThat(agent.getAgentCallbackManager()
                .hasHooks(com.openjiuwen.core.singleagent.rail.AgentCallbackEvent.BEFORE_MODEL_CALL)).isTrue();
    }

    @Test
    void builderCanInjectCodeLevelRailsIntoReactAndDeepAgent() throws Exception {
        TestYaml reactYaml = exampleYaml("react");
        TestYaml deepYaml = exampleYaml("deepagent");
        TestReactRail reactRail = new TestReactRail();
        TestDeepRail deepRail = new TestDeepRail();

        ReActAgent react = AgentFactory.builder()
                .rail(reactRail)
                .toReactAgent(reactYaml.path());
        DeepAgent deepAgent = AgentFactory.builder()
                .rail(deepRail)
                .toDeepAgent(deepYaml.path());

        assertThat(react.getAgentCallbackManager()
                .hasHooks(com.openjiuwen.core.singleagent.rail.AgentCallbackEvent.BEFORE_MODEL_CALL)).isTrue();
        assertThat(deepAgent.getConfig().getRails()).contains(deepRail);
    }

    private TestYaml exampleYaml(String agentType) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String agentId = "sdkExampleAgent" + suffix;
        String firstToolName = "firstTool" + suffix;
        String secondToolName = "secondTool" + suffix;
        Path tempDir = testDirectory(agentType);
        Path common = Files.createDirectories(tempDir.resolve("skills").resolve("common"));
        Files.writeString(common.resolve("SKILL.md"), "# Common\n");
        Path order = Files.createDirectories(tempDir.resolve("skills").resolve("order"));
        Files.writeString(order.resolve("SKILL.md"), "# Order\n");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: %s
                description: SDK example agent
                framework:
                  type: openjiuwen
                  agent: %s
                  options:
                    maxIterations: 3
                model:
                  provider: openai-compatible
                  name: deepseek-chat
                  baseUrl: https://api.deepseek.com
                  apiKey: sk-test
                prompt:
                  system: prove tools and skills
                skills:
                  sources:
                    - ./skills/common
                    - ./skills/order
                tools:
                  - name: %s
                    description: firstTool description
                    inputSchema:
                      type: object
                    ref:
                      type: file
                      class: %s
                      method: first
                  - name: %s
                    description: secondTool description
                    inputSchema:
                      type: object
                    ref:
                      type: file
                      class: %s
                      method: second
                """.formatted(
                agentId,
                agentType,
                firstToolName,
                TestTools.class.getName(),
                secondToolName,
                TestTools.class.getName()));

        return new TestYaml(yaml, agentId, firstToolName, secondToolName, tempDir.resolve("skills"));
    }

    private static Path testDirectory(String agentType) throws Exception {
        return Files.createDirectories(Path.of("target", "agent-factory-test", agentType,
                UUID.randomUUID().toString()));
    }

    private void assertRegisteredTools(TestYaml yaml) {
        Object first = Runner.resourceMgr().getTool(yaml.firstToolName());
        Object second = Runner.resourceMgr().getTool(yaml.secondToolName());
        assertThat(first).isInstanceOf(Tool.class);
        assertThat(second).isInstanceOf(Tool.class);
    }

    public static final class TestTools {
        private TestTools() {
        }

        public static Map<String, Object> first(Map<String, Object> inputs) {
            return Map.of("proof", "first", "inputs", inputs);
        }

        public static Map<String, Object> second(Map<String, Object> inputs) {
            return Map.of("proof", "second", "inputs", inputs);
        }
    }

    public static final class TestReactRail extends AgentRail {
        @Override
        public void beforeModelCall(AgentCallbackContext context) {
        }
    }

    public static final class TestDeepRail extends DeepAgentRail {
        @Override
        public void beforeModelCall(AgentCallbackContext context) {
        }
    }

    private record TestYaml(Path path, String agentId, String firstToolName, String secondToolName, Path skillsRoot) {
    }
}
