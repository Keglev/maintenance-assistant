package com.keglevich.maintenanceassistant.ingestion.seed;

import com.keglevich.maintenanceassistant.ingestion.FileStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corpus seed against a real database.
 *
 * <p><b>Why this needs Postgres rather than a mocked {@code JdbcClient}.</b> The seed's idempotence
 * is not written in Java — it is {@code ON CONFLICT (id) DO NOTHING} plus fixed UUIDs, decided
 * entirely by the database. A mocked client could only assert that {@code update()} was called
 * again, which is the opposite of the claim: the claim is that calling it again changes nothing.
 * The alternative to a container here is not a cheaper test, it is no test of the part that matters.
 *
 * <p><b>Why this is the one runner that earns a test.</b> Idempotence is the property the production
 * runbook actually relied on: seeding is re-run against an environment that may already hold the
 * corpus, and a seed that duplicated rows or overwrote edited documents would corrupt a live demo
 * rather than fail loudly.
 *
 * <p>The runner has already run ONCE by the time any test method executes — it is an
 * {@code ApplicationRunner} and this context enables it, so the first seeding happens exactly the
 * way production's does, at startup. Each test's second invocation is therefore a genuine re-run
 * against an already-seeded state.
 *
 * <p><b>A separate context from the other integration tests, unavoidably.</b> The shared {@code it}
 * profile sets {@code maintenance.corpus-seed.enabled=false} and says why: the 150 protocols do not
 * belong in tests that assert exact counts. Testing the seed means overriding exactly that, so this
 * cannot share their cached context. It is one extra context, deliberately kept to one — the flag
 * gate itself needs no database and lives in CorpusSeedRunnerGateTest.
 *
 * <p>Deliberately absent: any embedding client or provider stub. The runner does not chunk, embed or
 * call a provider — protocols land as {@code RECEIVED} for the ingestion module to pick up — so
 * there is nothing to stub, and adding a fake would suggest a collaborator that does not exist.
 *
 * <p>SIBLINGS: CorpusSeedRunnerGateTest (the flag), CorpusIntegrityTest (the corpus file itself).
 */
// The flag is an inlined property rather than a DynamicPropertyRegistrar entry: @ConditionalOnProperty
// is evaluated while the component scan runs, and at that point the `it` profile's
// corpus-seed.enabled=false would still be the winning value. Measured, not assumed — as a registrar
// entry the runner bean simply never existed.
@SpringBootTest(properties = {
        "maintenance.corpus-seed.enabled=true",
        "maintenance.corpus-seed.resource=classpath:corpus/protocols.ndjson"})
@ActiveProfiles("it")
@Testcontainers
@Import(CorpusSeedRunnerIT.SeedEnabledConfig.class)
class CorpusSeedRunnerIT {

    /** The production image, matching every other integration test: the schema needs pgvector. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path filesDir;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CorpusSeedRunner runner;

    private int protocolCount() {
        return jdbc.sql("SELECT count(*) FROM protocol").query(Integer.class).single();
    }

    private int countWhere(String predicate) {
        return jdbc.sql("SELECT count(*) FROM protocol WHERE " + predicate)
                .query(Integer.class).single();
    }

    private static long documentsOnDisk() throws IOException {
        try (Stream<Path> files = Files.walk(filesDir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    // ---------------------------------------------------------------------------------------
    // The count invariants
    // ---------------------------------------------------------------------------------------

    @Test
    void startupSeedsTheWholeCorpusAsReceived() {
        assertThat(protocolCount()).isEqualTo(165);
        // Nothing is chunked or embedded here by design: RECEIVED is exactly the state the
        // ingestion module picks protocols up from.
        assertThat(countWhere("status = 'RECEIVED'")).isEqualTo(165);
    }

    @Test
    void theCorpusIsSeededAsOneHundredAndFiftyApprovedAndFifteenUnapproved() {
        // Decision of 2026-08-11, and the reason the runner writes the approval columns itself
        // rather than leaving them to migration V5: V5 can only approve protocols it finds, and on
        // a fresh environment Flyway runs BEFORE the seed, so it finds an empty table. Without this
        // the demo corpus would come up entirely unreviewed on every new environment.
        assertThat(countWhere("approval_state = 'APPROVED'")).isEqualTo(150);
        assertThat(countWhere("approval_state = 'UNAPPROVED'")).isEqualTo(15);
    }

    @Test
    void approvedProtocolsAreAttributedToTheSeedRatherThanToAPerson() {
        // A system name, not an invented username: no human read these, and a person's name on the
        // record would be the exact unearned trust the approval state exists to expose.
        assertThat(countWhere("approval_state = 'APPROVED' AND approved_by = 'system:corpus-seed'"))
                .isEqualTo(150);
        assertThat(countWhere("approval_state = 'APPROVED' AND approved_at IS NULL")).isZero();
        assertThat(countWhere("approval_state = 'UNAPPROVED' AND approved_by IS NOT NULL")).isZero();
    }

    @Test
    void everyProtocolPointsAtADocumentThatIsActuallyOnTheVolume() throws IOException {
        assertThat(documentsOnDisk()).isEqualTo(165L);

        List<String> paths = jdbc.sql("SELECT source_file FROM protocol").query(String.class).list();
        assertThat(paths).hasSize(165).allSatisfy(path -> {
            // Stored with forward slashes so the value does not depend on the OS that seeded it.
            assertThat(path).doesNotContain("\\");
            assertThat(filesDir.resolve(path)).exists();
        });
    }

    // ---------------------------------------------------------------------------------------
    // Idempotence — the property the runbook relied on
    // ---------------------------------------------------------------------------------------

    @Test
    void aSecondRunInsertsNothingAndReportsEverythingAlreadyPresent() throws IOException {
        int before = protocolCount();
        long documentsBefore = documentsOnDisk();

        runner.run(new DefaultApplicationArguments());

        assertThat(protocolCount())
                .as("fixed UUIDs and ON CONFLICT DO NOTHING are what make re-seeding a no-op")
                .isEqualTo(before);
        assertThat(documentsOnDisk()).isEqualTo(documentsBefore);
    }

    @Test
    void aSecondRunDoesNotOverwriteADocumentThatWasEditedOnTheVolume() throws IOException {
        String relative = jdbc.sql("SELECT source_file FROM protocol ORDER BY id LIMIT 1")
                .query(String.class).single();
        Path document = filesDir.resolve(relative);
        Files.writeString(document, "EDITED BY HAND");

        runner.run(new DefaultApplicationArguments());

        // Documents are written only when MISSING. Re-seeding an environment must not silently
        // revert a file somebody changed on the volume — that would be a data loss disguised as a
        // no-op, on a live demo machine.
        assertThat(Files.readString(document)).isEqualTo("EDITED BY HAND");
    }

    @Test
    void aSecondRunDoesNotRevertAnApprovalDecisionMadeSinceTheFirst() throws IOException {
        UUID id = jdbc.sql("SELECT id FROM protocol WHERE approval_state = 'UNAPPROVED' ORDER BY id LIMIT 1")
                .query(UUID.class).single();
        // approved_by and approved_at move together: ck_protocol_approval_actor holds the two
        // columns and the state in step, so an approval cannot be recorded without its timestamp.
        jdbc.sql("UPDATE protocol SET approval_state = 'APPROVED', approved_by = 'schichtleiter', "
                + "approved_at = now() WHERE id = :id").param("id", id).update();

        runner.run(new DefaultApplicationArguments());

        // The strongest form of the idempotence claim: a moderator's decision outlives a re-seed.
        // ON CONFLICT DO NOTHING means the row is left entirely alone, not refreshed from the file.
        assertThat(jdbc.sql("SELECT approved_by FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).single())
                .isEqualTo("schichtleiter");

        jdbc.sql("UPDATE protocol SET approval_state = 'UNAPPROVED', approved_by = NULL, "
                + "approved_at = NULL WHERE id = :id").param("id", id).update();
    }

    // ---------------------------------------------------------------------------------------
    // The corpus file itself, read through a second runner built by hand
    // ---------------------------------------------------------------------------------------
    //
    // Constructed here rather than given a Spring context of its own: both cases need a DIFFERENT
    // corpus resource, and a @SpringBootTest property override for each would buy two more
    // container startups to test two lines of file handling. The autowired JdbcClient is the real
    // one, so these still run against the real database.

    private CorpusSeedRunner runnerReading(String resource) {
        return new CorpusSeedRunner(jdbc, new DefaultResourceLoader(),
                new CorpusSeedProperties(true, resource),
                new FileStorageProperties(filesDir.toString()));
    }

    @Test
    void aMissingCorpusFailsLoudlyRatherThanSeedingNothingQuietly() {
        // Naming the location it looked in: the one way this goes wrong in practice is a resource
        // path that is right on a developer's machine and wrong in the container, and a runner that
        // shrugged would leave an empty demo that looks like a database problem.
        assertThatThrownBy(() -> runnerReading("classpath:corpus/does-not-exist.ndjson")
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corpus not found")
                .hasMessageContaining("does-not-exist.ndjson");
    }

    @Test
    void blankLinesInTheCorpusAreSkippedRatherThanTreatedAsRecords() throws IOException {
        int before = protocolCount();

        // The fixture holds two records the corpus already contains, separated by an empty line and
        // a whitespace-only one. NDJSON is hand-edited, and a stray blank line at the end of a file
        // is the most ordinary edit there is; parsing one as a record would fail the whole seed and
        // name a line number rather than the real problem.
        runnerReading("classpath:corpus/with-blank-lines.ndjson")
                .run(new DefaultApplicationArguments());

        assertThat(protocolCount())
                .as("the two records are already present, so a correct read changes nothing")
                .isEqualTo(before);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SeedEnabledConfig {

        @Bean
        DynamicPropertyRegistrar corpusSeedFilesPath() {
            // A registrar rather than an inlined property because the value is only known once
            // JUnit has created the temporary directory.
            return registry -> registry.add("maintenance.files.base-path", () -> filesDir.toString());
        }
    }
}
