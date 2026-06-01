package com.huawei.ascend.samples.openjiuwen;

import com.huawei.ascend.service.engine.adapter.openjiuwen.OpenJiuwenAgentFactory;
import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import java.util.List;
import java.util.Map;

/**
 * Developer-authored agent app (engine model design layer ③). It decides what
 * this agent <em>is</em> — its prompt, iteration budget, and model — while the
 * engine's {@code OpenJiuwenAgentHandler} owns how it runs. LLM settings come
 * from environment variables so secrets stay out of source.
 */
public class EchoOpenJiuwenAgentFactory implements OpenJiuwenAgentFactory {

    private static final String SYSTEM_PROMPT = "You are a concise echo assistant. Reply briefly.";

    @Override
    public ReActAgent create(AgentExecutionContext context) {
        AgentCard card = AgentCard.builder()
                .id("echo-agent")
                .name("echo-agent")
                .description("echo sample agent")
                .build();
        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", SYSTEM_PROMPT)))
                .maxIterations(Integer.parseInt(env("OJW_MAX_ITERATIONS", "3")))
                .build()
                .configureModelClient(
                        env("OJW_MODEL_PROVIDER", "openai"),
                        env("OJW_API_KEY", ""),
                        env("OJW_API_BASE", "http://localhost:4000/v1"),
                        env("OJW_MODEL_NAME", "gpt-5.4-mini"),
                        Boolean.parseBoolean(env("OJW_SSL_VERIFY", "false")));
        agent.configure(config);
        return agent;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
