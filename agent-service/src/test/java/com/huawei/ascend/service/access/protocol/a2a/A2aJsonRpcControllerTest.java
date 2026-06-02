package com.huawei.ascend.service.access.protocol.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huawei.ascend.service.access.core.AccessGateway;
import com.huawei.ascend.service.access.core.AccessSubmissionService;
import com.huawei.ascend.service.access.model.AccessAcceptedResponse;
import com.huawei.ascend.service.access.model.ReplyContext;
import com.huawei.ascend.service.schema.AgentRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.lang.reflect.Method;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class A2aJsonRpcControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final CapturingAccessService accessService = new CapturingAccessService();
    private final A2aAccessProperties properties = new A2aAccessProperties();
    private final A2aJsonRpcController controller =
            new A2aJsonRpcController(accessService, new A2aOutputRegistry(), objectMapper, properties);

    @BeforeEach
    void setUp() {
        properties.setDefaultTenantId("tenant-default");
        properties.setDefaultAgentId("agent-default");
    }

    @Test
    void messageSendAcceptsAgentScopeMethodNameAndDefaultTenantAndAgent() throws Exception {
        Object response = controller.handle(objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "request-1",
                "method", "message/send",
                "params", Map.of(
                        "message", Map.of(
                                "role", "user",
                                "kind", "message",
                                "contextId", "session-1",
                                "messageId", UUID.randomUUID().toString(),
                                "metadata", Map.of(
                                        "userId", "user-1",
                                        "sessionId", "session-1"),
                                "parts", List.of(Map.of(
                                        "kind", "text",
                                        "text", "ping")))))));

        JsonNode json = responseJson(response);

        assertThat(json.at("/result/metadata/accepted").asBoolean()).isTrue();
        assertThat(accessService.sent).hasSize(1);
        A2aEnvelope envelope = accessService.sent.get(0);
        assertThat(envelope.context().tenantId()).isEqualTo("tenant-default");
        assertThat(envelope.context().agentId()).isEqualTo("agent-default");
        assertThat(envelope.context().userId()).isEqualTo("user-1");
        assertThat(envelope.message().text()).isEqualTo("ping");
    }

    @Test
    void messageSendDoesNotRequireCorrelationId() throws Exception {
        AccessSubmissionService submissionService = mock(AccessSubmissionService.class);
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        when(submissionService.run(requestCaptor.capture(), any())).thenAnswer(invocation -> {
            AgentRequest request = invocation.getArgument(0);
            return CompletableFuture.completedStage(new AccessAcceptedResponse(
                    request.tenantId(),
                    request.userId(),
                    request.agentId(),
                    request.sessionId(),
                    "task-1",
                    true,
                    "accepted"));
        });
        A2aJsonRpcController realController = new A2aJsonRpcController(
                new A2aIngressAdapter(new AccessGateway(submissionService)),
                new A2aOutputRegistry(),
                objectMapper,
                properties);

        Object response = realController.handle(objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "request-optional-context",
                "method", "message/send",
                "params", Map.of(
                        "message", Map.of(
                                "role", "user",
                                "kind", "message",
                                "messageId", UUID.randomUUID().toString(),
                                "metadata", Map.of(
                                        "userId", "user-1",
                                        "sessionId", "session-1"),
                                "parts", List.of(Map.of(
                                        "kind", "text",
                                        "text", "ping")))))));

        JsonNode json = responseJson(response);

        assertThat(json.at("/result/metadata/accepted").asBoolean()).isTrue();
        assertThat(requestCaptor.getValue().metadata()).doesNotContainKey("correlationId");
        assertThat(requestCaptor.getValue().metadata()).doesNotContainKey("contextId");
    }

    @Test
    void tasksGetAcceptsAgentScopeMethodName() throws Exception {
        Object response = controller.handle(objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "request-2",
                "method", "tasks/get",
                "params", Map.of(
                        "id", "task-1",
                        "metadata", Map.of(
                                "tenantId", "tenant-1",
                                "sessionId", "session-1")))));

        JsonNode json = responseJson(response);

        assertThat(json.at("/result/id").asText()).isEqualTo("task-1");
    }

    @Test
    void tasksGetUsesA2aWireValuesForTaskStatusAndMessageParts() throws Exception {
        A2aOutputRegistry outputRegistry = new A2aOutputRegistry();
        A2aJsonRpcController realController =
                new A2aJsonRpcController(accessService, outputRegistry, objectMapper, properties);
        Message message = A2aTaskMapper.agentMessage("session-1", "task-1", "pong", Map.of("type", "final_response"));
        outputRegistry.append(
                new A2aOutputHandle("tenant-1", "session-1", "task-1"),
                new A2aOutput(
                        "TaskStatus",
                        "task-1",
                        new TaskStatusUpdateEvent(
                                "task-1",
                                new TaskStatus(TaskState.TASK_STATE_COMPLETED, message, null),
                                "session-1",
                                Map.of()),
                        null,
                        true,
                        Map.of()));

        Object response = realController.handle(objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "request-2",
                "method", "tasks/get",
                "params", Map.of(
                        "id", "task-1",
                        "metadata", Map.of(
                                "tenantId", "tenant-1",
                                "sessionId", "session-1")))));

        JsonNode json = responseJson(response);

        assertThat(json.at("/result/status/state").asText()).isEqualTo("completed");
        assertThat(json.at("/result/status/message/role").asText()).isEqualTo("agent");
        assertThat(json.at("/result/status/message/parts/0/kind").asText()).isEqualTo("text");
        assertThat(json.at("/result/status/message/parts/0/text").asText()).isEqualTo("pong");
    }

    @Test
    void messageStreamAcceptsSdkEndpointWithoutTrailingSlash() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", "request-stream",
                                "method", "message/stream",
                                "params", Map.of(
                                        "message", Map.of(
                                                "role", "user",
                                                "kind", "message",
                                                "contextId", "session-1",
                                                "messageId", UUID.randomUUID().toString(),
                                                "metadata", Map.of(
                                                        "userId", "user-1",
                                                        "sessionId", "session-1"),
                                                "parts", List.of(Map.of(
                                                        "kind", "text",
                                                        "text", "ping"))))))))
                .andExpect(request().asyncStarted());

        assertThat(accessService.sent).hasSize(1);
        assertThat(accessService.sent.get(0).message().text()).isEqualTo("ping");
    }

    @Test
    void messageStreamSuccessResponseOmitsNullJsonRpcErrorField() throws Exception {
        A2aAcceptedResponse accepted = new A2aAcceptedResponse(
                "tenant-1",
                "user-1",
                "agent-1",
                "session-1",
                "task-1",
                true,
                "accepted");
        Method toAcceptedMessage = A2aJsonRpcController.class
                .getDeclaredMethod("toAcceptedMessage", A2aAcceptedResponse.class);
        toAcceptedMessage.setAccessible(true);
        Message acceptedMessage = (Message) toAcceptedMessage.invoke(controller, accepted);
        SendStreamingMessageResponse response = new SendStreamingMessageResponse("request-stream", acceptedMessage);

        String json = toJson(response);

        assertThat(json).contains("\"result\"");
        assertThat(json).contains("\"message\"");
        assertThat(json).doesNotContain("\"error\"");
        assertThat(objectMapper.readTree(json).at("/result/message/role").asText()).isEqualTo("ROLE_AGENT");
        assertThat(JSONRPCUtils.parseResponseEvent(json).hasMessage()).isTrue();
    }

    private JsonNode responseJson(Object response) throws Exception {
        Object body = responseBody(response);
        if (body instanceof String json) {
            return objectMapper.readTree(json);
        }
        JsonNode wrapped = objectMapper.valueToTree(response);
        return wrapped.has("body") ? wrapped.path("body") : wrapped;
    }

    private String responseJsonString(Object response) {
        Object body = responseBody(response);
        if (body instanceof String json) {
            return json;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize response", ex);
        }
    }

    private Object responseBody(Object response) {
        return response instanceof ResponseEntity<?> entity ? entity.getBody() : response;
    }

    private String toJson(Object response) throws Exception {
        Method toJson = A2aJsonRpcController.class.getDeclaredMethod("toJson", Object.class);
        toJson.setAccessible(true);
        return (String) toJson.invoke(controller, response);
    }

    private static final class CapturingAccessService implements A2aAccessService {
        private final List<A2aEnvelope> sent = new ArrayList<>();

        @Override
        public A2aAcceptedResponse send(A2aEnvelope envelope) {
            sent.add(envelope);
            return accepted(envelope);
        }

        @Override
        public A2aAcceptedResponse stream(A2aEnvelope envelope) {
            sent.add(envelope);
            return accepted(envelope);
        }

        @Override
        public A2aAcceptedResponse cancel(A2aEnvelope envelope) {
            return accepted(envelope);
        }

        private static A2aAcceptedResponse accepted(A2aEnvelope envelope) {
            return new A2aAcceptedResponse(
                    envelope.context().tenantId(),
                    envelope.context().userId(),
                    envelope.context().agentId(),
                    envelope.context().sessionId(),
                    "task-1",
                    true,
                    "accepted");
        }
    }

}
