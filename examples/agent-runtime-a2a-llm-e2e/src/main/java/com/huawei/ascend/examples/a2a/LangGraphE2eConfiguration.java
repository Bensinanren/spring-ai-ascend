package com.huawei.ascend.examples.a2a;

import com.huawei.ascend.runtime.engine.langgraph.LangGraphRuntimeClient;
import com.huawei.ascend.runtime.engine.langgraph.LangGraphRuntimeClientHandler;
import com.huawei.ascend.runtime.engine.langgraph.LangGraphRuntimeClientProperties;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hosts an agent served by a remote LangGraph runtime (LangGraph Platform /
 * {@code langgraph-api} dev server) behind this sample's A2A surface.
 *
 * <p>Activate with {@code sample.a2a.agent=langgraph} and point
 * {@code sample.langgraph.base-url} at the LangGraph deployment; the
 * assistant id selects the deployed graph. An API key is sent as
 * {@code X-Api-Key} only when configured.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sample.a2a", name = "agent", havingValue = "langgraph")
public class LangGraphE2eConfiguration {

    static final String AGENT_ID = "langgraph-agent";

    @Bean
    AgentRuntimeHandler langGraphAgentHandler(
            @Value("${sample.langgraph.base-url:${SAA_SAMPLE_LANGGRAPH_BASE_URL:http://localhost:2024}}")
            String baseUrl,
            @Value("${sample.langgraph.assistant-id:${SAA_SAMPLE_LANGGRAPH_ASSISTANT_ID:agent}}")
            String assistantId,
            @Value("${sample.langgraph.api-key:${SAA_SAMPLE_LANGGRAPH_API_KEY:}}")
            String apiKey) {
        Map<String, String> headers = apiKey == null || apiKey.isBlank()
                ? Map.of() : Map.of("X-Api-Key", apiKey);
        LangGraphRuntimeClientProperties properties = new LangGraphRuntimeClientProperties(
                baseUrl, assistantId, "/runs/stream", headers);
        return new LangGraphRuntimeClientHandler(AGENT_ID, new LangGraphRuntimeClient(properties));
    }
}
