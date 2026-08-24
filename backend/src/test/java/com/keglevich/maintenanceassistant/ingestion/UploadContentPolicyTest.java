package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.ingestion.UploadContentPolicy.RejectedUploadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is allowed to become a protocol, decided before anything is stored or paid for.
 *
 * <p>This is the gate in front of the whole ingestion pipeline, and it runs on untrusted input: the
 * bytes and the filename both come from a browser. Everything it refuses would otherwise be
 * embedded, chunked and indexed — a binary accepted here becomes paid provider calls and a corpus
 * row nobody can read.
 *
 * <p>MACHINE CODES ARE THE CONTRACT ({@code EMPTY_FILE}, {@code UNSUPPORTED_TYPE},
 * {@code NOT_TEXT}). The frontend switches on them to choose a message, so every refusal asserts the
 * code and not only the type — a test pinning the English sentence would break on a wording fix and
 * pass on a code change, which is exactly backwards.
 *
 * <p>Tested as a unit: the policy is a static function of its two arguments, and
 * ProtocolUploadGuardsIT already owns the HTTP envelope these codes travel in.
 *
 * <p>SIBLING: ProtocolUploadGuardsIT, which asserts the same codes as upload responses.
 */
class UploadContentPolicyTest {

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void verify_aPlainTextProtocol_isAccepted() {
        assertThatCode(() -> UploadContentPolicy.verify(
                "protokoll.txt", text("Symptom: Presse kommt nicht auf Druck.\nUrsache: E-47.")))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------
    // EMPTY_FILE
    // ---------------------------------------------------------------------------------------

    @Test
    void verify_noBytesAtAll_isRefusedAsAnEmptyFile() {
        // Null rather than empty: the two arrive by different routes — a form field with no file
        // chosen against a file that is genuinely zero bytes — and both have to stop here rather
        // than at a NullPointerException further down the pipeline.
        assertThatThrownBy(() -> UploadContentPolicy.verify("protokoll.txt", null))
                .isInstanceOf(RejectedUploadException.class)
                .extracting(thrown -> ((RejectedUploadException) thrown).code())
                .isEqualTo("EMPTY_FILE");
    }

    @Test
    void verify_zeroBytes_isRefusedAsAnEmptyFile() {
        assertThatThrownBy(() -> UploadContentPolicy.verify("protokoll.txt", new byte[0]))
                .isInstanceOf(RejectedUploadException.class)
                .extracting(thrown -> ((RejectedUploadException) thrown).code())
                .isEqualTo("EMPTY_FILE");
    }

    @Test
    void verify_emptinessIsCheckedBeforeTheExtension() {
        // Order matters to the reader: an empty file with a bad name is empty first. Telling a
        // Schichtleiter to change the file type when they attached nothing sends them after the
        // wrong fix.
        assertThatThrownBy(() -> UploadContentPolicy.verify("scan.pdf", new byte[0]))
                .isInstanceOf(RejectedUploadException.class)
                .extracting(thrown -> ((RejectedUploadException) thrown).code())
                .isEqualTo("EMPTY_FILE");
    }

    // ---------------------------------------------------------------------------------------
    // UNSUPPORTED_TYPE
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "filename={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "protokoll", "scan.pdf", "notiz.md", "protokoll.txt.exe"})
    void verify_anythingThatIsNotATxtFile_isRefusedAsAnUnsupportedType(String filename) {
        // A MISSING name is refused for the same reason as a wrong one: at this point it is
        // indistinguishable from a renamed binary. ".md" is here because it was once accepted —
        // narrowing it removed one way for a file nobody intended to reach the corpus.
        assertThatThrownBy(() -> UploadContentPolicy.verify(filename, text("Symptom: Druckabfall.")))
                .isInstanceOf(RejectedUploadException.class)
                .extracting(thrown -> ((RejectedUploadException) thrown).code())
                .isEqualTo("UNSUPPORTED_TYPE");
    }

    @Test
    void verify_anUppercaseExtension_isAccepted() {
        // The extension is the client's, and a tablet that capitalises it has not sent a different
        // kind of file.
        assertThatCode(() -> UploadContentPolicy.verify("PROTOKOLL.TXT", text("Symptom: Druckabfall.")))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------
    // NOT_TEXT
    // ---------------------------------------------------------------------------------------

    @Test
    void verify_bytesCarryingANullByte_areRefusedAsNotText() {
        byte[] renamedBinary = new byte[]{'P', 'K', 3, 4, 0, 'x', 'y'};

        // The extension says .txt and the content says otherwise. Sniffing the bytes is what stops
        // a renamed archive becoming a protocol.
        assertThatThrownBy(() -> UploadContentPolicy.verify("protokoll.txt", renamedBinary))
                .isInstanceOf(RejectedUploadException.class)
                .extracting(thrown -> ((RejectedUploadException) thrown).code())
                .isEqualTo("NOT_TEXT");
    }

    @Test
    void verify_germanUmlautsAndSharpS_areNotMistakenForBinary() {
        // The corpus is German, so the sniff has to survive multi-byte UTF-8. A check that flagged
        // any high byte would refuse most real protocols.
        assertThatCode(() -> UploadContentPolicy.verify(
                "protokoll.txt", text("Prüfung der Meßwerte: Größe, Dichtung schadhaft. Straße 5.")))
                .doesNotThrowAnyException();
    }

    @Test
    void verify_refusalMessagesSayWhatIsMissingRatherThanJustRefusing() {
        // The sentence is not the contract, but a Schichtleiter reads it: naming the unimplemented
        // feature is what stops "unsupported" being read as "broken".
        assertThatThrownBy(() -> UploadContentPolicy.verify("scan.pdf", text("egal")))
                .hasMessageContaining("only .txt files are accepted")
                .hasMessageContaining("not implemented yet");
    }
}
