package com.huawei.ascend.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.engine.spi.AgentExecutionResult;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.AgentSkillDescriptor;
import com.huawei.ascend.runtime.engine.spi.LocalFsPayloadRefStore;
import com.huawei.ascend.runtime.engine.spi.Redactor;
import com.huawei.ascend.runtime.engine.spi.StreamAdapter;
import com.huawei.ascend.runtime.engine.spi.TrajectoryMasking;
import com.huawei.ascend.runtime.engine.spi.TrajectorySettings;
import com.huawei.ascend.runtime.engine.spi.ValueRecognizingRedactor;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Covers the auto-configuration's bean-backoff contracts (durable TaskStore replacement, daemon
 * event-bus thread, no broad Executor bean) and the config→settings mapping that decides whether
 * (and how) trajectory is enabled in prod.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuntimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    /** A consumer-supplied durable TaskStore must replace the in-memory default, not coexist with it. */
    @Test
    void customTaskStoreSuppressesInMemoryDefault() {
        runner.withUserConfiguration(CustomStoreConfiguration.class, RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).getBeans(TaskStore.class).hasSize(1);
                    assertThat(ctx.getBean(TaskStore.class)).isInstanceOf(RecordingTaskStore.class);
                });
    }

    /** The event-bus loop must run on the SDK's own daemon thread so a hosting JVM can exit. */
    @Test
    void eventBusProcessorRunsOnDaemonThread() {
        runner.withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MainEventBusProcessor.class);
                    Thread processorThread = Thread.getAllStackTraces().keySet().stream()
                            .filter(t -> t.getName().contains("MainEventBusProcessor"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("processor thread not started"));
                    assertThat(processorThread.isDaemon())
                            .as("processor thread must be daemon or it blocks JVM exit")
                            .isTrue();
                });
    }

    /**
     * Actuator is an optional dependency: the auto-configuration must stay loadable
     * (skipping the health contribution) in hosts without HealthIndicator on the
     * classpath — a bean-method signature mentioning the indicator on the outer
     * configuration class makes context startup throw NoClassDefFoundError there.
     */
    @Test
    void autoConfigurationLoadsWithoutActuatorOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("agentRuntimeHealthIndicator");
                });
    }

    /**
     * No bean assignable to java.util.concurrent.Executor may be exposed: Spring Boot's
     * applicationTaskExecutor backs off when one exists, silently disabling the
     * application's default (virtual-thread) task executor.
     */
    @Test
    void noBroadExecutorBeanExposed() {
        runner.withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBeanNamesForType(Executor.class)).isEmpty());
    }

    @Test
    void disabledYieldsOff() {
        TrajectoryProperties properties = new TrajectoryProperties();
        properties.setEnabled(false);
        assertThat(RuntimeAutoConfiguration.toTrajectorySettings(properties).enabled()).isFalse();
    }

    @Test
    void enabledCarriesMaskAndTruncate() {
        TrajectoryProperties properties = new TrajectoryProperties();
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.truncateChars()).isEqualTo(256);
        assertThat(settings.maskKeyPattern()).isNotNull();
    }

    @Test
    void enabledCarriesSampleRateDefaultOne() {
        TrajectoryProperties properties = new TrajectoryProperties();
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        assertThat(settings.sampleRate()).isEqualTo(1.0);
    }

    @Test
    void configuredSampleRateFlowsIntoSettings() {
        TrajectoryProperties properties = new TrajectoryProperties();
        properties.setSampleRate(0.25);
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        assertThat(settings.sampleRate()).isEqualTo(0.25);
    }

    @Test
    void invalidMaskPatternFailsSafeToTheDefaultNotABootCrash() {
        TrajectoryProperties properties = new TrajectoryProperties();
        properties.getMask().setKeyPattern("(unbalanced");
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        // Never crashes, never degrades to a null pattern (which would silently disable redaction).
        assertThat(settings.maskKeyPattern().pattern()).isEqualTo(TrajectoryMasking.DEFAULT_KEY_PATTERN);
    }

    /** Default settings (no Redactor bean) must carry a null redactor — the fallback key-name path. */
    @Test
    void defaultSettingsHasNullRedactor() {
        TrajectoryProperties properties = new TrajectoryProperties();
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        assertThat(settings.redactor()).isNull();
    }

    /** A provided Redactor bean is wired into the settings returned for an enabled trajectory. */
    @Test
    void providedRedactorBeanIsWiredIntoSettings() {
        TrajectoryProperties properties = new TrajectoryProperties();
        Redactor supplied = new ValueRecognizingRedactor(
                java.util.regex.Pattern.compile(TrajectoryMasking.DEFAULT_KEY_PATTERN), 256);
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties, supplied);
        assertThat(settings.redactor()).isSameAs(supplied);
    }

    /** Even when trajectory is disabled the redactor field stays null (off() factory). */
    @Test
    void disabledSettingsAlwaysHasNullRedactor() {
        TrajectoryProperties properties = new TrajectoryProperties();
        properties.setEnabled(false);
        Redactor supplied = new ValueRecognizingRedactor(
                java.util.regex.Pattern.compile(TrajectoryMasking.DEFAULT_KEY_PATTERN), 256);
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties, supplied);
        // off() is returned when disabled; its redactor is null regardless of argument.
        assertThat(settings.redactor()).isNull();
    }

    /** Default config (payloadRef disabled) yields a null store — unchanged truncation behaviour. */
    @Test
    void payloadRefDisabledByDefaultYieldsNullStore() {
        TrajectoryProperties properties = new TrajectoryProperties();
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        assertThat(settings.payloadRefStore()).isNull();
        assertThat(settings.payloadRefThreshold()).isEqualTo(0);
        assertThat(settings.payloadRefFields()).isEmpty();
    }

    /** Enabling payloadRef with a baseDir and fields wires a LocalFsPayloadRefStore. */
    @Test
    void payloadRefEnabledWiresLocalFsStore(@TempDir java.nio.file.Path tempDir) {
        TrajectoryProperties properties = new TrajectoryProperties();
        properties.getPayloadRef().setEnabled(true);
        properties.getPayloadRef().setBaseDir(tempDir.toString());
        properties.getPayloadRef().setThreshold(1024);
        properties.getPayloadRef().setFields(List.of("result", "args"));
        TrajectorySettings settings = RuntimeAutoConfiguration.toTrajectorySettings(properties);
        assertThat(settings.payloadRefStore()).isInstanceOf(LocalFsPayloadRefStore.class);
        assertThat(settings.payloadRefThreshold()).isEqualTo(1024);
        assertThat(settings.payloadRefFields()).containsExactlyInAnyOrder("result", "args");
    }

    /**
     * The configured default-agent-id pins the served card to the hosted handler.
     * The runtime hosts exactly one agent (the executor rejects multiple handlers
     * at boot), so the key's job is validate-and-name, not multi-handler routing.
     */
    @Test
    void defaultAgentIdMatchingTheHostedHandlerNamesTheCard() {
        runner.withBean("handlerA", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues("agent-runtime.access.a2a.default-agent-id=agent-a")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class).name()).isEqualTo("agent-a"));
    }

    /** Unset default-agent-id keeps the existing behavior: the hosted handler names the card. */
    @Test
    void unsetDefaultAgentIdFallsBackToTheHostedHandler() {
        runner.withBean("handlerA", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class).name()).isEqualTo("agent-a"));
    }

    /** A typo'd id must not silently serve an arbitrary card: WARN names the configured value and the candidates. */
    @Test
    void mismatchedDefaultAgentIdWarnsWithConfiguredValueAndAvailableIds(CapturedOutput output) {
        runner.withBean("handlerA", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues("agent-runtime.access.a2a.default-agent-id=agent-typo")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx.getBean(AgentCard.class).name()).isEqualTo("agent-a");
                    assertThat(output).contains("default-agent-id 'agent-typo' matches no registered handler");
                    assertThat(output).contains("agent-a");
                });
    }

    /**
     * An explicit agent-card.name that matches no registered handler must WARN
     * (the card would otherwise advertise a name no execution accepts) but MUST NOT
     * throw — the bean is still produced and discovery keeps serving.
     */
    @Test
    void explicitCardNameMatchingNoHandlerWarnsButStillProducesCard(CapturedOutput output) {
        runner.withBean("handlerA", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues("agent-runtime.access.a2a.agent-card.name=wrong-name")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AgentCard.class).name()).isEqualTo("wrong-name");
                    assertThat(output).contains("wrong-name");
                    assertThat(output).contains("agent-a");
                });
    }

    /**
     * An explicit agent-card.name that DOES match a registered handler must NOT log
     * a warning — the happy-path must stay silent.
     */
    @Test
    void explicitCardNameMatchingAHandlerProducesCardWithNoWarn(CapturedOutput output) {
        runner.withBean("handlerA", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues("agent-runtime.access.a2a.agent-card.name=agent-a")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AgentCard.class).name()).isEqualTo("agent-a");
                    assertThat(output.toString()).doesNotContain("matches no registered handler");
                });
    }

    /**
     * A host that only depends on the jar (no component scan of runtime.boot) must
     * still get the northbound controllers from the auto-configuration; otherwise
     * the engine boots healthy while every northbound route 404s.
     */
    @Test
    void servletHostsGetNorthboundControllersWithoutComponentScanning() {
        new WebApplicationContextRunner().withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(A2aJsonRpcController.class);
                    assertThat(ctx).hasSingleBean(AgentCardController.class);
                });
    }

    /** Hosts that DO component-scan runtime.boot keep exactly one controller of each type. */
    @Test
    void scannedControllerBeansSuppressTheAutoConfiguredOnes() {
        new WebApplicationContextRunner()
                .withUserConfiguration(ScannedControllersConfiguration.class, RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).getBeans(A2aJsonRpcController.class).hasSize(1);
                    assertThat(ctx).getBeans(AgentCardController.class).hasSize(1);
                });
    }

    /** Non-web hosts (pure engine embedding) must not fail on servlet-only controller beans. */
    @Test
    void nonWebHostsSkipTheControllerRegistration() {
        runner.withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(A2aJsonRpcController.class);
                    assertThat(ctx).doesNotHaveBean(AgentCardController.class);
                });
    }

    // --- Capability-honesty tests ---

    /** Default handler (no override) must produce streaming=false on the card. */
    @Test
    void defaultHandlerProducesStreamingFalseOnCard() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .capabilities().streaming()).isFalse());
    }

    /** A handler that overrides supportsStreaming()=true must produce streaming=true on the card. */
    @Test
    void streamingHandlerProducesStreamingTrueOnCard() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new StreamingHandler("agent-s"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .capabilities().streaming()).isTrue());
    }

    /** In-memory push store (the default) must produce pushNotifications=false on the card. */
    @Test
    void inMemoryPushStoreProducesPushFalseOnCard() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .capabilities().pushNotifications()).isFalse());
    }

    /** A durable push store (not InMemoryPushNotificationConfigStore) must produce pushNotifications=true. */
    @Test
    void durablePushStoreProducesPushTrueOnCard() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withBean("durablePushStore", PushNotificationConfigStore.class,
                        () -> new DurablePushNotificationConfigStore())
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .capabilities().pushNotifications()).isTrue());
    }

    /** A handler that declares skills must have them surfaced on the card. */
    @Test
    void handlerWithSkillsPopulatesCardSkills() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new HandlerWithSkills("agent-b"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    AgentCard card = ctx.getBean(AgentCard.class);
                    assertThat(card.skills()).hasSize(1);
                    assertThat(card.skills().get(0).id()).isEqualTo("skill-1");
                });
    }

    /** Default handler (no override) must produce outputModes=["text"] on the card. */
    @Test
    void defaultHandlerProducesTextOnlyOutputModes() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .defaultOutputModes()).containsExactly("text"));
    }

    /** A handler overriding defaultOutputModes to include "artifact" must surface on the card. */
    @Test
    void handlerWithArtifactOutputModePopulatesCardOutputModes() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new ArtifactOutputHandler("agent-c"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .defaultOutputModes()).containsExactlyInAnyOrder("text", "artifact"));
    }

    /** Stand-in for the component-scan path: user-declared controller beans of the same types. */
    @Configuration(proxyBeanMethods = false)
    static class ScannedControllersConfiguration {
        @Bean
        A2aJsonRpcController scannedJsonRpcController() {
            return new A2aJsonRpcController(null, new RuntimeAccessProperties());
        }

        @Bean
        AgentCardController scannedCardController() {
            return new AgentCardController(null, new RuntimeAccessProperties());
        }
    }

    static class NamedHandler implements AgentRuntimeHandler {
        private final String agentId;

        NamedHandler(String agentId) {
            this.agentId = agentId;
        }

        @Override
        public String agentId() { return agentId; }

        @Override
        public boolean isHealthy() { return true; }

        @Override
        public Stream<?> execute(AgentExecutionContext context) { return Stream.empty(); }

        @Override
        public StreamAdapter resultAdapter() {
            return rawResults -> rawResults.map(raw -> AgentExecutionResult.completed("ok"));
        }
    }

    /** Handler that declares supportsStreaming()=true for tests. */
    static final class StreamingHandler extends NamedHandler {
        StreamingHandler(String agentId) { super(agentId); }

        @Override
        public boolean supportsStreaming() { return true; }
    }

    /** Handler that declares skills for tests. */
    static final class HandlerWithSkills extends NamedHandler {
        HandlerWithSkills(String agentId) { super(agentId); }

        @Override
        public List<AgentSkillDescriptor> skills() {
            return List.of(AgentSkillDescriptor.of("skill-1", "Skill One", "Does stuff"));
        }
    }

    /** Handler that declares artifact output mode for tests. */
    static final class ArtifactOutputHandler extends NamedHandler {
        ArtifactOutputHandler(String agentId) { super(agentId); }

        @Override
        public List<String> defaultOutputModes() { return List.of("text", "artifact"); }
    }

    // --- E3 tests ---

    /**
     * The default card must emit NO security — advertising one makes the card unparseable
     * by the A2A SDK card resolver, which would render it undiscoverable. Security is opt-in.
     */
    @Test
    void defaultCardEmitsNoSecurity() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    AgentCard card = ctx.getBean(AgentCard.class);
                    assertThat(card.securitySchemes()).isNullOrEmpty();
                    assertThat(card.securityRequirements()).isNullOrEmpty();
                });
    }

    /** YAML-configured capabilities.streaming=true overrides the handler-derived false. */
    @Test
    void yamlCapabilitiesStreamingOverridesHandlerDerived() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues(
                        "agent-runtime.access.a2a.agent-card.capabilities.streaming=true")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .capabilities().streaming()).isTrue());
    }

    /** YAML-configured defaultOutputModes overrides the handler-declared value. */
    @Test
    void yamlDefaultOutputModesOverrideHandlerDeclared() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues(
                        "agent-runtime.access.a2a.agent-card.default-output-modes[0]=text",
                        "agent-runtime.access.a2a.agent-card.default-output-modes[1]=artifact")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean(AgentCard.class)
                        .defaultOutputModes()).containsExactly("text", "artifact"));
    }

    /** YAML-configured additional-endpoints appear as extra AgentInterfaces on the card. */
    @Test
    void yamlAdditionalEndpointsAppearAsExtraInterfaces() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues(
                        "agent-runtime.access.a2a.agent-card.additional-endpoints[0].protocol=GRPC",
                        "agent-runtime.access.a2a.agent-card.additional-endpoints[0].path=/a2a/grpc")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    AgentCard card = ctx.getBean(AgentCard.class);
                    assertThat(card.supportedInterfaces()).hasSizeGreaterThanOrEqualTo(2);
                    boolean hasGrpc = card.supportedInterfaces().stream()
                            .anyMatch(i -> "GRPC".equals(i.protocolBinding()));
                    assertThat(hasGrpc).as("GRPC interface must be present on the card").isTrue();
                });
    }

    /** YAML documentationUrl and iconUrl appear on the card. */
    @Test
    void yamlDocumentationUrlAndIconUrlAppearOnCard() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new NamedHandler("agent-a"))
                .withPropertyValues(
                        "agent-runtime.access.a2a.agent-card.documentation-url=https://docs.example.com",
                        "agent-runtime.access.a2a.agent-card.icon-url=https://example.com/icon.png")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    AgentCard card = ctx.getBean(AgentCard.class);
                    assertThat(card.documentationUrl()).isEqualTo("https://docs.example.com");
                    assertThat(card.iconUrl()).isEqualTo("https://example.com/icon.png");
                });
    }

    /** YAML skills override replaces handler-declared skills. */
    @Test
    void yamlSkillsOverrideReplacesHandlerDeclaredSkills() {
        runner.withBean("h", AgentRuntimeHandler.class, () -> new HandlerWithSkills("agent-b"))
                .withPropertyValues(
                        "agent-runtime.access.a2a.agent-card.skills[0].id=yaml-skill",
                        "agent-runtime.access.a2a.agent-card.skills[0].name=YAML Skill",
                        "agent-runtime.access.a2a.agent-card.skills[0].description=From YAML")
                .withUserConfiguration(RuntimeAutoConfiguration.class)
                .run(ctx -> {
                    AgentCard card = ctx.getBean(AgentCard.class);
                    assertThat(card.skills()).hasSize(1);
                    assertThat(card.skills().get(0).id()).isEqualTo("yaml-skill");
                });
    }

    /** Stub durable push store (not InMemoryPushNotificationConfigStore) for tests. */
    static final class DurablePushNotificationConfigStore implements PushNotificationConfigStore {
        @Override
        public org.a2aproject.sdk.spec.TaskPushNotificationConfig setInfo(
                org.a2aproject.sdk.spec.TaskPushNotificationConfig config) {
            return config;
        }

        @Override
        public org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult getInfo(
                org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams params) {
            return new org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult(java.util.List.of());
        }

        @Override
        public void deleteInfo(String taskId, String pushNotificationConfigId) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStoreConfiguration {
        @Bean
        TaskStore durableTaskStore() { return new RecordingTaskStore(); }

        // InMemoryQueueManager needs a TaskStateProvider; a durable store would implement both.
        @Bean
        TaskStateProvider durableTaskStateProvider() {
            return new TaskStateProvider() {
                @Override
                public boolean isTaskActive(String taskId) { return false; }

                @Override
                public boolean isTaskFinalized(String taskId) { return true; }
            };
        }
    }

    static final class RecordingTaskStore implements TaskStore {
        @Override
        public void save(Task task, boolean overwrite) { }

        @Override
        public Task get(String taskId) { return null; }

        @Override
        public void delete(String taskId) { }

        @Override
        public ListTasksResult list(ListTasksParams params) { return new ListTasksResult(java.util.List.of()); }
    }
}
