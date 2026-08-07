package com.keglevich.maintenanceassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the dependency that makes {@code spring.flyway.*} mean anything.
 *
 * <p>Boot 4 split auto-configuration into per-technology modules. {@code flyway-core} is
 * only the migration engine: without {@code org.springframework.boot:spring-boot-flyway}
 * there is no {@code FlywayAutoConfiguration}, so no Flyway bean is ever created and the
 * {@code spring.flyway.*} block in application.yml is inert — with no startup error to
 * say so. Phase 2 ships the schema as Flyway migrations, so that silence would surface
 * as missing tables at runtime rather than as a build failure.
 *
 * <p>This test is deliberately database-free: it asserts the auto-configuration is on the
 * classpath and registered for import, which is exactly the condition that dropping the
 * dependency would break. That the migration then actually runs is covered in Phase 2 by
 * the Testcontainers integration tests.
 */
class FlywayAutoConfigurationTest {

    private static final String FLYWAY_AUTO_CONFIGURATION =
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    @Test
    void flywayAutoConfigurationIsOnTheClasspath() {
        assertThat(ClassUtils.isPresent(FLYWAY_AUTO_CONFIGURATION, getClass().getClassLoader()))
                .as("spring-boot-flyway must be a dependency, or spring.flyway.* is silently ignored")
                .isTrue();
    }

    @Test
    void flywayAutoConfigurationIsRegisteredForImport() {
        ClassLoader classLoader = getClass().getClassLoader();
        List<String> candidates = StreamSupport
                .stream(ImportCandidates.load(org.springframework.boot.autoconfigure.AutoConfiguration.class,
                        classLoader).spliterator(), false)
                .toList();

        assertThat(candidates)
                .as("present on the classpath but not imported would be just as dead")
                .contains(FLYWAY_AUTO_CONFIGURATION);
    }
}
