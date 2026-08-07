package com.keglevich.maintenanceassistant.ingestion.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the corpus file itself, without a database.
 *
 * <p>The corpus is data a human edits by hand, and its constraints are not all expressible in the
 * schema: the machine UUIDs must match {@code R__seed_machines.sql}, the protocol UUIDs must stay
 * unique because the seed's idempotence depends on them, and the demo tags must still be there
 * because scripted demo queries rely on those specific protocols. A wrong edit here fails at seed
 * time on someone's laptop otherwise, which is late.
 *
 * <p>Deliberately not asserted: the exact distribution percentages. They are documented in
 * DOMAIN-MODEL.md and are a judgement call, not an invariant — pinning them here would turn every
 * future corpus addition into a test edit for no benefit.
 */
class CorpusIntegrityTest {

    private static final String CORPUS = "/corpus/protocols.ndjson";

    /** The ten machine UUIDs from R__seed_machines.sql. */
    private static final Set<String> MACHINE_IDS = Set.of(
            "0f9c5b01-0000-4000-8000-000000000001", "0f9c5b01-0000-4000-8000-000000000002",
            "0f9c5b01-0000-4000-8000-000000000003", "0f9c5b01-0000-4000-8000-000000000004",
            "0f9c5b01-0000-4000-8000-000000000005", "0f9c5b01-0000-4000-8000-000000000006",
            "0f9c5b01-0000-4000-8000-000000000007", "0f9c5b01-0000-4000-8000-000000000008",
            "0f9c5b01-0000-4000-8000-000000000009", "0f9c5b01-0000-4000-8000-000000000010");

    private static List<JsonNode> records;

    @BeforeAll
    static void readCorpus() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        records = new ArrayList<>();
        try (InputStream in = CorpusIntegrityTest.class.getResourceAsStream(CORPUS)) {
            assertThat(in).as("corpus resource %s must be on the classpath", CORPUS).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    records.add(mapper.readTree(line));
                }
            }
        }
    }

    @Test
    void everyRecordParsesIntoAProtocol() {
        assertThat(records).hasSizeGreaterThan(100);
        assertThat(records).allSatisfy(node -> assertThat(CorpusProtocol.from(node)).isNotNull());
    }

    @Test
    void protocolIdsAreUnique() {
        Set<String> ids = records.stream().map(n -> n.get("id").asText()).collect(Collectors.toSet());
        assertThat(ids)
                .as("fixed UUIDs are what make the seed idempotent; a duplicate silently drops a protocol")
                .hasSameSizeAs(records);
    }

    @Test
    void everyProtocolPointsAtASeededMachine() {
        Set<String> referenced = new HashSet<>();
        records.forEach(n -> referenced.add(n.get("machine_id").asText()));
        assertThat(MACHINE_IDS).containsAll(referenced);
    }

    @Test
    void enumValuesMatchTheSchemaConstraints() {
        assertThat(records).allSatisfy(node -> {
            assertThat(node.get("protocol_type").asText()).isIn("STOERUNG", "WARTUNG");
            assertThat(node.get("language").asText()).isIn("de", "en");
        });
    }

    @Test
    void everyFaultProtocolCarriesExactlyOneRootCauseClass() {
        records.stream()
                .filter(n -> "STOERUNG".equals(n.get("protocol_type").asText()))
                .forEach(n -> assertThat(n.get("meta").get("cause_class").asText())
                        .as("protocol %s", n.get("id").asText())
                        .isIn("MATERIAL", "BEDIENUNG", "TECHNIK"));
    }

    @Test
    void plannedMaintenanceHasNoRootCauseClass() {
        records.stream()
                .filter(n -> "WARTUNG".equals(n.get("protocol_type").asText()))
                .forEach(n -> assertThat(n.get("meta").get("cause_class").isNull())
                        .as("protocol %s is planned work and has no fault to classify", n.get("id").asText())
                        .isTrue());
    }

    @Test
    void theDemoProtocolsAreStillPresent() {
        Set<String> demoTags = records.stream()
                .map(n -> n.get("meta").get("demo"))
                .filter(tag -> tag != null && !tag.isNull())
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
        assertThat(demoTags)
                .as("scripted demo queries depend on these specific protocols")
                .contains("e47-1", "e47-2", "e47-3", "cross-language-de-query");
    }

    @Test
    void theThreeE47ProtocolsShareACodeAndDifferInRootCause() {
        List<JsonNode> e47 = records.stream()
                .filter(n -> "E-47".equals(n.get("error_code").asText(null)))
                .filter(n -> n.get("meta").get("demo") != null && !n.get("meta").get("demo").isNull())
                .toList();
        assertThat(e47).hasSize(3);
        assertThat(e47).allSatisfy(n -> assertThat(n.get("machine_no").asText()).isEqualTo("PR-03"));
        assertThat(e47.stream().map(n -> n.get("meta").get("cause_class").asText()))
                .as("the point of the demo is one code with three different root causes")
                .containsExactlyInAnyOrder("MATERIAL", "BEDIENUNG", "TECHNIK");
    }

    @Test
    void renderedDocumentsCarryTheProtocolContent() {
        JsonNode first = records.getFirst();
        CorpusProtocol protocol = CorpusProtocol.from(first);
        String document = ProtocolDocumentRenderer.render(protocol);

        assertThat(document).contains(protocol.machineNo(), protocol.title(), protocol.symptom());
        assertThat(document).contains("WARTUNGSPROTOKOLL", "Symptom:", "Ursache:", "Massnahme:");
        assertThat(document)
                .as("empty sections are omitted, not printed as headings with nothing under them")
                .doesNotContain(":\n\n\n");
    }

    @Test
    void germanTextSurvivesAsUtf8() {
        long withUmlauts = records.stream()
                .filter(n -> "de".equals(n.get("language").asText()))
                .filter(n -> (n.get("title").asText() + n.get("symptom").asText() + n.get("action").asText())
                        .matches("(?s).*[äöüÄÖÜß].*"))
                .count();
        assertThat(withUmlauts)
                .as("a corpus of German protocols with no umlauts means the file was read as latin-1")
                .isGreaterThan(50);
    }
}
