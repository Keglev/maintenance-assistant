package com.keglevich.maintenanceassistant.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example questions are content, and this is what stops the content going stale silently.
 *
 * <p><b>The assertion that matters is the `source` one.</b> Every example was written against a
 * protocol that exists, and its only job is to reach one — so when a protocol leaves the corpus,
 * the example that cited it stops working. Without this test that is discovered by a reader
 * clicking a chip and getting the ungrounded card, which is the exact failure ADR-011 exists to
 * prevent. With it, the build says so.
 *
 * <p>WHAT THIS TEST CANNOT DO: prove that a question actually retrieves Mode A. That is a
 * measurement against a live embedding model and a real index, taken once when the file was
 * authored (72 of 72, recorded in the pull request) and re-taken by the frontend suite's e2e case.
 * A unit test asserting it would need a provider key and would spend money on every build.
 *
 * <p>No Spring context: this reads two classpath resources and compares them.
 */
class ExampleQuestionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode examples() throws IOException {
        try (InputStream in = resource("examples/example-questions.json")) {
            return JSON.readTree(in);
        }
    }

    private static InputStream resource(String path) {
        InputStream in = ExampleQuestionsTest.class.getClassLoader().getResourceAsStream(path);
        assertThat(in).as("classpath resource %s", path).isNotNull();
        return in;
    }

    /** Every protocol id in the seed corpus, which is the set an example may cite. */
    private static Set<String> corpusProtocolIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource("corpus/protocols.ndjson"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    ids.add(JSON.readTree(line).get("id").asText());
                }
            }
        }
        return ids;
    }

    @Test
    @DisplayName("every example cites a protocol that is still in the corpus")
    void everySourceExists() throws IOException {
        Set<String> corpus = corpusProtocolIds();
        assertThat(corpus).as("the seed corpus is readable").isNotEmpty();

        JsonNode machines = examples().get("machines");
        Map<String, String> orphaned = new TreeMap<>();
        machines.fields().forEachRemaining(machine ->
                List.of("de", "en").forEach(language ->
                        machine.getValue().get(language).forEach(example -> {
                            String source = example.get("source").asText();
                            if (!corpus.contains(source)) {
                                orphaned.put(example.get("question").asText(),
                                        machine.getKey() + "/" + language + " -> " + source);
                            }
                        })));

        assertThat(orphaned)
                .as("an example whose protocol has left the corpus will land on the ungrounded "
                        + "card — rewrite it against a protocol that exists, or remove it")
                .isEmpty();
    }

    @Test
    @DisplayName("every machine offers three to four questions in each language")
    void everyMachineIsUsablyStocked() throws IOException {
        JsonNode machines = examples().get("machines");
        assertThat(machines).isNotNull();
        assertThat(machines.size()).as("machines with examples").isGreaterThanOrEqualTo(10);

        machines.fields().forEachRemaining(machine -> List.of("de", "en").forEach(language -> {
            // Three is the floor because two chips read as an accident and one reads as a
            // suggestion; four is the ceiling because a row of chips is a hint, not a menu.
            assertThat(machine.getValue().get(language).size())
                    .as("%s / %s", machine.getKey(), language)
                    .isBetween(3, 4);
        }));
    }

    @Test
    @DisplayName("no question is blank, and none is duplicated within a machine")
    void theQuestionsAreDistinctAndReal() throws IOException {
        JsonNode machines = examples().get("machines");
        machines.fields().forEachRemaining(machine -> List.of("de", "en").forEach(language -> {
            Set<String> seen = new HashSet<>();
            machine.getValue().get(language).forEach(example -> {
                String question = example.get("question").asText();
                assertThat(question.isBlank()).as("%s / %s has a blank question", machine.getKey(), language).isFalse();
                assertThat(seen.add(question))
                        .as("%s / %s repeats \"%s\"", machine.getKey(), language, question)
                        .isTrue();
            });
        }));
    }

    @Test
    @DisplayName("the file declares its version, so a shape change is detectable rather than guessed")
    void theFileIsVersioned() throws IOException {
        assertThat(examples().get("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the golden set still says it is the measurement set, not this one")
    void theGoldenSetKeepsItsOwnJob() throws IOException {
        // ADR-011 named the duplication rather than pretending it away, and the note in the golden
        // file is where an editor finds out which of the two they are editing. A note that can be
        // deleted without a test noticing is a note that will be.
        try (InputStream in = resource("retrieval/golden-questions.json")) {
            String about = JSON.readTree(in).get("_about").toString();
            assertThat(about)
                    .contains("example-questions.json")
                    .contains("CANONICAL FOR MEASUREMENT");
        }
    }
}
