package com.keglevich.maintenanceassistant.ingestion.seed;

import com.keglevich.maintenanceassistant.ingestion.FileStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The flag that decides whether seeding happens at all.
 *
 * <p>Seeding is a deliberate act. The runner is an {@link org.springframework.boot.ApplicationRunner}
 * gated by {@code @ConditionalOnProperty}, so "off" does not mean a bean that checks a boolean and
 * returns — it means <b>no bean at all</b>, and therefore nothing that can run by accident on a
 * plain application start. That distinction is the whole point of the gate, and it is only
 * observable at the context level, which is why this is a slice test rather than a unit one.
 *
 * <p>No database and no container: the condition is evaluated before the bean is instantiated, so
 * the collaborators can be mocks that are never called. Making this an integration test would buy a
 * Postgres startup to assert something that has nothing to do with Postgres.
 *
 * <p>OUT OF SCOPE: what the runner does once enabled — the counts, the documents and the
 * idempotence all need a real database and live in CorpusSeedRunnerIT.
 *
 * <p>SIBLING: CorpusSeedRunnerIT, which covers the seeding itself.
 */
class CorpusSeedRunnerGateTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(Collaborators.class, CorpusSeedRunner.class)
            .withPropertyValues("maintenance.corpus-seed.resource=classpath:corpus/protocols.ndjson");

    @Test
    void theRunnerBeanIsAbsentWhenTheFlagIsUnset() {
        // The default. A deployment that never sets the variable cannot seed, even by mistake.
        contexts.run(context -> assertThat(context).doesNotHaveBean(CorpusSeedRunner.class));
    }

    @Test
    void theRunnerBeanIsAbsentWhenTheFlagIsFalse() {
        contexts.withPropertyValues("maintenance.corpus-seed.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CorpusSeedRunner.class));
    }

    @Test
    void theRunnerBeanIsAbsentWhenTheFlagIsNotTheStringTrue() {
        // havingValue="true" is an exact match, not a truthiness test. "yes" and "1" are how a
        // hand-edited compose file expresses intent, and they deliberately do NOT seed — better a
        // demo that visibly has no corpus than one that seeds where it was not meant to.
        contexts.withPropertyValues("maintenance.corpus-seed.enabled=yes")
                .run(context -> assertThat(context).doesNotHaveBean(CorpusSeedRunner.class));
        contexts.withPropertyValues("maintenance.corpus-seed.enabled=1")
                .run(context -> assertThat(context).doesNotHaveBean(CorpusSeedRunner.class));
    }

    @Test
    void theRunnerBeanIsPresentWhenTheFlagIsTrue() {
        // The other half of the claim, and the one that stops the three tests above passing because
        // the bean can never be created at all.
        contexts.withPropertyValues("maintenance.corpus-seed.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CorpusSeedRunner.class));
    }

    /** Never called: the gate is decided before the runner is constructed. */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        JdbcClient jdbc() {
            return mock(JdbcClient.class);
        }

        @Bean
        CorpusSeedProperties seedProperties() {
            return new CorpusSeedProperties(true, "classpath:corpus/protocols.ndjson");
        }

        @Bean
        FileStorageProperties fileProperties() {
            return new FileStorageProperties("target/unused-by-this-test");
        }
    }
}
