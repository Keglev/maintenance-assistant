package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ingestion pipeline for one protocol: read the document, chunk it, embed the chunks, write
 * them, and move the status from {@code RECEIVED} to {@code INDEXED} — or to {@code FAILED} with a
 * reason.
 *
 * <p>Nothing here is transactional across the embedding call: see {@link ProtocolIndexWriter}.
 *
 * <p>Failure keeps the row and the file. A FAILED protocol is a retry worklist entry, not a dead
 * one, which is why the reason is stored rather than only logged — the next run should be able to
 * tell a provider outage from a corrupt file without reproducing the failure and paying for it
 * again.
 */
@Component
class ProtocolIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProtocolIndexer.class);

    private final JdbcClient jdbc;
    private final ProtocolChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingBudget budget;
    private final ProtocolIndexWriter writer;
    private final FileStorageProperties fileProperties;
    private final EmbeddingProperties embeddingProperties;

    ProtocolIndexer(JdbcClient jdbc, ProtocolChunker chunker, EmbeddingClient embeddingClient,
                    EmbeddingBudget budget, ProtocolIndexWriter writer,
                    FileStorageProperties fileProperties, EmbeddingProperties embeddingProperties) {
        this.jdbc = jdbc;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.budget = budget;
        this.writer = writer;
        this.fileProperties = fileProperties;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * Indexes one protocol. Never throws: a failure is a recorded state, not an exception the
     * caller has to handle, because the callers are an async event listener and a bulk backlog run
     * and neither can do anything useful with it.
     *
     * @return true if the protocol reached INDEXED
     */
    boolean index(UUID protocolId) {
        ProtocolRow protocol;
        try {
            protocol = load(protocolId).orElse(null);
        } catch (RuntimeException e) {
            log.error("Cannot load protocol {}", protocolId, e);
            return false;
        }
        if (protocol == null) {
            log.warn("Protocol {} disappeared before indexing", protocolId);
            return false;
        }

        try {
            String text = readDocument(protocol);
            List<String> chunks = chunker.chunk(text, contextLine(protocol));
            if (chunks.isEmpty()) {
                throw new IllegalStateException("document produced no chunks — is the file empty?");
            }

            // Reserved for the whole protocol before any of it is sent, so a protocol is never
            // left half-embedded by the budget running out between batches.
            int estimatedCalls = (chunks.size() + embeddingProperties.batchSize() - 1)
                    / embeddingProperties.batchSize();
            budget.checkHeadroom(estimatedCalls);

            // The client records its own usage as each request is served — see EmbeddingClient.
            EmbeddingClient.EmbeddingBatch embedded = embeddingClient.embed(chunks);

            if (embedded.vectors().size() != chunks.size()) {
                throw new IllegalStateException("provider returned %d vectors for %d chunks"
                        .formatted(embedded.vectors().size(), chunks.size()));
            }

            writer.writeChunks(protocol.id(), protocol.machineId(), protocol.errorCode(),
                    protocol.language(), chunks, embedded.vectors());

            log.info("Indexed {} ({}): {} chunks, {} provider calls, {} tokens",
                    protocol.machineNo(), protocolId, chunks.size(),
                    embedded.providerCalls(), embedded.promptTokens());
            return true;

        } catch (Exception e) {
            String reason = "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage());
            log.warn("Indexing failed for {} ({}): {}", protocol.machineNo(), protocolId, reason);
            writer.markFailed(protocolId, reason);
            return false;
        }
    }

    /**
     * The context prepended to every chunk. Machine, error code and title, so a retrieved chunk
     * still says what it is about once it is separated from its row — see {@link ProtocolChunker}.
     */
    private static String contextLine(ProtocolRow p) {
        StringBuilder out = new StringBuilder(p.machineNo());
        if (p.errorCode() != null && !p.errorCode().isBlank()) {
            out.append(" · ").append(p.errorCode().strip());
        }
        return out.append(" · ").append(p.title()).toString();
    }

    /**
     * Reads the document from the volume as UTF-8.
     *
     * <p>Text only. A PDF or a scan reaches this point as a decoding error, which is a correct
     * failure rather than a silent one: extraction from those formats is a documented later-phase
     * item, and until it exists, accepting the upload and storing mojibake would be worse than
     * refusing it.
     */
    private String readDocument(ProtocolRow protocol) throws IOException {
        if (protocol.sourceFile() == null || protocol.sourceFile().isBlank()) {
            throw new IllegalStateException("protocol has no source_file");
        }
        Path path = Path.of(fileProperties.basePath()).resolve(protocol.sourceFile()).normalize();
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("source file missing or unreadable: " + protocol.sourceFile());
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IllegalStateException("source file is empty: " + protocol.sourceFile());
            }
            return text;
        } catch (MalformedInputException e) {
            throw new IllegalStateException(
                    "source file is not UTF-8 text (PDF and scan extraction is not implemented): "
                            + protocol.sourceFile(), e);
        }
    }

    private Optional<ProtocolRow> load(UUID protocolId) {
        return jdbc.sql("""
                        SELECT p.id, p.machine_id, m.machine_no, p.error_code, p.title,
                               p.language, p.source_file
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.id = :id
                        """)
                .param("id", protocolId)
                .query((rs, rowNum) -> new ProtocolRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("machine_id", UUID.class),
                        rs.getString("machine_no"),
                        rs.getString("error_code"),
                        rs.getString("title"),
                        rs.getString("language"),
                        rs.getString("source_file")))
                .optional();
    }

    private record ProtocolRow(UUID id, UUID machineId, String machineNo, String errorCode,
                               String title, String language, String sourceFile) {
    }
}
