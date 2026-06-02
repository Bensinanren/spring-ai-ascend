package com.huawei.ascend.service.access.core;

import com.huawei.ascend.service.access.egress.EgressBindingFactory;
import com.huawei.ascend.service.access.egress.EgressDispatcher;
import com.huawei.ascend.service.access.egress.EgressQueueRegistry;
import com.huawei.ascend.service.access.model.AccessAcceptedResponse;
import com.huawei.ascend.service.access.model.AccessCancelCommand;
import com.huawei.ascend.service.access.model.EgressBinding;
import com.huawei.ascend.service.access.model.ReplyContext;
import com.huawei.ascend.service.schema.AgentRequest;
import com.huawei.ascend.service.schema.Message;
import com.huawei.ascend.service.schema.Role;
import com.huawei.ascend.service.session.api.SessionManager;
import com.huawei.ascend.service.session.model.Session;
import com.huawei.ascend.service.taskcontrol.api.TaskControlClient;
import com.huawei.ascend.service.taskcontrol.api.TaskControlClient.CancelCommand;
import com.huawei.ascend.service.taskcontrol.api.TaskControlClient.ResumeCommand;
import com.huawei.ascend.service.taskcontrol.api.TaskControlClient.RunCommand;
import com.huawei.ascend.service.taskcontrol.api.TaskControlClient.TaskResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submits normalized access requests into task control and binds the session
 * reply channel before dispatch.
 */
public final class AccessSubmissionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccessSubmissionService.class);

    private final TaskControlClient taskControlClient;
    private final SessionManager sessionManager;
    private final EgressQueueRegistry egressQueueRegistry;
    private final EgressDispatcher egressDispatcher;

    public AccessSubmissionService(
            TaskControlClient taskControlClient,
            SessionManager sessionManager,
            EgressQueueRegistry egressQueueRegistry,
            EgressDispatcher egressDispatcher) {
        this.taskControlClient = Objects.requireNonNull(taskControlClient, "taskControlClient");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.egressQueueRegistry = Objects.requireNonNull(egressQueueRegistry, "egressQueueRegistry");
        this.egressDispatcher = Objects.requireNonNull(egressDispatcher, "egressDispatcher");
    }

    public CompletionStage<AccessAcceptedResponse> run(AgentRequest request, ReplyContext reply) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reply, "reply");
        AgentRequest resolved = resolveSession(request);
        LOGGER.info("access resolved session tenantId={} userId={} agentId={} requestedSessionId={} resolvedSessionId={}",
                request.tenantId(),
                request.userId(),
                request.agentId(),
                request.sessionId(),
                resolved.sessionId());
        bindEgress(resolved, reply);
        return taskControlClient.run(new RunCommand(resolved))
                .thenApply(result -> toAccepted(resolved, result));
    }

    public CompletionStage<AccessAcceptedResponse> resume(AgentRequest request, ReplyContext reply) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reply, "reply");
        AgentRequest resolved = resolveSession(request);
        bindEgress(resolved, reply);
        return taskControlClient.resume(new ResumeCommand(null, resolved))
                .thenApply(result -> toAccepted(resolved, result));
    }

    public CompletionStage<AccessAcceptedResponse> cancel(AccessCancelCommand command) {
        Objects.requireNonNull(command, "command");
        CancelCommand cancelCommand = new CancelCommand(
                command.tenantId(),
                command.userId(),
                command.agentId(),
                command.sessionId(),
                command.taskId(),
                command.reason(),
                command.metadata());
        return taskControlClient.cancel(cancelCommand).thenApply(result -> toAccepted(command, result));
    }

    private void bindEgress(AgentRequest request, ReplyContext reply) {
        EgressBinding binding = EgressBindingFactory.from(request, reply);
        egressQueueRegistry.getOrCreate(binding);
        egressDispatcher.start(binding);
        LOGGER.info("access egress bound tenantId={} sessionId={} agentId={} channel={} streaming={}",
                binding.tenantId(),
                binding.sessionId(),
                request.agentId(),
                binding.replyChannel(),
                reply.a2aStreaming());
    }

    private AgentRequest resolveSession(AgentRequest request) {
        Session session = sessionManager.loadOrCreate(
                request.tenantId(),
                request.userId(),
                request.agentId(),
                request.sessionId(),
                currentUserInput(request));
        return new AgentRequest(
                request.tenantId(),
                request.userId(),
                request.agentId(),
                session.sessionId(),
                request.input(),
                request.idempotencyKey(),
                request.metadata());
    }

    private List<Message> currentUserInput(AgentRequest request) {
        return request.input().stream()
                .filter(message -> message.role() == Role.USER)
                .toList();
    }

    private AccessAcceptedResponse toAccepted(AgentRequest request, TaskResult result) {
        return new AccessAcceptedResponse(
                result.tenantId(),
                request.userId(),
                request.agentId(),
                result.sessionId(),
                result.taskId(),
                result.accepted(),
                result.message());
    }

    private AccessAcceptedResponse toAccepted(AccessCancelCommand command, TaskResult result) {
        return new AccessAcceptedResponse(
                result.tenantId(),
                command.userId(),
                command.agentId(),
                result.sessionId(),
                result.taskId(),
                result.accepted(),
                result.message());
    }
}
