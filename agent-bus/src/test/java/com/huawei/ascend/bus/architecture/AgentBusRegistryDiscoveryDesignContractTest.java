package com.huawei.ascend.bus.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design-level contract harness for {@code ICD-Agent-Registry-Discovery}
 * (Stage 4, after slices 1-5 landed).
 *
 * <p>Two layers of invariants are pinned here:
 * <ol>
 *   <li><b>ICD document assertions</b> — read the ICD and the L1 README as
 *       plain text and anchor on stable phrases (HD3-001..006). A future edit
 *       that silently weakens the ICD (dropping the tenantId requirement,
 *       leaking Task state into discovery results) fails the build.</li>
 *   <li><b>Runtime promotion assertions</b> — ArchUnit checks that the
 *       {@code com.huawei.ascend.bus.spi.registry} package now <em>exists</em>
 *       as the runtime-promoted SPI surface (HD3-007 stage-4 lift), and that
 *       the runtime implementation package
 *       {@code com.huawei.ascend.bus.registry.runtime..} stays free of
 *       {@code TenantFilter} (ESC-2 design pivot — tenant isolation by
 *       explicit parameter + WHERE + RLS, no Servlet filter).</li>
 * </ol>
 *
 * <p>Authority: {@code docs/architecture/l0/05-contracts/human-readable/
 * ICD-agent-registry-discovery.md} (HD3-001..007);
 * {@code docs/adr/0160-stage4-registry-spi-runtime-promotion.yaml}.
 *
 * <p><b>Trip-wire evolution (RB3)</b>: the Stage 3 trip-wire method (the
 * assertion that no runtime registry production class may exist under
 * {@code com.huawei.ascend.bus.spi}) has been deleted — Stage 4 admitted
 * the registry SPI per HD3-007, so the Stage 3 "no runtime registry class"
 * boundary is no longer the correct invariant. The post-edit gate rule
 * {@code req-2026-003-trip-wire-removed} (regex-check, invert) prevents
 * re-introduction of that method by name. The runtime-promotion assertions
 * below replace the trip-wire: they assert the SPI package <em>exists</em>
 * and the runtime implementation <em>is free of TenantFilter</em>.
 */
class AgentBusRegistryDiscoveryDesignContractTest {

    private static final Path ICD = Path.of(
            "../docs/architecture/l0/05-contracts/human-readable/ICD-agent-registry-discovery.md");
    private static final Path L1_README = Path.of("../architecture/L1-High-Level-Design/agent-bus/README.md");

    private static String icdText;
    private static String readmeText;

    @BeforeAll
    static void readDesignSources() throws Exception {
        assertThat(ICD)
                .as("ICD-Agent-Registry-Discovery must be reachable from the surefire working "
                  + "directory (agent-bus module basedir)")
                .exists();
        icdText = Files.readString(ICD);

        assertThat(L1_README)
                .as("agent-bus L1 README must be reachable from the surefire working directory")
                .exists();
        readmeText = Files.readString(L1_README);
    }

    // ---- slice 3: ICD presence + L1 linkage (meta) ------------------------

    @Test
    void registry_discovery_icd_exists() {
        assertThat(icdText)
                .as("ICD-Agent-Registry-Discovery must declare its ICD ID header")
                .contains("# ICD-Agent-Registry-Discovery");
    }

    @Test
    void l1_readme_links_registry_discovery_icd() {
        assertThat(readmeText)
                .as("agent-bus L1 README must link the registry/discovery ICD (slice 3 back-link)")
                .contains("ICD-agent-registry-discovery.md");
    }

    // ---- ICD contract test 1-2: tenantId is mandatory on both ends --------

    @Test
    void registry_entry_requires_tenant_id() {
        assertThat(icdText)
                .as("ICD must declare a Registry Entry Required Fields block (HD3-002)")
                .contains("Registry Entry Required Fields")
                .as("ICD must state tenantId is a mandatory part of the registry key (HD3-003)")
                .contains("registry key 必须包含");
    }

    @Test
    void discovery_query_requires_tenant_id() {
        assertThat(icdText)
                .as("ICD must declare a Discovery Query block")
                .contains("Discovery Query")
                .as("ICD must state the discovery query must carry tenantId (HD3-003)")
                .contains("query 必须携带");
    }

    // ---- ICD contract test 3: discovery result excludes Task state --------

    @Test
    void discovery_result_has_no_task_execution_state() {
        assertThat(icdText)
                .as("ICD must forbid Task execution state in discovery results (HD3-001)")
                .contains("不得携带 Task execution state");
    }

    // ---- ICD contract test 4-5: health & version expressed explicitly -----

    @Test
    void unhealthy_target_has_explicit_health_state() {
        assertThat(icdText)
                .as("ICD must express unhealthy targets with an explicit health failure mode")
                .contains("health_unavailable")
                .as("ICD must require unhealthy targets to remain visible with explicit health")
                .contains("显式标注 health");
    }

    @Test
    void version_mismatch_has_explicit_result() {
        assertThat(icdText)
                .as("ICD must express version mismatch as an explicit failure mode (HD3-005)")
                .contains("version_unavailable")
                .contains("version mismatch");
    }

    // ---- ICD contract test 6: cross-tenant isolation ----------------------

    @Test
    void cross_tenant_query_rejected() {
        assertThat(icdText)
                .as("ICD must name a cross-tenant isolation failure mode (HD3-003)")
                .contains("tenant_isolation_violation")
                .as("ICD must forbid cross-tenant fallback")
                .contains("禁止跨 tenant fallback");
    }

    // ---- Stage 4 runtime promotion (replaces Stage 3 trip-wire) -----------
    // HD3-007: Stage 4 admits the registry SPI. The old Stage 3 trip-wire
    // (the "no runtime registry production class" assertion) is deleted; the
    // post-edit gate rule req-2026-003-trip-wire-removed prevents
    // re-introduction. The assertions below pin the new Stage 4 invariant:
    // the SPI package exists, the runtime implementation exists, and the
    // runtime implementation is free of TenantFilter (ESC-2 design pivot).

    @Test
    void spi_registry_package_exists_as_runtime_promoted_surface() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.ascend.bus.spi.registry");
        assertThat(classes)
                .as("Stage 4 lift (HD3-007): com.huawei.ascend.bus.spi.registry must exist as "
                  + "the runtime-promoted SPI surface (replaces Stage 3 trip-wire).")
                .isNotEmpty();

        Set<String> spiClassNames = classes.stream()
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());
        assertThat(spiClassNames)
                .as("Stage 4 SPI surface must expose the dual-method discovery contract + "
                  + "tenant isolation port + opaque route handle resolution target.")
                .contains("AgentDiscoveryService", "AgentCardDto", "TenantContext",
                        "TenantIsolationViolationException", "RouteResolution", "AgentCard",
                        "Nullable");
    }

    @Test
    void runtime_registry_implementation_exists() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.ascend.bus.registry.runtime");
        assertThat(classes)
                .as("Stage 4 runtime implementation must exist (slices S2/S4 shipped)")
                .isNotEmpty();

        Set<String> implClassNames = classes.stream()
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());
        assertThat(implClassNames)
                .as("Stage 4 runtime must ship the MVP discovery impl, the JDBC adapter, "
                  + "the controller, the health-probe scheduler, and the route-handle codec.")
                .contains("PgMvpDiscoveryServiceImpl", "JdbcAgentRegistryRepository",
                        "MvpRegistryController", "MvpHealthProbeScheduler",
                        "RouteHandleCodec", "ThreadLocalTenantContext",
                        "RegistryObservabilityConfig", "RegistrySchedulingConfig");
    }

    @Test
    void runtime_registry_is_free_of_tenant_filter() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.ascend.bus.registry");
        Set<String> classNames = classes.stream()
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());
        assertThat(classNames)
                .as("ESC-2 design pivot (ADR-0160 decision 6): TenantFilter / "
                  + "TenantFilterRegistration must NOT exist in the registry runtime. "
                  + "Tenant isolation is enforced by explicit tenantId parameter + "
                  + "WHERE clause + RLS, not by a Servlet filter.")
                .doesNotContain("TenantFilter", "TenantFilterRegistration");
    }

    @Test
    void runtime_registry_is_free_of_jakarta_servlet() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.ascend.bus.registry");
        assertThat(classes)
                .as("ESC-2 design pivot: no Servlet API in the registry runtime — tenant "
                  + "isolation has no filter entry point. MvpRegistryController uses Spring "
                  + "Web annotations (@RestController) but never jakarta.servlet.*.")
                .isNotEmpty();
        // ArchUnit-based noClassDependenciesOn would duplicate
        // AgentBusRegistryJdbcPurityTest's servlet rule; here we add a
        // cheaper presence assertion that the registry runtime package does
        // not declare a class named *Filter or *Servlet.
        Set<String> filterOrServlet = classes.stream()
                .map(JavaClass::getSimpleName)
                .filter(n -> n.endsWith("Filter") || n.endsWith("Servlet"))
                .collect(Collectors.toSet());
        assertThat(filterOrServlet)
                .as("ESC-2 design pivot: no *Filter or *Servlet class in registry runtime")
                .isEmpty();
    }
}
