package com.keglevich.maintenanceassistant.query;

/**
 * Model output made safe to put on one log line: escaped, and never more than a fixed length.
 *
 * <p>Two diagnoses log what the model said, the tail of a truncated answer ({@link TruncatedBody})
 * and the head of a grounded answer that produced no valid citation ({@link QueryService}). Both
 * need the same two guarantees, so they live here once: newlines are escaped rather than stripped,
 * because a log line that wraps is a log line nobody greps, and only {@link #MAX} characters are
 * kept, because a log line is not a place to put a user's document.
 */
final class LogText {

    /** The most characters of model output a single log line carries. */
    static final int MAX = 200;

    private LogText() {
    }

    /**
     * The first {@link #MAX} characters of {@code text}, on one line.
     *
     * @param text model output; {@code null} is logged as the empty string
     * @return the escaped head
     */
    static String head(String text) {
        String body = text == null ? "" : text;
        return oneLine(body.length() <= MAX ? body : body.substring(0, MAX));
    }

    /**
     * The last {@link #MAX} characters of {@code text}, on one line.
     *
     * @param text model output; {@code null} is logged as the empty string
     * @return the escaped tail
     */
    static String tail(String text) {
        String body = text == null ? "" : text;
        return oneLine(body.length() <= MAX ? body : body.substring(body.length() - MAX));
    }

    /** Backslash first, so the escapes added afterwards are not escaped a second time. */
    private static String oneLine(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
