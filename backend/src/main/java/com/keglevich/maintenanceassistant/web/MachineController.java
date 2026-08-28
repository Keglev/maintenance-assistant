package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.query.ExampleQuestions;
import com.keglevich.maintenanceassistant.query.MachineCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The machine list behind the search view's picker.
 *
 * <p>It exists because {@code POST /api/query} takes a machine <em>id</em> and search is scoped to
 * exactly one machine (DECISIONS.txt). Without this, a client would need UUIDs it has no way to
 * learn, which is why the previous PR recorded the gap rather than working around it.
 *
 * <p>Open to the same three shop-floor roles as the query itself: knowing which machines the plant
 * has is a precondition of asking about one, so gating it more tightly than the question would make
 * the question unaskable.
 *
 * <p><b>And to the admin, since ADR-006 gave that role a shop-floor function.</b> It cannot ask a
 * question and must not be able to, but the moderation view filters the corpus by machine, and a
 * machine filter needs the machine list. Refusing it produced a live defect: the admin's landing
 * page rendered "Maschinenliste nicht verfügbar" because the first call it made was one the role was
 * correctly forbidden to make. This is plant metadata — ten rows of identifier, name and location,
 * no protocol content and nothing a protocol says — so widening the read is proportionate where
 * widening {@code POST /api/query} would not be.
 */
@RestController
@RequestMapping("/api/machines")
@Tag(name = "Query", description = "Question answering over the indexed maintenance protocols")
class MachineController {

    private final MachineCatalog machines;
    private final ExampleQuestions examples;

    MachineController(MachineCatalog machines, ExampleQuestions examples) {
        this.machines = machines;
        this.examples = examples;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'TECHNIKER', 'SCHICHTLEITER', 'ADMIN')")
    @Operation(summary = "List the machines a question can be asked about",
            description = "Ordered by plant identifier. Unpaged: this is a fixed-size plant. "
                    + "Readable by an administrator too — the moderation filter needs it, and "
                    + "plant metadata carries nothing a protocol says.")
    @ApiResponse(responseCode = "403",
            description = "Caller holds no shop-floor role and is not an administrator")
    List<MachineCatalog.Machine> list() {
        return machines.findAll();
    }

    /**
     * The example questions for one machine, and how many protocols it has.
     *
     * <p>ADR-011. A first-time reader cannot invent a question that reaches a protocol, because
     * they do not know that E-47 exists; this is what the chips under the question box are filled
     * from. Read-only, and available to every role — the examples are QUESTIONS, and what an
     * operator may be TOLD is filtered on the answer path (ADR-006, NFR-3), so filtering the
     * questions as well would mean maintaining a second role matrix over content that carries no
     * protocol text.
     *
     * <p>Addressed by {@code machineNo} rather than by the id the query path takes: the resource
     * file behind it is hand-authored against plant identifiers, and a file keyed by UUID is a file
     * nobody can review.
     */
    @GetMapping("/{machineNo}/examples")
    @PreAuthorize("hasAnyRole('OPERATOR', 'TECHNIKER', 'SCHICHTLEITER', 'ADMIN')")
    @Operation(summary = "Example questions for one machine",
            description = "Questions known to reach a protocol on this machine, in German and "
                    + "English, for a reader who does not yet know what to ask. Every entry is "
                    + "written against a protocol that exists. A machine with no examples returns "
                    + "empty lists rather than an error — there is no question that works, so none "
                    + "is offered.")
    @ApiResponse(responseCode = "200", description = "The machine's examples, possibly empty")
    @ApiResponse(responseCode = "403",
            description = "Caller holds no shop-floor role and is not an administrator")
    @ApiResponse(responseCode = "404", description = "No machine carries this identifier",
            content = @Content)
    ResponseEntity<MachineExamples> examples(
            @Parameter(description = "The plant identifier, e.g. PR-03", example = "PR-03")
            @PathVariable String machineNo) {

        return machines.findByMachineNo(machineNo)
                .map(machine -> ResponseEntity.ok(new MachineExamples(
                        machine.machineNo(),
                        machines.countLiveProtocols(machine.id()),
                        examples.forMachine(machine.machineNo()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @param machineNo     the identifier as stored, so a caller that guessed the case sees the
     *                      canonical spelling back
     * @param protocolCount how many protocols a question about this machine could reach
     * @param examples      the questions, by language
     */
    record MachineExamples(
            @Schema(description = "The plant identifier this list belongs to", example = "PR-03")
            String machineNo,

            @Schema(description = "How many protocols exist for this machine that a question can "
                    + "reach. Live protocols only — a deleted protocol is unreachable by "
                    + "retrieval, so counting it would promise evidence that cannot be returned.",
                    example = "24")
            int protocolCount,

            @Schema(description = "The example questions, keyed by language. Empty lists when this "
                    + "machine has none.")
            ExampleQuestions.Questions examples) {
    }
}
