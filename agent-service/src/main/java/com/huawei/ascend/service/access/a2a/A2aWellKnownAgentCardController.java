package com.huawei.ascend.service.access.a2a;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class A2aWellKnownAgentCardController {

    private final A2aAgentCardService agentCardService;

    public A2aWellKnownAgentCardController(A2aAgentCardService agentCardService) {
        this.agentCardService = agentCardService;
    }

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<A2aAgentCard> getAgentCard() {
        return ResponseEntity.ok(agentCardService.getAgentCard());
    }
}
