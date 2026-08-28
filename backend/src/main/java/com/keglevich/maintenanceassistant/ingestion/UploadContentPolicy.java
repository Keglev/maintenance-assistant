package com.keglevich.maintenanceassistant.ingestion;

import java.util.Locale;

/**
 * What may be uploaded at all, decided before anything is stored.
 *
 * <p>Every rejection here happens <b>before</b> a row exists and before a byte reaches the volume,
 * which is the property worth stating: a refused upload leaves nothing behind to clean up, so the
 * moderation work package only ever has to deal with content that was accepted on purpose.
 *
 * <p>Rejections carry a stable {@code code} rather than only a sentence. The frontend translates the
 * code; matching on English prose from another layer breaks the first time someone rewords it — the
 * same reasoning as the query path's {@code reason} field.
 */
public final class UploadContentPolicy {

    /**
     * How far into the file to look for a NUL byte.
     *
     * <p>A NUL in the first 8 KB is a sufficient binary test for this application and no detection
     * library is worth adding for it: real UTF-8 text never contains one, and every format actually
     * being guarded against — PDF, ZIP, images, Office documents — has them within the first few
     * hundred bytes. Anything that slips past this still has to survive strict UTF-8 decoding
     * immediately afterwards, which is the real filter; this one exists to give a clear "that is not
     * a text file" instead of a decoder complaint about a malformed byte sequence.
     */
    private static final int BINARY_SNIFF_BYTES = 8192;

    private UploadContentPolicy() {
    }

    /** A rejected upload, with a code the frontend can translate. */
    public static class RejectedUploadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String code;

        RejectedUploadException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Checks the submitted bytes and filename.
     *
     * @param filename the client-supplied name, which is untrusted and used only for its extension
     * @param bytes    the raw upload
     * @throws RejectedUploadException with a stable code, if this is not something to store
     */
    public static void verify(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new RejectedUploadException("EMPTY_FILE", "the uploaded file is empty");
        }
        if (!hasAcceptedExtension(filename)) {
            throw new RejectedUploadException("UNSUPPORTED_TYPE",
                    "only .txt files are accepted; PDF and scan extraction is not implemented yet");
        }
        if (looksBinary(bytes)) {
            throw new RejectedUploadException("NOT_TEXT",
                    "the uploaded file is not text; PDF and scan extraction is not implemented yet");
        }
    }

    /**
     * {@code .txt} only, and {@code .md} is no longer accepted.
     *
     * <p>Markdown was a developer convenience from the days when the only way to get a protocol into
     * the system was to write a file. Typed entry replaced that: a Schichtleiter now writes prose in
     * the form and the browser wraps it as {@code .txt}, so the extension no longer has to
     * accommodate whatever a developer had lying around. Narrowing it costs nothing real — the
     * pipeline treats both identically as plain text — and it removes one way for a file nobody
     * intended to reach the corpus. A missing extension is refused for the same reason: it is
     * indistinguishable from a renamed binary at this point.
     */
    private static boolean hasAcceptedExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        return filename.toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
