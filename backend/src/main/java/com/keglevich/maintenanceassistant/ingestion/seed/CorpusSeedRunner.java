package com.keglevich.maintenanceassistant.ingestion.seed;

import com.keglevich.maintenanceassistant.ingestion.FileStorageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;

/**
 * Loads the synthetic protocol corpus into the database and onto the file volume.
 *
 * <h2>Why an application runner rather than a migration or a script</h2>
 *
 * <p><b>Not a Flyway migration.</b> The corpus is demo data, not schema. It also has to write ~150
 * documents to a volume, which SQL cannot do — so a migration could only ever do half the job.
 * Baking 150 records of German prose into the versioned schema history would also make that history
 * unreadable and would tie a corpus revision to a schema version.
 *
 * <p><b>Not a standalone script.</b> A script needs its own copy of the database credentials and
 * its own idea of where the volume is mounted. Running inside the application reuses the configured
 * {@code DataSource} and the same properties the application itself uses, so seeding the deployed
 * stack is one environment variable and a restart rather than a second configuration surface.
 *
 * <p>It is off by default ({@code maintenance.corpus-seed.enabled}) because seeding should be an
 * explicit act, and it is idempotent: rows are inserted with fixed UUIDs and {@code ON CONFLICT DO
 * NOTHING}, documents are written only when missing. Running it twice changes nothing.
 *
 * <p>Deliberately absent: chunking, embedding and any call to the LLM provider. Protocols land with
 * status {@code RECEIVED}, which is exactly the state the ingestion module will pick them up from.
 */
@Component
@ConditionalOnProperty(prefix = "maintenance.corpus-seed", name = "enabled", havingValue = "true")
class CorpusSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusSeedRunner.class);

    private static final String INSERT = """
            INSERT INTO protocol (
                id, machine_id, incident_date, protocol_type, error_code, title,
                symptom, cause, action, parts_used, downtime_minutes,
                technician_initials, language, source_file, status, uploaded_by)
            VALUES (
                :id, :machineId, :incidentDate, :protocolType, :errorCode, :title,
                :symptom, :cause, :action, :partsUsed, :downtimeMinutes,
                :technicianInitials, :language, :sourceFile, 'RECEIVED', :uploadedBy)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final ResourceLoader resourceLoader;
    private final CorpusSeedProperties seedProperties;
    private final FileStorageProperties fileProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CorpusSeedRunner(JdbcClient jdbc, ResourceLoader resourceLoader,
                     CorpusSeedProperties seedProperties, FileStorageProperties fileProperties) {
        this.jdbc = jdbc;
        this.resourceLoader = resourceLoader;
        this.seedProperties = seedProperties;
        this.fileProperties = fileProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Resource resource = resourceLoader.getResource(seedProperties.resource());
        if (!resource.exists()) {
            throw new IllegalStateException("Corpus not found: " + seedProperties.resource());
        }
        Path base = Path.of(fileProperties.basePath()).toAbsolutePath().normalize();
        Files.createDirectories(base);

        log.info("Seeding corpus from {} into {}", seedProperties.resource(), base);

        int read = 0;
        int inserted = 0;
        int filesWritten = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                CorpusProtocol protocol;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    protocol = CorpusProtocol.from(node);
                } catch (RuntimeException | IOException e) {
                    // Line-oriented input earns a line-oriented error: the point of NDJSON here is
                    // that a bad record can be named rather than just failing the whole file.
                    throw new IllegalStateException(
                            "Invalid corpus record at line " + lineNumber + ": " + e.getMessage(), e);
                }
                read++;

                Path document = documentPath(base, protocol);
                if (writeDocument(document, protocol)) {
                    filesWritten++;
                }
                inserted += insert(protocol, base.relativize(document));
            }
        }

        log.info("Corpus seed finished: {} records read, {} rows inserted, {} already present, "
                        + "{} documents written, {} already on disk",
                read, inserted, read - inserted, filesWritten, read - filesWritten);
        logDistribution();
    }

    /**
     * One directory per machine. Grouping by machine keeps the volume browsable during a demo, and
     * the file name is the protocol UUID so the path is stable no matter how a title is later
     * reworded.
     */
    private static Path documentPath(Path base, CorpusProtocol protocol) {
        return base.resolve(protocol.machineNo()).resolve(protocol.id() + ".txt");
    }

    /** @return true if the document was written, false if it was already there */
    private static boolean writeDocument(Path path, CorpusProtocol protocol) {
        if (Files.exists(path)) {
            return false;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, ProtocolDocumentRenderer.render(protocol), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write protocol document " + path, e);
        }
    }

    /** @return 1 if the row was inserted, 0 if it was already present */
    private int insert(CorpusProtocol p, Path relativePath) {
        return jdbc.sql(INSERT)
                .param("id", p.id())
                .param("machineId", p.machineId())
                .param("incidentDate", p.incidentDate())
                .param("protocolType", p.protocolType())
                .param("errorCode", p.errorCode(), Types.VARCHAR)
                .param("title", p.title())
                .param("symptom", p.symptom(), Types.VARCHAR)
                .param("cause", p.cause(), Types.VARCHAR)
                .param("action", p.action(), Types.VARCHAR)
                .param("partsUsed", p.partsUsed(), Types.VARCHAR)
                .param("downtimeMinutes", p.downtimeMinutes(), Types.INTEGER)
                .param("technicianInitials", p.technicianInitials(), Types.VARCHAR)
                .param("language", p.language())
                // Stored with forward slashes so the value does not depend on the OS that seeded it.
                .param("sourceFile", relativePath.toString().replace('\\', '/'))
                .param("uploadedBy", p.uploadedBy())
                .update();
    }

    /** Logged so a seeded environment can state its own distribution without a psql session. */
    private void logDistribution() {
        jdbc.sql("""
                        SELECT language, protocol_type, count(*) AS n
                        FROM protocol GROUP BY language, protocol_type ORDER BY 1, 2
                        """)
                .query()
                .listOfRows()
                .forEach(row -> log.info("  {} {}: {}",
                        row.get("language"), row.get("protocol_type"), row.get("n")));
    }
}
