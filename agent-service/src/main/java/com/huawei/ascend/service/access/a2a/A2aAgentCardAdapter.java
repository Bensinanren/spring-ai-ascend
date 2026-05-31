package com.huawei.ascend.service.access.a2a;

import java.util.Objects;

public final class A2aAgentCardAdapter implements A2aAgentCardService {

    private final A2aAgentCard agentCard;

    public A2aAgentCardAdapter(A2aAgentCard agentCard) {
        this.agentCard = Objects.requireNonNull(agentCard, "agentCard");
    }

    @Override
    public A2aAgentCard getAgentCard() {
        return agentCard;
    }
}
