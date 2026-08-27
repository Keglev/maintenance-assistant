package com.keglevich.maintenanceassistant.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The questions a first-time reader is offered, per machine, per language (ADR-011).
 *
 * <p><b>Why this is data and not a list in the frontend.</b> Every example must reach a protocol —
 * an example that lands on the ungrounded card teaches the opposite of the lesson it exists for —
 * and what protocols exist is the backend's to know. A list in the Angular bundle would drift from
 * the corpus silently and take a frontend deploy to correct a fact this side owns.
 *
 * <p>THE FILE IS VALIDATED AT STARTUP RATHER THAN AT FIRST REQUEST. A malformed resource or a
 * machine number that no longer exists is a packaging error, and a packaging error should stop a
 * deployment rather than surface as an empty picker to whoever clicks first.
 *
 * <p><b>A machine with no entry gets an empty list, not an error.</b> That is the ruled behaviour:
 * where there is no question that works, the honest answer is to offer none, and the frontend shows
 * no chips.
 *
 * <p>SIBLING, AND NOT THE SAME THING: {@code test/resources/retrieval/golden-questions.json} is
 * ADR-008's ratified measurement set and keeps its deliberate Mode B cases. This file is canonical
 * for what a user is offered; editing it is a product change.
 */
@Service
public class ExampleQuestions implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ExampleQuestions.class);
    private static final String RESOURCE = "examples/example-questions.json";

    private final MachineCatalog machines;
    private final Map<String, Questions> byMachineNo;

    ExampleQuestions(MachineCatalog machines) {
        this.machines = machines;
        this.byMachineNo = load();
    }

    /**
     * Every example for one machine, or an empty pair.
     *
     * @param machineNo the plant identifier, as the resource file spells it
     */
    public Questions forMachine(String machineNo) {
        return byMachineNo.getOrDefault(machineNo, Questions.EMPTY);
    }

    /**
     * Reads and parses the resource. A failure here fails the bean, and therefore the application.
     */
    private static Map<String, Questions> load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            Document document = new ObjectMapper().readValue(in, Document.class);
            if (document == null || document.machines() == null || document.machines().isEmpty()) {
                throw new IllegalStateException(RESOURCE + " parsed but carries no machines");
            }
            return new TreeMap<>(document.machines());
        } catch (IOException e) {
            // Named rather than allowed to surface as a bean-creation stack: the one way this
            // happens in practice is a resource that did not make it into the jar, and that
            // sentence is the whole diagnosis.
            throw new UncheckedIOException("cannot read " + RESOURCE + " from the classpath", e);
        }
    }

    /**
     * Cross-checks the file against the machine table once the context is up.
     *
     * <p><b>An unknown machine number FAILS the application; a machine table that cannot be read
     * only WARNS.</b> The asymmetry is the whole design of this method. A file naming a machine
     * that does not exist is a real defect and is worth refusing to start over. Not being able to
     * ASK is a different thing: an empty table is the ordinary state of a slice test that never ran
     * Flyway, and the {@code test} profile has no database at all on purpose — so a hard failure
     * here would make this bean impossible to load in every test that has nothing to do with it.
     *
     * <p>THE FILE ITSELF IS STILL VALIDATED HARD, in the constructor: a resource that does not
     * parse, or does not reach the jar, fails the application whether a database exists or not.
     * What is best-effort is only the part that needs somebody else's data.
     */
    @Override
    public void afterPropertiesSet() {
        int questions = byMachineNo.values().stream().mapToInt(Questions::size).sum();

        List<String> known;
        try {
            known = machines.findAll().stream().map(MachineCatalog.Machine::machineNo).toList();
        } catch (RuntimeException e) {
            log.warn("Example questions loaded ({} machines, {} questions); the machine table could "
                    + "not be read, so nothing was cross-checked: {}",
                    byMachineNo.size(), questions, e.getMessage());
            return;
        }

        if (known.isEmpty()) {
            log.warn("Example questions loaded ({} machines, {} questions) but the machine table is "
                    + "empty, so nothing was cross-checked", byMachineNo.size(), questions);
            return;
        }

        Set<String> unknown = byMachineNo.keySet().stream()
                .filter(machineNo -> !known.contains(machineNo))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " names machines that do not exist: " + unknown);
        }

        log.info("Example questions loaded: {} machines, {} questions ({} de, {} en)",
                byMachineNo.size(), questions,
                byMachineNo.values().stream().mapToInt(m -> m.de().size()).sum(),
                byMachineNo.values().stream().mapToInt(m -> m.en().size()).sum());
    }

    /** The file. {@code version} is read so a future shape change can be detected rather than guessed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(int version, Map<String, Questions> machines) {
    }

    /**
     * One machine's examples.
     *
     * @param de German questions, in the order the file lists them
     * @param en English questions, likewise
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Questions(List<Example> de, List<Example> en) {

        static final Questions EMPTY = new Questions(List.of(), List.of());

        public Questions {
            de = de == null ? List.of() : List.copyOf(de);
            en = en == null ? List.of() : List.copyOf(en);
        }

        int size() {
            return de.size() + en.size();
        }
    }

    /**
     * One example.
     *
     * @param question what the chip puts into the box
     * @param source   the protocol this question was written against. Not shown to anyone: it is
     *                 what lets a test fail when that protocol leaves the corpus, instead of the
     *                 demo failing in front of a reader
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Example(String question, String source) {
    }
}
