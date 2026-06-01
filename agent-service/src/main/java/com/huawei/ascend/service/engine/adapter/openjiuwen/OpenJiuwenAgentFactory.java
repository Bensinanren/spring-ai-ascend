package com.huawei.ascend.service.engine.adapter.openjiuwen;

import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import com.openjiuwen.core.singleagent.ReActAgent;

/**
 * The seam between the engine and a concrete openJiuwen agent. The engine knows
 * how to <em>run</em> a {@link ReActAgent} and map its result to events; this
 * factory supplies a configured agent for a given execution. The concrete
 * build logic (prompt / tools / model) lives in the developer's agent-app
 * module (design §1.1 layer ③, §10.5).
 */
public interface OpenJiuwenAgentFactory {

    ReActAgent create(AgentExecutionContext context);
}
