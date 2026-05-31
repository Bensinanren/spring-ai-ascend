package com.huawei.ascend.service.access.a2a;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/a2a")
public final class A2aAccessController {

    private final A2aAccessService accessService;
    private final A2aAgentCardService agentCardService;
    private final A2aOutputRegistry outputRegistry;

    public A2aAccessController(
            A2aAccessService accessService,
            A2aAgentCardService agentCardService,
            A2aOutputRegistry outputRegistry) {
        this.accessService = accessService;
        this.agentCardService = agentCardService;
        this.outputRegistry = outputRegistry;
    }

    @GetMapping("/agent-card")
    public ResponseEntity<A2aAgentCard> getAgentCard() {
        return ResponseEntity.ok(agentCardService.getAgentCard());
    }

    @PostMapping("/tasks/send")
    public ResponseEntity<A2aAcceptedResponse> send(@RequestBody A2aEnvelope envelope) {
        return ResponseEntity.ok(accessService.send(envelope));
    }

    @PostMapping("/tasks/resume")
    public ResponseEntity<A2aAcceptedResponse> resume(@RequestBody A2aEnvelope envelope) {
        return ResponseEntity.ok(accessService.resume(envelope));
    }

    @PostMapping("/tasks/cancel")
    public ResponseEntity<A2aAcceptedResponse> cancel(@RequestBody A2aEnvelope envelope) {
        return ResponseEntity.ok(accessService.cancel(envelope));
    }

    @GetMapping("/tasks/{tenantId}/{sessionId}/{taskId}/outputs")
    public ResponseEntity<List<A2aOutput>> getOutputs(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @PathVariable String taskId) {
        return ResponseEntity.ok(outputRegistry.list(new A2aOutputHandle(tenantId, sessionId, taskId)));
    }

    @GetMapping("/tasks/{tenantId}/{sessionId}/{taskId}/stream")
    public SseEmitter streamOutputs(
            @PathVariable String tenantId,
            @PathVariable String sessionId,
            @PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        A2aOutputHandle handle = new A2aOutputHandle(tenantId, sessionId, taskId);
        Runnable unsubscribe = outputRegistry.subscribe(handle, output -> send(emitter, output));
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ignored -> unsubscribe.run());
        return emitter;
    }

    private static void send(SseEmitter emitter, A2aOutput output) {
        try {
            emitter.send(SseEmitter.event().name(output.kind()).data(output));
            if (output.terminal()) {
                emitter.complete();
            }
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }
}
