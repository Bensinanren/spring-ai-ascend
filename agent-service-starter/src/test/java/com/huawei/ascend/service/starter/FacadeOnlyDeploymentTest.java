package com.huawei.ascend.service.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.runtime.boot.RuntimeAutoConfiguration;
import com.huawei.ascend.service.spi.registry.RuntimeRegistry;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * A facade-only deployment (service edge without hosting agents) carries
 * agent-runtime on the classpath solely for the shared JWT validator — the
 * A2A runtime kernel must stay down with one switch instead of a per-class
 * exclude list.
 */
class FacadeOnlyDeploymentTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RuntimeAutoConfiguration.class,
                    AgentServiceAutoConfiguration.class));

    @Test
    void runtimeKernelBootsAlongsideTheFacadeByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RuntimeRegistry.class);
            assertThat(context).hasSingleBean(TaskStore.class);
        });
    }

    @Test
    void disablingAgentRuntimeKeepsTheFacadeUpAndTheKernelDown() {
        contextRunner.withPropertyValues("agent-runtime.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(RuntimeRegistry.class);
            assertThat(context).hasSingleBean(RuntimeRegistryController.class);
            assertThat(context).doesNotHaveBean(TaskStore.class);
            assertThat(context).doesNotHaveBean(RuntimeAutoConfiguration.class);
        });
    }
}
