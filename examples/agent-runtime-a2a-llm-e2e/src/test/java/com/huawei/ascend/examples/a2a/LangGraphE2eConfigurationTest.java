package com.huawei.ascend.examples.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.runtime.engine.langgraph.LangGraphRuntimeClientHandler;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LangGraphE2eConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LangGraphE2eConfiguration.class);

    @Test
    void activatesOnlyWhenLangGraphAgentSelected() {
        runner.withPropertyValues("sample.a2a.agent=langgraph")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AgentRuntimeHandler.class);
                    assertThat(ctx.getBean(AgentRuntimeHandler.class))
                            .isInstanceOf(LangGraphRuntimeClientHandler.class);
                    assertThat(ctx.getBean(AgentRuntimeHandler.class).agentId())
                            .isEqualTo("langgraph-agent");
                });

        runner.withPropertyValues("sample.a2a.agent=agentscope")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AgentRuntimeHandler.class));
    }
}
