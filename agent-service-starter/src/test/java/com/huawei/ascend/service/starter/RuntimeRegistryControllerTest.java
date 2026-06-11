package com.huawei.ascend.service.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.service.core.InMemoryRuntimeRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the registration wire contract for the agent card: the card travels as
 * the A2A SDK serializer's JSON and is parsed back with the SDK's own
 * deserializer, so polymorphic spec members (securitySchemes) survive the
 * round trip and the wire format never silently tracks the SDK record layout.
 */
class RuntimeRegistryControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final InMemoryRuntimeRegistry registry = new InMemoryRuntimeRegistry();
    private final RuntimeRegistryController controller = new RuntimeRegistryController(registry, registry);

    @Test
    void securitySchemesBearingCardSurvivesTheRegistrationRoundTrip() throws Exception {
        AgentCard card = AgentCard.builder(weatherCard())
                .securitySchemes(Map.of("api-key", APIKeySecurityScheme.builder()
                        .location(APIKeySecurityScheme.Location.HEADER)
                        .name("X-Api-Key")
                        .description("tenant API key")
                        .build()))
                .build();
        JsonNode wireCard = json.readTree(JsonUtil.toJson(card));

        controller.register(registrationRequest(wireCard));

        AgentCard served = controller.getAgentCard("agent-weather", "tenant-a");
        assertThat(served).isEqualTo(card);
        assertThat(served.securitySchemes()).containsKey("api-key");
    }

    @Test
    void malformedAgentCardIsRejectedAsABadRequestNotAServerFault() {
        JsonNode malformed = json.readTree("{\"capabilities\":\"not-an-object\"}");

        assertThatThrownBy(() -> controller.register(registrationRequest(malformed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentCard");
    }

    private static RuntimeRegistryController.RuntimeRegistrationRequest registrationRequest(JsonNode wireCard) {
        return new RuntimeRegistryController.RuntimeRegistrationRequest(
                "runtime-1",
                "tenant-a",
                "agent-weather",
                wireCard,
                URI.create("http://runtime-1.internal:8443/a2a"),
                URI.create("http://runtime-1.internal:8443/health"),
                "1.0.0",
                60,
                Map.of());
    }

    private static AgentCard weatherCard() {
        return AgentCard.builder()
                .name("agent-weather")
                .description("agent-weather A2A runtime")
                .url("http://runtime-1.internal:8443/a2a")
                .version("1.0.0")
                .provider(new AgentProvider("spring-ai-ascend", "http://runtime-1.internal:8443"))
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface(
                        TransportProtocol.JSONRPC.asString(), "http://runtime-1.internal:8443/a2a")))
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .build();
    }
}
