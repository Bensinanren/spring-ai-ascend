package com.huawei.ascend.agentsdk.spec.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.agentsdk.spec.AgentSpec;
import com.huawei.ascend.agentsdk.support.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentYamlLoaderTest {

    @Test
    void loadsYamlWithToolsAndSkillsResolvedRelativeToYamlFile() throws Exception {
        Path tempDir = testDirectory("loads");
        Path skill = Files.createDirectories(tempDir.resolve("skills").resolve("orders"));
        Files.writeString(skill.resolve("SKILL.md"), "# Order Skill\n");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: order-agent
                description: Order agent
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  provider: openai-compatible
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                prompt:
                  system: hello
                skills:
                  sources:
                    - ./skills/orders
                tools:
                  - name: queryOrder
                    description: Query an order
                    inputSchema:
                      type: object
                    ref:
                      type: file
                      class: example.OrderTools
                      method: query
                """);

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.name()).isEqualTo("order-agent");
        assertThat(spec.displayName()).isEqualTo("order-agent");
        assertThat(spec.skillSpecs()).hasSize(1);
        assertThat(spec.skillSpecs().get(0).path()).isEqualTo(skill.toAbsolutePath().normalize());
        assertThat(spec.toolSpecs()).hasSize(1);
        assertThat(spec.toolSpecs().get(0).ref().scheme()).isEqualTo("file");
        assertThat(spec.toolSpecs().get(0).ref().attributes()).doesNotContainKey("path");
        assertThat(spec.toolSpecs().get(0).name()).isEqualTo("queryOrder");
    }

    @Test
    void stringShorthandRefsEmitTheKeysTheBuiltInResolversRead() throws Exception {
        Path tempDir = testDirectory("shorthand");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: shorthand-agent
                description: Shorthand agent
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                tools:
                  - name: javaTool
                    description: java
                    ref: "file:example.OrderTools#query"
                  - name: httpTool
                    description: http
                    ref: "http:https://api.example.com/orders"
                """);

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.toolSpecs().get(0).ref().attributes())
                .containsEntry("class", "example.OrderTools")
                .containsEntry("method", "query");
        assertThat(spec.toolSpecs().get(1).ref().attributes())
                .containsEntry("url", "https://api.example.com/orders");
    }

    @Test
    void malformedStringShorthandFailsAtLoadWithSyntaxHint() throws Exception {
        Path tempDir = testDirectory("shorthand-bad");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: bad-agent
                description: Bad agent
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                tools:
                  - name: javaTool
                    description: java
                    ref: "file:./tools/order.java"
                """);

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("file:com.example.Class#method");
    }

    @Test
    void duplicateToolNamesAreRejected() throws Exception {
        Path tempDir = testDirectory("dup-tools");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: dup-agent
                description: Dup agent
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                tools:
                  - name: lookup
                    description: first
                    ref: "http:https://api.example.com/a"
                  - name: lookup
                    description: second
                    ref: "http:https://api.example.com/b"
                """);

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate tool name: lookup");
    }

    @Test
    void missingFrameworkSectionIsNamedInTheError() throws Exception {
        Path tempDir = testDirectory("no-framework");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: no-framework-agent
                description: No framework
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                """);

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("framework");
    }

    @Test
    void nonBooleanSslVerifyIsRejectedNotSilentlyFalse() throws Exception {
        Path tempDir = testDirectory("bad-bool");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: bad-bool-agent
                description: Bad bool
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                  sslVerify: enabled
                """);

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sslVerify");
    }

    @Test
    void rejectsMissingEnvironmentVariable() throws Exception {
        Path tempDir = testDirectory("rejects-missing-env");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: broken-agent
                description: Broken agent
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  provider: openai-compatible
                  name: deepseek-chat
                  baseUrl: ${ASCEND_AGENT_SDK_TEST_MISSING}
                  apiKey: secret
                """);

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ASCEND_AGENT_SDK_TEST_MISSING");
    }

    @Test
    void agentMdOnlyBecomesSystemPrompt() throws Exception {
        Path tempDir = testDirectory("agent-md-only");
        Files.writeString(tempDir.resolve("AGENT.md"), "You are an order assistant.");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, baseYaml("md-only-agent", "prompt:\n  agentMd: ./AGENT.md\n"));

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.promptSpec().system()).isEqualTo("You are an order assistant.");
    }

    @Test
    void systemOnlyBecomesSystemPrompt() throws Exception {
        Path tempDir = testDirectory("system-only");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, baseYaml("system-only-agent", "prompt:\n  system: hello\n"));

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.promptSpec().system()).isEqualTo("hello");
    }

    @Test
    void agentMdAndSystemAreJoinedWithAgentMdFirst() throws Exception {
        Path tempDir = testDirectory("md-and-system");
        Files.writeString(tempDir.resolve("AGENT.md"), "Identity block.");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, baseYaml("md-and-system-agent",
                "prompt:\n  agentMd: ./AGENT.md\n  system: inline-instruction\n"));

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.promptSpec().system()).isEqualTo("Identity block.\n\ninline-instruction");
    }

    @Test
    void emptyPromptYieldsEmptySystem() throws Exception {
        Path tempDir = testDirectory("empty-prompt");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, baseYaml("empty-prompt-agent", ""));

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.promptSpec().system()).isEqualTo("");
    }

    @Test
    void missingAgentMdFileIsRejected() throws Exception {
        Path tempDir = testDirectory("missing-agent-md");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, baseYaml("missing-md-agent", "prompt:\n  agentMd: ./MISSING.md\n"));

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("agentMd");
    }

    @Test
    void parsesModelRequestRailsAndMcps() throws Exception {
        Path tempDir = testDirectory("p1-fields");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, """
                schema: ascend-agent/v1
                name: p1-agent
                description: P1 agent
                framework:
                  type: openjiuwen
                  agent: deepagent
                  options:
                    skillMode: none
                    workspacePath: ./work
                    language: en
                    enableTaskLoop: true
                    enableTaskPlanning: true
                    completionTimeout: 120
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                  timeout: 45s
                  maxRetries: 2
                  request:
                    temperature: 0.2
                    topP: 0.8
                    maxTokens: 1024
                    stop: END
                    seed: 7
                rails:
                  - name: audit
                    type: class
                    class: example.AuditRail
                    priority: 10
                    options:
                      mode: strict
                  - name: hook
                    type: function
                    event: afterToolCall
                    class: example.RailHooks
                    method: afterToolCall
                mcps:
                  - serverId: orders
                    serverName: order-mcp
                    serverPath: http://localhost:9000/sse
                    clientType: sse
                    params:
                      tenant: test
                    authHeaders:
                      Authorization: Bearer token
                    authQueryParams:
                      token: query-token
                """);

        AgentSpec spec = new AgentYamlLoader().load(yaml);

        assertThat(spec.frameworkOptions())
                .containsEntry("skillMode", "none")
                .containsEntry("workspacePath", "./work")
                .containsEntry("language", "en")
                .containsEntry("enableTaskLoop", true)
                .containsEntry("enableTaskPlanning", true)
                .containsEntry("completionTimeout", 120);
        assertThat(spec.modelSpec().timeout()).isEqualTo(java.time.Duration.ofSeconds(45));
        assertThat(spec.modelSpec().maxRetries()).isEqualTo(2);
        assertThat(spec.modelSpec().requestSpec().temperature()).isEqualTo(0.2);
        assertThat(spec.modelSpec().requestSpec().topP()).isEqualTo(0.8);
        assertThat(spec.modelSpec().requestSpec().maxTokens()).isEqualTo(1024);
        assertThat(spec.modelSpec().requestSpec().stop()).isEqualTo("END");
        assertThat(spec.modelSpec().requestSpec().seed()).isEqualTo(7);
        assertThat(spec.railSpecs()).hasSize(2);
        assertThat(spec.railSpecs().get(0).name()).isEqualTo("audit");
        assertThat(spec.railSpecs().get(0).className()).isEqualTo("example.AuditRail");
        assertThat(spec.railSpecs().get(0).priority()).isEqualTo(10);
        assertThat(spec.railSpecs().get(0).options()).containsEntry("mode", "strict");
        assertThat(spec.railSpecs().get(1).type()).isEqualTo("function");
        assertThat(spec.railSpecs().get(1).event()).isEqualTo("afterToolCall");
        assertThat(spec.railSpecs().get(1).method()).isEqualTo("afterToolCall");
        assertThat(spec.mcpSpecs()).hasSize(1);
        assertThat(spec.mcpSpecs().get(0).serverId()).isEqualTo("orders");
        assertThat(spec.mcpSpecs().get(0).clientType()).isEqualTo("sse");
        assertThat(spec.mcpSpecs().get(0).authHeaders()).containsEntry("Authorization", "Bearer token");
    }

    @Test
    void rejectsFunctionRailEventsOutsideInitialWhitelist() throws Exception {
        Path tempDir = testDirectory("bad-rail-event");
        Path yaml = tempDir.resolve("agent.yaml");
        Files.writeString(yaml, baseYaml("bad-rail-event-agent", """
                rails:
                  - name: badHook
                    type: function
                    event: onToolException
                    class: example.RailHooks
                    method: onToolException
                """));

        assertThatThrownBy(() -> new AgentYamlLoader().load(yaml))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("onToolException");
    }

    private static String baseYaml(String name, String promptBlock) {
        return """
                schema: ascend-agent/v1
                name: %s
                description: test agent
                framework:
                  type: openjiuwen
                  agent: react
                model:
                  name: deepseek-chat
                  baseUrl: http://localhost
                  apiKey: secret
                %s
                """.formatted(name, promptBlock);
    }

    private static Path testDirectory(String name) throws Exception {
        return Files.createDirectories(Path.of("target", "agent-yaml-loader-test", name,
                UUID.randomUUID().toString()));
    }
}
