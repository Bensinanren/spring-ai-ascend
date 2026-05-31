package com.huawei.ascend.service.access.gateway;

import com.huawei.ascend.service.access.a2a.A2aEnvelope;
import com.huawei.ascend.service.access.a2a.A2aReplyMode;
import com.huawei.ascend.service.access.egress.EgressDispatcher;
import com.huawei.ascend.service.access.egress.EgressQueueRegistry;
import com.huawei.ascend.service.access.model.AccessAcceptedResponse;
import com.huawei.ascend.service.access.model.AccessIntent;
import com.huawei.ascend.service.access.model.AccessOperation;
import com.huawei.ascend.service.access.model.EgressBinding;
import com.huawei.ascend.service.access.model.ReplyChannel;
import com.huawei.ascend.service.access.mq.MqEnvelope;
import com.huawei.ascend.service.access.port.TaskHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class AccessGateway {

    private final TaskHandler taskHandler;
    private final EgressQueueRegistry egressQueueRegistry;
    private final EgressDispatcher egressDispatcher;

    public AccessGateway(
            TaskHandler taskHandler,
            EgressQueueRegistry egressQueueRegistry,
            EgressDispatcher egressDispatcher) {
        this.taskHandler = Objects.requireNonNull(taskHandler, "taskHandler");
        this.egressQueueRegistry = Objects.requireNonNull(egressQueueRegistry, "egressQueueRegistry");
        this.egressDispatcher = Objects.requireNonNull(egressDispatcher, "egressDispatcher");
    }

    public AccessIntent acceptA2a(A2aEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        A2aEnvelope.A2aContext context = envelope.context();
        A2aEnvelope.A2aMessage message = envelope.message();
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("parts", message == null ? java.util.List.of() : message.parts());
        payload.put("metadata", message == null ? Map.of() : message.metadata());
        payload.put("reply", envelope.reply());
        payload.put("contextId", context.contextId());
        payload.put("correlationId", context.correlationId());
        return new AccessIntent(
                AccessOperation.SUBMIT,
                context.tenantId(),
                context.userId(),
                context.agentId(),
                context.sessionId(),
                message == null ? null : message.text(),
                context.idempotencyKey(),
                Collections.unmodifiableMap(payload));
    }

    public AccessIntent acceptMq(MqEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("payload", envelope.body().payload());
        payload.put("replyTopic", envelope.headers().replyTopic());
        payload.put("correlationId", envelope.headers().correlationId());
        return new AccessIntent(
                envelope.headers().operation(),
                envelope.headers().tenantId(),
                envelope.headers().userId(),
                envelope.headers().agentId(),
                envelope.headers().sessionId(),
                envelope.body().query(),
                envelope.headers().idempotencyKey(),
                Collections.unmodifiableMap(payload));
    }

    public CompletionStage<AccessAcceptedResponse> dispatch(AccessIntent intent) {
        return taskHandler.runTask(Objects.requireNonNull(intent, "intent"));
    }

    public EgressBinding bindEgress(AccessIntent intent, AccessAcceptedResponse accepted) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(accepted, "accepted");
        ReplyChannel replyChannel = resolveReplyChannel(intent);
        String deliveryMode = resolveDeliveryMode(intent, replyChannel);
        String targetRef = resolveTargetRef(intent, replyChannel);
        String correlationId = resolveCorrelationId(intent);
        EgressBinding binding = new EgressBinding(
                accepted.tenantId(),
                accepted.sessionId(),
                accepted.taskId(),
                replyChannel,
                deliveryMode,
                targetRef,
                correlationId);
        egressQueueRegistry.getOrCreate(binding);
        egressDispatcher.start(binding);
        return binding;
    }

    private static ReplyChannel resolveReplyChannel(AccessIntent intent) {
        if (intent.payload() instanceof Map<?, ?> payload && payload.containsKey("replyTopic")) {
            return ReplyChannel.MQ;
        }
        return ReplyChannel.A2A;
    }

    private static String resolveTargetRef(AccessIntent intent, ReplyChannel replyChannel) {
        if (replyChannel == ReplyChannel.MQ && intent.payload() instanceof Map<?, ?> payload) {
            Object replyTopic = payload.get("replyTopic");
            return replyTopic == null ? null : replyTopic.toString();
        }
        if (intent.payload() instanceof Map<?, ?> payload) {
            Object reply = payload.get("reply");
            if (reply instanceof A2aEnvelope.A2aReplySpec spec && spec.target() != null) {
                return spec.target();
            }
            return reply == null ? null : reply.toString();
        }
        return null;
    }

    private static String resolveDeliveryMode(AccessIntent intent, ReplyChannel replyChannel) {
        if (replyChannel == ReplyChannel.MQ) {
            return ReplyChannel.MQ.name();
        }
        if (intent.payload() instanceof Map<?, ?> payload) {
            Object reply = payload.get("reply");
            if (reply instanceof A2aEnvelope.A2aReplySpec spec) {
                A2aReplyMode mode = spec.mode();
                return mode == null ? A2aReplyMode.STREAM.name() : mode.name();
            }
        }
        return A2aReplyMode.STREAM.name();
    }

    private static String resolveCorrelationId(AccessIntent intent) {
        if (intent.payload() instanceof Map<?, ?> payload) {
            Object correlationId = payload.get("correlationId");
            return correlationId == null ? null : correlationId.toString();
        }
        return null;
    }
}
