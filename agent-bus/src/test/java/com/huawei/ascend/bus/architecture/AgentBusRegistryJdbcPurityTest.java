package com.huawei.ascend.bus.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layered purity harness for the Stage 4 registry/discovery runtime
 * ({@code com.huawei.ascend.bus.registry..}).
 *
 * <p>Three invariants are pinned here, mirroring
 * {@link AgentBusForwardingSpiPurityTest}'s structure for the forwarding
 * substrate:
 *
 * <ol>
 *   <li><b>JDBC confinement</b> — {@code java.sql} / {@code javax.sql} /
 *       {@code org.springframework.jdbc..} may be imported ONLY inside
 *       {@code registry.runtime.persistence.jdbc..}. The {@code api} /
 *       {@code discovery} / {@code health} / {@code tenant} subpackages call
 *       the {@link com.huawei.ascend.bus.registry.runtime.persistence.jdbc.AgentRegistryRepository}
 *       port and never touch JDBC directly (ADR-0160 decision 4).</li>
 *   <li><b>Consul forbidden</b> — the entire {@code registry..} package is
 *       Consul-free in MVP. Phase 2 introduces Consul under an ADR-gated
 *       exemption (ADR-0160 decision 2 / NFR-2 trip-wire).</li>
 *   <li><b>Spring Web + Micrometer confinement</b> (ESC-2(b) follow-up) —
 *       {@code org.springframework.web..} and {@code io.micrometer..} may be
 *       imported ONLY inside {@code registry.runtime.api..},
 *       {@code registry.runtime.discovery..}, and
 *       {@code registry.runtime.health..}. The {@code tenant} subpackage
 *       stays pure Java so non-HTTP-entry callers (schedulers, async
 *       handlers) can use {@code ThreadLocalTenantContext} without pulling
 *       Servlet / Web onto the classpath. The {@code persistence.jdbc}
 *       subpackage also stays free of Spring Web / Micrometer — it imports
 *       Spring JDBC / transaction types but not Web / Micrometer.</li>
 * </ol>
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + ESC-2(b) boundary evolution.
 * The post-edit gate rule {@code req-2026-003-jdbc-confined-to-persistence}
 * enforces the JDBC invariant at edit time; this test is the second layer.
 * The Spring Web / Micrometer confinement rule has no post-edit gate yet —
 * this test is the sole enforcer (S5 follow-up to ESC-2(b)).
 *
 * <p>One {@code @Test} per forbidden dependency so a violation reports the
 * exact offending import. Test classes are excluded — the rule constrains
 * the shipped runtime surface, not test scaffolding.
 *
 * <p>Assertion ID: HA-002-REG.
 */
class AgentBusRegistryJdbcPurityTest {

    /**
     * Production registry runtime classes only
     * ({@code com.huawei.ascend.bus.registry} and sub-packages). Test
     * classes are excluded.
     */
    private static final JavaClasses REGISTRY_RUNTIME = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.huawei.ascend.bus.registry");

    /**
     * Sub-packages that ARE allowed to import JDBC / Spring JDBC types.
     * Everything outside this set is JDBC-free.
     */
    private static final String JDBC_ADAPTER = "com.huawei.ascend.bus.registry.runtime.persistence.jdbc..";

    /**
     * Sub-packages that ARE allowed to import Spring Web / Micrometer types
     * (ESC-2(b) boundary — ADR-0160 decision 7). Three runtime subpackages
     * need Spring Web / Micrometer:
     * <ul>
     *   <li>{@code api} — {@code @RestController} / {@code @RequestMapping}</li>
     *   <li>{@code discovery} — none directly, but kept inside the boundary
     *       for future Prometheus / OpenAPI annotations</li>
     *   <li>{@code health} — {@code @Component} / {@code @Scheduled} /
     *       {@code RestClient}</li>
     * </ul>
     * The root {@code runtime} package (for
     * {@code RegistryObservabilityConfig} / {@code RegistrySchedulingConfig})
     * is also licensed for Spring Web / Micrometer because it ships the
     * observability facade that uses {@code io.micrometer.core.instrument}.
     */
    private static final String[] SPRING_WEB_MICROMETER_ALLOWED = {
            "com.huawei.ascend.bus.registry.runtime..",
            "com.huawei.ascend.bus.registry.runtime.api..",
            "com.huawei.ascend.bus.registry.runtime.discovery..",
            "com.huawei.ascend.bus.registry.runtime.health..",
    };

    // ---- 1. JDBC confinement (ADR-0160 decision 4) -----------------------

    @Test
    void jdbc_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("java.sql..")
                .because("JDBC lives only in registry.runtime.persistence.jdbc.. (ADR-0160 "
                       + "decision 4); api / discovery / health / tenant call the "
                       + "AgentRegistryRepository port and never touch JDBC directly.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_sql_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("javax.sql..")
                .because("javax.sql (DataSource) lives only in registry.runtime.persistence.jdbc.. "
                       + "(ADR-0160 decision 4); the rest of the runtime stays pure Java.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_jdbc_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("Spring JDBC (NamedParameterJdbcTemplate / RowMapper) lives only in "
                       + "registry.runtime.persistence.jdbc.. (ADR-0160 decision 4).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 2. Consul forbidden across the entire registry runtime -----------

    @Test
    void consul_client_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .should().dependOnClassesThat().resideInAPackage("com.ecwid.consul..")
                .because("MVP registry runtime is Consul-free; phase 2 introduces Consul under "
                       + "an ADR-gated exemption (ADR-0160 decision 2 / NFR-2 trip-wire).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_cloud_consul_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud.consul..")
                .because("MVP registry runtime is Consul-free; Spring Cloud Consul is a phase-2 "
                       + "candidate, never a Stage 4 dependency.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 3. Spring Web + Micrometer confinement (ESC-2(b) follow-up) ------
    // spring-boot-starter-web (provided) and io.micrometer:micrometer-core
    // (provided) are ADR-0160 decision 7 additions. They must NOT leak into
    // the persistence.jdbc adapter (which is Spring JDBC / transaction only)
    // or the tenant subpackage (which must stay pure Java so non-HTTP
    // callers can use ThreadLocalTenantContext without pulling Web in).
    //
    // The runtime.{api,discovery,health} subpackages + the runtime root
    // (for RegistryObservabilityConfig / RegistrySchedulingConfig) ARE
    // licensed to import Spring Web / Micrometer. Rules below target only
    // the two subpackages where leakage is the concern — testing the
    // negative space via resideOutsideOfPackage("runtime..") would be
    // vacuous because every registry class lives under runtime.. .

    @Test
    void spring_web_does_not_leak_into_persistence_jdbc_adapter() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("persistence.jdbc adapter is Spring JDBC / transaction only — Spring "
                       + "Web must not leak in (ESC-2(b) confinement, ADR-0160 decision 7).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_web_does_not_leak_into_tenant_subpackage() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry.runtime.tenant..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("tenant subpackage stays pure Java so background schedulers / async "
                       + "handlers can use ThreadLocalTenantContext without pulling Spring Web "
                       + "onto the classpath (ESC-2 design pivot, ADR-0160 decision 6).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_persistence_jdbc_adapter() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("persistence.jdbc adapter is Spring JDBC / transaction only — "
                       + "Micrometer must not leak in (ESC-2(b) confinement).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_tenant_subpackage() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry.runtime.tenant..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("tenant subpackage stays pure Java so background schedulers / async "
                       + "handlers can use ThreadLocalTenantContext without pulling Micrometer "
                       + "onto the classpath (ESC-2 design pivot).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 4. Servlet API forbidden outside the api subpackage --------------
    // The api subpackage ships @RestController (Spring Web's servlet-based
    // adapter) but never jakarta.servlet.* directly. Every other subpackage
    // must stay Servlet-free.

    @Test
    void jakarta_servlet_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("ESC-2 design pivot: no Servlet API in the registry runtime — tenant "
                       + "isolation has no filter entry point. MvpRegistryController uses Spring "
                       + "Web annotations but never jakarta.servlet.* directly.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_servlet_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .should().dependOnClassesThat().resideInAPackage("javax.servlet..")
                .because("ESC-2 design pivot: no legacy Servlet API in the registry runtime.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 5. Jackson confinement — discovery only -------------------------
    // RouteHandleCodec (in runtime.discovery) is the only subpackage that
    // needs Jackson for opaque route-handle encoding. api / health / tenant /
    // persistence.jdbc stay Jackson-free.

    @Test
    void jackson_confined_to_discovery_subpackage() {
        noClasses().that().resideInAPackage("com.huawei.ascend.bus.registry..")
                .and().resideOutsideOfPackage("com.huawei.ascend.bus.registry.runtime.discovery..")
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("Jackson is licensed only inside registry.runtime.discovery (for "
                       + "RouteHandleCodec's opaque handle encoding); every other subpackage "
                       + "stays serialisation-agnostic (ADR-0160 decision 3/5).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- import-liveness guard ------------------------------------------

    /**
     * Guards against an accidental empty import (e.g. a typo'd package path)
     * silently passing every {@code noClasses} rule above.
     */
    @Test
    void registry_runtime_import_is_non_empty() {
        assertThat(REGISTRY_RUNTIME)
                .as("registry runtime production class import must be non-empty (liveness guard)")
                .isNotEmpty();
    }
}
