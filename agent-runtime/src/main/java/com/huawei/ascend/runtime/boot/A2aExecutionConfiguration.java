package com.huawei.ascend.runtime.boot;

import com.huawei.ascend.runtime.engine.a2a.A2aAgentCardMapper;
import com.huawei.ascend.runtime.engine.a2a.A2aAgentExecutor;
import com.huawei.ascend.runtime.engine.a2a.AgentCards;
import com.huawei.ascend.runtime.engine.a2a.BuildVersion;
import com.huawei.ascend.runtime.engine.a2a.RemoteAgentInvocationService;
import com.huawei.ascend.runtime.engine.spi.AgentCapabilitiesDescriptor;
import com.huawei.ascend.runtime.engine.spi.AgentCardDescriptor;
import com.huawei.ascend.runtime.engine.spi.AgentCardProvider;
import com.huawei.ascend.runtime.engine.spi.AgentInterfaceDescriptor;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.AgentSkillDescriptor;
import com.huawei.ascend.runtime.engine.spi.Redactor;
import com.huawei.ascend.runtime.engine.spi.TrajectorySinkFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the A2A execution surface: agent executor, request handler, agent card,
 * and the northbound HTTP controllers for servlet hosts.
 */
@Configuration(proxyBeanMethods = false)
class A2aExecutionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(A2aExecutionConfiguration.class);

    @Bean @ConditionalOnMissingBean
    public AgentExecutor a2aAgentExecutor(ObjectProvider<AgentRuntimeHandler> handlers,
            ObjectProvider<RemoteAgentInvocationService> remoteInvocationService,
            RuntimeReadiness readiness, TrajectoryProperties trajectoryProperties,
            ObjectProvider<TrajectorySinkFactory> sinkFactories,
            ObjectProvider<Redactor> redactorProvider) {
        var registered = handlers.orderedStream().toList();
        RemoteAgentInvocationService invocationService = remoteInvocationService.getIfAvailable();
        if (registered.isEmpty()) {
            // Tolerated so the A2A surface can boot for card discovery; every
            // execution will be rejected until a handler bean is registered.
            log.warn("No AgentRuntimeHandler registered - A2A executions will be rejected");
            return new A2aAgentExecutor(null, invocationService, readiness::isReady);
        }
        if (registered.size() > 1) {
            throw new IllegalStateException(
                    "Multiple AgentRuntimeHandler beans registered but the runtime hosts exactly one agent."
                    + " Found: " + registered.stream().map(AgentRuntimeHandler::agentId).toList()
                    + ". Register exactly one AgentRuntimeHandler bean, or split agents into separate"
                    + " runtime instances.");
        }
        return new A2aAgentExecutor(registered.get(0), invocationService, readiness::isReady,
                RuntimeAutoConfiguration.toTrajectorySettings(trajectoryProperties,
                        redactorProvider.getIfAvailable()),
                sinkFactories.orderedStream().toList());
    }

    @Bean @ConditionalOnMissingBean
    public RequestHandler a2aRequestHandler(AgentExecutor agentExecutor, TaskStore store,
            QueueManager queueManager, PushNotificationConfigStore pushStore, MainEventBusProcessor eventBus,
            RuntimeAutoConfiguration.A2aServerExecutor exec) {
        return DefaultRequestHandler.create(agentExecutor, store, queueManager, pushStore, eventBus,
                exec.executor(), exec.executor());
    }

    /**
     * Default agent card: an explicit {@code agent-card.name} wins, then the
     * configured {@code default-agent-id} selects among registered handlers (with
     * a WARN when it matches none), then the first registered handler. When an
     * {@link AgentCardProvider} bean is present, its descriptor is the base (YAML
     * overlay still applies on top). Otherwise the card is built from handler-declared
     * metadata ({@code supportsStreaming()}, {@code skills()}, {@code defaultOutputModes()})
     * combined with push-store durability detection and the build version.
     *
     * <p>YAML overlay: each explicitly set {@code AgentCardProperties} field wins over
     * the handler-derived or default value. Null/empty fields leave the derived value.
     *
     * <p>Security: no security scheme is emitted by default. Although the runtime identifies
     * tenants via the {@code X-Tenant-Id} header, advertising a security requirement on the
     * card makes it unparseable by the A2A SDK card resolver (which expects a protobuf-JSON
     * shape for security requirement scopes), so security is opt-in via explicit
     * handler/YAML configuration only.
     *
     * <p>Capability honesty rules:
     * <ul>
     *   <li>{@code capabilities.streaming}: derived from the registered handler's
     *       {@link AgentRuntimeHandler#supportsStreaming()} (default false); overridable
     *       via YAML {@code capabilities.streaming}.</li>
     *   <li>{@code capabilities.pushNotifications}: true only when the configured
     *       {@code PushNotificationConfigStore} is NOT the in-memory default
     *       (durable replacement signals cross-instance push support); overridable
     *       via YAML {@code capabilities.push-notifications}.</li>
     *   <li>{@code defaultOutputModes}: derived from the handler's
     *       {@link AgentRuntimeHandler#defaultOutputModes()} (default ["text"]); overridable
     *       via YAML {@code default-output-modes}.</li>
     * </ul>
     */
    @Bean @ConditionalOnMissingBean
    public AgentCard a2aAgentCard(ObjectProvider<AgentCardProvider> cardProviders,
                                   ObjectProvider<AgentRuntimeHandler> handlers,
                                   PushNotificationConfigStore pushStore,
                                   RuntimeAccessProperties access,
                                   AgentCardProperties cardProperties) {
        AgentCardDescriptor descriptor;

        var cp = cardProviders.getIfAvailable();
        if (cp != null) {
            // Provider bean supplies the base descriptor; YAML overlay is applied below.
            descriptor = cp.describe();
        } else {
            // Resolve the card name from YAML, configured default-agent-id, or first handler.
            String name;
            AgentRuntimeHandler handler = null;
            if (cardProperties.hasExplicitName()) {
                name = cardProperties.getName();
                List<AgentRuntimeHandler> registered = handlers.orderedStream().toList();
                List<String> agentIds = registered.stream().map(AgentRuntimeHandler::agentId).toList();
                if (!agentIds.isEmpty() && !agentIds.contains(name)) {
                    log.warn("agent-runtime.access.a2a.agent-card.name '{}' matches no registered handler;"
                            + " available agent ids: {}", name, agentIds);
                }
            } else {
                List<AgentRuntimeHandler> registered = handlers.orderedStream().toList();
                List<String> agentIds = registered.stream().map(AgentRuntimeHandler::agentId).toList();
                String configured = access.getDefaultAgentId();
                if (configured != null && !configured.isBlank() && agentIds.contains(configured.trim())) {
                    name = configured.trim();
                    handler = registered.stream()
                            .filter(h -> h.agentId().equals(name))
                            .findFirst().orElse(null);
                } else {
                    if (configured != null && !configured.isBlank()) {
                        log.warn("agent-runtime.access.a2a.default-agent-id '{}' matches no registered handler;"
                                + " available agent ids: {}", configured.trim(), agentIds);
                    }
                    name = agentIds.isEmpty() ? "agent" : agentIds.get(0);
                    handler = registered.isEmpty() ? null : registered.get(0);
                }
            }

            // Derive version: YAML override wins; fall back to build version.
            String version = (cardProperties.getVersion() != null && !cardProperties.getVersion().isBlank())
                    ? cardProperties.getVersion()
                    : BuildVersion.resolve();

            // Capability honesty: streaming from handler, push from store durability.
            boolean streaming = handler != null && handler.supportsStreaming();
            boolean pushNotifications = !(pushStore instanceof InMemoryPushNotificationConfigStore);
            AgentCapabilitiesDescriptor caps = new AgentCapabilitiesDescriptor(streaming, pushNotifications, false);

            // Skills and output modes from handler (empty / ["text"] when no handler).
            List<AgentSkillDescriptor> skills = handler != null ? handler.skills() : List.of();
            List<String> outputModes = handler != null ? handler.defaultOutputModes() : List.of("text");

            descriptor = AgentCards.defaultDescriptor(name,
                    cardProperties.getDescription(), version,
                    cardProperties.getEndpoint(), cardProperties.getOrganization(),
                    cardProperties.getOrganizationUrl())
                    .withCapabilities(caps)
                    .withSkills(skills)
                    .withDefaultOutputModes(outputModes);
        }

        // Apply YAML overlay: each non-null/non-empty YAML field wins.
        descriptor = applyYamlOverlay(descriptor, cardProperties, pushStore);

        return A2aAgentCardMapper.toAgentCard(descriptor);
    }

    /**
     * Applies the YAML-configured overlay onto the base descriptor.
     * Fields set explicitly in {@code props} replace the corresponding descriptor fields.
     * No default security scheme is injected; security is emitted only when explicitly
     * configured (see the bean javadoc for the A2A SDK card-resolver rationale).
     */
    private static AgentCardDescriptor applyYamlOverlay(AgentCardDescriptor base,
            AgentCardProperties props, PushNotificationConfigStore pushStore) {
        AgentCardDescriptor d = base;

        // Capabilities overlay: each non-null flag from YAML wins over the handler-derived value.
        if (props.hasExplicitCapabilities()) {
            AgentCapabilitiesDescriptor baseCaps = d.capabilities() != null
                    ? d.capabilities()
                    : AgentCapabilitiesDescriptor.defaults();
            AgentCardProperties.CapabilitiesConfig cc = props.getCapabilities();
            boolean streaming = cc.getStreaming() != null ? cc.getStreaming() : baseCaps.streaming();
            boolean push = cc.getPushNotifications() != null ? cc.getPushNotifications()
                    : baseCaps.pushNotifications();
            boolean extended = cc.getExtendedAgentCard() != null ? cc.getExtendedAgentCard()
                    : baseCaps.extendedAgentCard();
            d = d.withCapabilities(new AgentCapabilitiesDescriptor(streaming, push, extended));
        }

        // defaultInputModes overlay.
        if (props.hasExplicitDefaultInputModes()) {
            d = d.withDefaultInputModes(List.copyOf(props.getDefaultInputModes()));
        }

        // defaultOutputModes overlay.
        if (props.hasExplicitDefaultOutputModes()) {
            d = d.withDefaultOutputModes(List.copyOf(props.getDefaultOutputModes()));
        }

        // Skills overlay: YAML skills replace handler-declared skills entirely.
        if (props.hasExplicitSkills()) {
            List<AgentSkillDescriptor> yamlSkills = props.getSkills().stream()
                    .map(sc -> new AgentSkillDescriptor(
                            sc.getId(), sc.getName(), sc.getDescription(),
                            sc.getTags(), sc.getExamples(),
                            sc.getInputModes(), sc.getOutputModes()))
                    .toList();
            d = d.withSkills(yamlSkills);
        }

        // Additional interfaces from additionalEndpoints.
        if (props.hasAdditionalEndpoints()) {
            List<AgentInterfaceDescriptor> extraInterfaces = props.getAdditionalEndpoints().stream()
                    .filter(ep -> ep.getProtocol() != null && ep.getPath() != null)
                    .map(ep -> AgentInterfaceDescriptor.of(ep.getProtocol(), ep.getPath()))
                    .toList();
            if (!extraInterfaces.isEmpty()) {
                // Merge with any descriptor-declared additional interfaces.
                List<AgentInterfaceDescriptor> merged = new ArrayList<>(d.additionalInterfaces());
                merged.addAll(extraInterfaces);
                d = d.withAdditionalInterfaces(merged);
            }
        }

        // documentationUrl overlay.
        if (props.getDocumentationUrl() != null && !props.getDocumentationUrl().isBlank()) {
            d = d.withDocumentationUrl(props.getDocumentationUrl());
        }

        // iconUrl overlay.
        if (props.getIconUrl() != null && !props.getIconUrl().isBlank()) {
            d = d.withIconUrl(props.getIconUrl());
        }

        // No security scheme is emitted by default. The A2A SDK card resolver parses the
        // card via protobuf-JSON, where a security requirement's scopes must be a message
        // object; the spec-JSON empty-scope array a default scheme would emit ("scheme": [])
        // makes the resolver fail with "Expect message object but got: []", which renders the
        // card undiscoverable. Security stays opt-in via explicit handler/YAML configuration.

        return d;
    }

    /**
     * Registers the northbound HTTP surface for hosts that only depend on the jar:
     * without these bean methods a pure-dependency host boots with the full engine
     * wired but every northbound route silently 404s, because the controllers are
     * plain {@code @RestController} classes that only component scanning would find.
     * Hosts that do scan {@code runtime.boot} get the same beans by stereotype; the
     * {@code @ConditionalOnMissingBean} guards keep the two paths from colliding.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class NorthboundControllerConfiguration {

        @Bean @ConditionalOnMissingBean
        A2aJsonRpcController a2aJsonRpcController(RequestHandler handler, RuntimeAccessProperties access) {
            return new A2aJsonRpcController(handler, access);
        }

        @Bean @ConditionalOnMissingBean
        AgentCardController agentCardController(AgentCard agentCard, RuntimeAccessProperties access) {
            return new AgentCardController(agentCard, access);
        }
    }
}
