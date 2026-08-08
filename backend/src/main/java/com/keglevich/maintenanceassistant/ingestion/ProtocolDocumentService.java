package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading a protocol's original document back off the volume.
 *
 * <p>In the ingestion module because that module owns the volume: it decides the path layout, it
 * wrote the file, and {@link FileStorageProperties} is its property. The query module cites a
 * protocol; this is what turns that citation into something a person can open and check, which is
 * the point of citing at all.
 *
 * <p>Read access is not write access. Upload stays Schichtleiter-only for the quality reasons in
 * DECISIONS.txt, and every role that may ask a question may read the sources behind the answer —
 * an answer whose evidence its reader cannot open is an answer they have to take on trust, which is
 * exactly what Mode A exists to avoid.
 */
@Service
public class ProtocolDocumentService {

    private final JdbcClient jdbc;
    private final FileStorageProperties properties;

    ProtocolDocumentService(JdbcClient jdbc, FileStorageProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * The stored document for a protocol.
     *
     * <p>Empty rather than an exception for every "not there" case — unknown id, a row whose
     * {@code source_file} is null, a path that no longer resolves to a readable file. They are one
     * outcome for the caller (404) and telling them apart in the response would describe the
     * database to whoever asked.
     */
    public Optional<ProtocolDocument> find(UUID protocolId) {
        return jdbc.sql("""
                        SELECT p.title, p.source_file, p.language, m.machine_no
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.id = :id
                        """)
                .param("id", protocolId)
                .query((rs, rowNum) -> new StoredProtocol(
                        rs.getString("title"), rs.getString("source_file"),
                        rs.getString("language"), rs.getString("machine_no")))
                .optional()
                .flatMap(stored -> resolve(protocolId, stored));
    }

    private Optional<ProtocolDocument> resolve(UUID protocolId, StoredProtocol stored) {
        if (stored.sourceFile() == null || stored.sourceFile().isBlank()) {
            return Optional.empty();
        }
        Path base = Path.of(properties.basePath()).toAbsolutePath().normalize();
        Path path = base.resolve(stored.sourceFile()).normalize();

        // The path comes from our own database and is still checked. `source_file` is a column, and
        // a column is only ever as trustworthy as everything that has written to it since the
        // schema was created — including a future import, a manual fix, or a restored dump. The
        // check costs one comparison and removes the whole class of question.
        if (!path.startsWith(base) || !Files.isReadable(path) || Files.isDirectory(path)) {
            return Optional.empty();
        }

        return Optional.of(new ProtocolDocument(
                new FileSystemResource(path),
                downloadName(protocolId, stored),
                contentType(path),
                sizeOf(path)));
    }

    /**
     * What the file is called when it is saved rather than viewed.
     *
     * <p>Built from the machine and the title instead of the stored name, which is a UUID: a
     * technician who saves three sources ends up with three files they can tell apart. Reduced to
     * ASCII and to characters every filesystem accepts, because the corpus is German and a
     * {@code Content-Disposition} filename crossing an umlaut is a well-known source of mojibake.
     */
    private static String downloadName(UUID protocolId, StoredProtocol stored) {
        String base = "%s-%s".formatted(
                stored.machineNo() == null ? "protokoll" : stored.machineNo(),
                stored.title() == null ? protocolId.toString() : stored.title());
        String ascii = java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD)
                .replace("ß", "ss")
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        String safe = ascii.isBlank() ? protocolId.toString() : ascii;
        return (safe.length() > 80 ? safe.substring(0, 80) : safe) + extensionOf(stored.sourceFile());
    }

    private static String extensionOf(String sourceFile) {
        int dot = sourceFile.lastIndexOf('.');
        int slash = Math.max(sourceFile.lastIndexOf('/'), sourceFile.lastIndexOf('\\'));
        return dot > slash && dot >= 0 ? sourceFile.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Probed from the filesystem first, then decided by extension.
     *
     * <p>{@code probeContentType} is platform-dependent and answers null often enough that relying
     * on it alone would serve text as a download on one host and inline on another. Everything the
     * ingestion pipeline accepts today is UTF-8 text — a PDF fails the strict decode on upload — so
     * the fallback is the truth rather than a guess, and the charset is stated so a browser does not
     * have to sniff German umlauts.
     */
    private static String contentType(Path path) {
        String probed = probe(path);
        if (probed != null && !probed.equals("application/octet-stream")) {
            return probed.startsWith("text/") && !probed.contains("charset")
                    ? probed + ";charset=UTF-8"
                    : probed;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".md")) {
            return "text/markdown;charset=UTF-8";
        }
        return "text/plain;charset=UTF-8";
    }

    private static String probe(Path path) {
        try {
            return Files.probeContentType(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            // A length header is a courtesy; the stream is the answer.
            return -1L;
        }
    }

    /**
     * @param resource     the file on the volume, streamed rather than read into memory
     * @param downloadName suggested filename, ASCII-safe
     * @param contentType  what it is, including the charset for text
     * @param sizeBytes    file size, or -1 when it could not be read
     */
    public record ProtocolDocument(Resource resource, String downloadName, String contentType, long sizeBytes) {
    }

    private record StoredProtocol(String title, String sourceFile, String language, String machineNo) {
    }
}
