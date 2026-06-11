package com.huawei.ascend.service.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the serviceization module boundaries: the SPI and its reference
 * implementations stay Spring-free, never reach into the frozen bus SPI plane,
 * and touch agent-runtime only through its public engine SPI.
 */
class ServicePackageBoundaryTest {

    private static final JavaClasses SERVICE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.huawei.ascend.service");

    @Test
    void serviceModuleIsSpringFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.huawei.ascend.service..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .allowEmptyShould(false);
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void serviceModuleDoesNotDependOnBusSpi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.huawei.ascend.service..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.huawei.ascend.bus.spi..")
                .allowEmptyShould(false);
        rule.check(SERVICE_CLASSES);
    }

    @Test
    void serviceModuleTouchesRuntimeOnlyThroughEngineSpi() {
        // Allowlist, not denylist: every agent-runtime package is forbidden by
        // default, so a new runtime package can never silently widen this seam.
        // The framework-neutral engine SPI — plus the engine-root execution
        // context types that appear in SPI signatures — is the only permitted
        // dependency surface.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.huawei.ascend.service..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.huawei.ascend.runtime..")
                                .and(not(resideInAnyPackage(
                                        "com.huawei.ascend.runtime.engine",
                                        "com.huawei.ascend.runtime.engine.spi.."))))
                .allowEmptyShould(false);
        rule.check(SERVICE_CLASSES);
    }
}
