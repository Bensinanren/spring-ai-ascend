package com.huawei.ascend.examples.deepresearch.read.a2a;

import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.SkillHubProvider;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the YAML assembly path (selected via {@code read-agent.yaml-classpath},
 * which the {@code stub} / {@code prod} Spring profiles override) into a single
 * {@link ReadAgentHandler} bean. agent-runtime discovers the handler through
 * the {@link AgentRuntimeHandler} SPI and serves it on port 13005.
 *
 * <p>TOPOLOGY §4.5 (3-profile fail-fast): the default profile ships a BLANK
 * {@code read-agent.yaml-classpath}. The {@link #readAgentYamlPath} bean throws
 * a clear {@link IllegalStateException} in that case, so booting without
 * {@code -Dspring.profiles.active=stub|prod} fails immediately with an
 * actionable message instead of silently running a half-configured agent.
 *
 * <p>Also publishes a filesystem-backed {@link SkillHubProvider} so the runtime
 * SkillHub auto-configuration activates (its condition is
 * {@code @ConditionalOnBean(SkillHubProvider.class)}). All directories under
 * {@code read-agent.skillhub.root} containing a {@code SKILL.md} file are
 * exposed as skills and installed into the ReActAgent on every execution.
 */
@Configuration(proxyBeanMethods = false)
public class ReadAgentConfiguration {

    @Bean
    Path readAgentYamlPath(@Value("${read-agent.yaml-classpath:}") String yamlClasspath) {
        if (yamlClasspath == null || yamlClasspath.isBlank()) {
            throw new IllegalStateException(
                    "read-agent.yaml-classpath is blank — TOPOLOGY §4.5 requires an explicit "
                            + "profile. Start with --spring.profiles.active=stub (fixture-backed, "
                            + "no network) or prod (real HTTP fetch via java.net.http.HttpClient).");
        }
        return ClasspathYamlExtractor.extract(yamlClasspath);
    }

    @Bean
    AgentRuntimeHandler readAgentHandler(Path readAgentYamlPath) {
        return new ReadAgentHandler(readAgentYamlPath);
    }

    @Bean
    SkillHubProvider readAgentSkillHubProvider(
            @Value("${read-agent.skillhub.root:skills}") String skillRoot) {
        return new LocalDirectorySkillHubProvider(Path.of(skillRoot));
    }
}
