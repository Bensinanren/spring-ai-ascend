package com.huawei.ascend.bus.registry.runtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} support for the agent registry MVP health-probe
 * scheduler (ADR-0160 + KF-1).
 *
 * <p>{@code agent-bus} is a library jar — there is no {@code @SpringBootApplication}
 * class to anchor {@code @EnableScheduling} on (KF-1). This standalone
 * {@code @Configuration} class is picked up by the runtime consumer's
 * (agent-runtime {@code LocalA2aRuntimeHost}) component scan, which already
 * imports {@code com.huawei.ascend.bus.registry.runtime.*} via the registry
 * SPI's {@code @ComponentScan} footprint. Once phase 2 retires
 * {@code MvpHealthProbeScheduler}, this class can be deleted in lockstep.
 *
 * <p>Authority: ADR-0160 + KF-1 (agent-bus has no {@code @SpringBootApplication}).
 */
@Configuration
@EnableScheduling
public class RegistrySchedulingConfig {
}
